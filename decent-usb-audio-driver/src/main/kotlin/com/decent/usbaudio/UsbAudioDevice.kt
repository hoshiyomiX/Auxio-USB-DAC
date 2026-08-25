package com.decent.usbaudio

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import timber.log.Timber
import kotlin.math.sqrt


/**
 * Manages the lifecycle of a USB Audio Class device for bit-perfect output.
 *
 * Responsibilities:
 * - Discover connected USB audio devices
 * - Request user permission via [UsbManager.requestPermission]
 * - Open the device and extract endpoint/interface info
 * - Provide the file descriptor and endpoint addresses to [UsbAudioStream]
 *
 * This class does NOT perform audio I/O — that's handled by the native layer
 * via [UsbAudioStream].
 *
 * @author DecentPlayer project
 */
class UsbAudioDevice private constructor(private val context: Context) {

    private var usbManager: UsbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    // P1 fix: All mutable fields below are accessed from multiple threads:
    //   - Main thread: UsbDacConnectionMonitor.refreshConnectionState (findUsbAudioDevice)
    //   - Background thread: UsbAudioPermissionHelper.handleIntent → Thread { openDevice }
    //   - Renderer thread: UsbAudioSink.configure / handleBuffer / releaseUsbStream
    //   - BroadcastReceiver thread: usbDetachedReceiver.onReceive → Thread { releaseUsbStream }
    // Without @Volatile, changes made by one thread (e.g., openDevice setting
    // `connection`) may not be visible to other threads (e.g., setUsbVolume reading
    // `connection`) due to JMM's memory visibility rules — causing null checks to
    // fail or stale cachedDeviceInfo to be used.
    @Volatile private var connection: UsbDeviceConnection? = null
    @Volatile private var currentDevice: UsbDevice? = null
    @Volatile private var claimedInterface: UsbInterface? = null
    @Volatile private var cachedDeviceInfo: UsbAudioDeviceInfo? = null
    @Volatile private var parsedAltSettings: List<Pair<Int, Int>> = emptyList()

    companion object {
        private const val TAG = "UsbAudioDevice"
        private const val ACTION_USB_PERMISSION_SUFFIX = ".USB_AUDIO_PERMISSION"

        @Volatile
        private var instance: UsbAudioDevice? = null

        /**
         * Get the singleton instance. All callers share the same connection
         * share the same connection and fd, preventing ENODEV from competing opens.
         */
        fun getInstance(context: Context): UsbAudioDevice {
            return instance ?: synchronized(this) {
                instance ?: UsbAudioDevice(context.applicationContext).also { instance = it }
            }
        }
    }


    /**
     * Find the first connected USB audio output device.
     *
     * Scans all USB devices for one with an AudioStreaming interface
     * (class=1, subclass=2) that has an isochronous OUT endpoint.
     *
     * @return The USB device, or null if none found.
     */
    fun findUsbAudioDevice(): UsbDevice? {
        for (device in usbManager.deviceList.values) {
            for (i in 0 until device.interfaceCount) {
                val iface = device.getInterface(i)
                // USB Audio Class: class=1 (Audio), subclass=2 (AudioStreaming)
                if (iface.interfaceClass == UsbConstants.USB_CLASS_AUDIO &&
                    iface.interfaceSubclass == 2) {
                    Timber.tag(TAG).i("Found USB audio device: ${device.productName} " +
                            "(vendor=0x${device.vendorId.toString(16)}, " +
                            "product=0x${device.productId.toString(16)})")
                    return device
                }
            }
        }
        Timber.tag(TAG).d("No USB audio device found")
        return null
    }

    /**
     * Check if we already have permission to access the device.
     */
    fun hasPermission(device: UsbDevice): Boolean {
        return usbManager.hasPermission(device)
    }

    /**
     * Request permission from the user to access the USB device.
     *
     * @param device   The USB device to request access for.
     * @param callback Called with true if permission granted, false otherwise.
     */
    fun requestPermission(device: UsbDevice, callback: (Boolean) -> Unit) {
        if (usbManager.hasPermission(device)) {
            Timber.tag(TAG).i("Permission already granted for ${device.productName}")
            callback(true)
            return
        }

        val intent = Intent(context.packageName + ACTION_USB_PERMISSION_SUFFIX)
        intent.setPackage(context.packageName)
        val permissionIntent = PendingIntent.getBroadcast(
                context, 0,
                intent,
                PendingIntent.FLAG_MUTABLE
        )

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action == context.packageName + ACTION_USB_PERMISSION_SUFFIX) {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    Timber.tag(TAG).i("USB permission result: granted=$granted for ${device.productName}")
                    context.unregisterReceiver(this)
                    callback(granted)
                }
            }
        }

        val filter = IntentFilter(context.packageName + ACTION_USB_PERMISSION_SUFFIX)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }

        usbManager.requestPermission(device, permissionIntent)
        Timber.tag(TAG).i("Permission requested for ${device.productName}")
    }

    /**
     * Open the USB device and extract all information needed for audio I/O.
     *
     * Finds the AudioStreaming interface, locates the isochronous OUT and
     * feedback IN endpoints, and returns everything the native layer needs.
     *
     * @param device The USB audio device to open.
     * @return Device info with fd and endpoint addresses, or null on failure.
     */
    fun openDevice(device: UsbDevice): UsbAudioDeviceInfo? {
        // Return cached info if already open with valid connection
        val cached = cachedDeviceInfo
        if (cached != null && connection != null) {
            Timber.tag(TAG).i("Device already open, reusing fd=${cached.fd}")
            return cached
        }
        // Close any stale connection before opening new
        closeDevice()
        val conn = usbManager.openDevice(device)
        if (conn == null) {
            Timber.tag(TAG).e("Failed to open device ${device.productName}")
            return null
        }

        // Find the AudioStreaming interface and its endpoints
        var streamingInterface: UsbInterface? = null
        var endpointOut = -1
        var endpointFeedback = -1
        var maxPacketSize = 0
        var altSettingCount = 0

        // Count alternate settings for the streaming interface
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == UsbConstants.USB_CLASS_AUDIO &&
                iface.interfaceSubclass == 2) {
                altSettingCount++

                // Look for endpoints in non-zero alt settings
                if (iface.endpointCount > 0 && streamingInterface == null) {
                    streamingInterface = iface

                    for (e in 0 until iface.endpointCount) {
                        val ep = iface.getEndpoint(e)
                        when {
                            // Isochronous OUT endpoint (audio data)
                            ep.type == UsbConstants.USB_ENDPOINT_XFER_ISOC &&
                                    ep.direction == UsbConstants.USB_DIR_OUT -> {
                                endpointOut = ep.address
                                maxPacketSize = ep.maxPacketSize
                                Timber.tag(TAG).i("Found ISO OUT endpoint: address=0x${ep.address.toString(16)}, " +
                                        "maxPacket=$maxPacketSize, interval=${ep.interval}")
                            }
                            // Isochronous IN endpoint (feedback)
                            ep.type == UsbConstants.USB_ENDPOINT_XFER_ISOC &&
                                    ep.direction == UsbConstants.USB_DIR_IN -> {
                                endpointFeedback = ep.address
                                Timber.tag(TAG).i("Found ISO IN (feedback) endpoint: address=0x${ep.address.toString(16)}, " +
                                        "interval=${ep.interval}")
                            }
                        }
                    }
                }
            }
        }

        if (streamingInterface == null || endpointOut < 0) {
            Timber.tag(TAG).e("No suitable AudioStreaming interface/endpoint found")
            conn.close()
            return null
        }

        // Claim the AudioControl interface (0) with force=true to disconnect kernel driver
        val controlInterface = (0 until device.interfaceCount)
                .map { device.getInterface(it) }
                .firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_AUDIO && it.interfaceSubclass == 1 }

        if (controlInterface != null) {
            val claimed = conn.claimInterface(controlInterface, true)
            Timber.tag(TAG).i("Claimed AudioControl interface ${controlInterface.id} force=true: $claimed")
        }

        // Claim the AudioStreaming interface with force=true to disconnect kernel driver (snd-usb-audio)
        // NOTE: We claim the zero-bandwidth alt setting (alt=0). The actual streaming alt setting
        // will be activated later via setInterface() which allocates USB bandwidth.
        val claimed = conn.claimInterface(streamingInterface, true)
        Timber.tag(TAG).i("Claimed AudioStreaming interface ${streamingInterface.id} force=true: $claimed " +
                "(alt=${streamingInterface.alternateSetting}, endpoints=${streamingInterface.endpointCount})")
        if (!claimed) {
            Timber.tag(TAG).e("Failed to claim streaming interface — kernel driver may still be active")
            conn.close()
            return null
        }
        claimedInterface = streamingInterface

        // Force alt=0 to stop any streaming left by kernel driver
        val zeroAlt = (0 until device.interfaceCount)
                .map { device.getInterface(it) }
                .firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_AUDIO &&
                        it.interfaceSubclass == 2 && it.alternateSetting == 0 }
        if (zeroAlt != null) {
            conn.setInterface(zeroAlt)
            Timber.tag(TAG).i("Reset streaming to alt=0 (zero-bandwidth)")
        }
        Thread.sleep(100)

        // Log all available alt settings for debugging
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == UsbConstants.USB_CLASS_AUDIO && iface.interfaceSubclass == 2) {
                Timber.tag(TAG).d("  AudioStreaming alt=${iface.alternateSetting}: " +
                        "id=${iface.id}, endpoints=${iface.endpointCount}")
            }
        }

        val fd = conn.fileDescriptor
        val interfaceId = streamingInterface.id

        Timber.tag(TAG).i("Device opened: ${device.productName}, fd=$fd, " +
                "iface=$interfaceId, epOut=0x${endpointOut.toString(16)}, " +
                "epFb=0x${endpointFeedback.toString(16)}, " +
                "maxPacket=$maxPacketSize, altSettings=$altSettingCount")

        connection = conn
        currentDevice = device

        // Auto-detect Clock Source ID, Feature Unit ID, AudioControl interface number,
        // and best alt setting from USB descriptors
        val clockSourceId = parseClockSourceId(conn)
        val (featureUnitId, audioControlIfaceId) = parseFeatureUnitId(conn)
        val (bestAlt, bestBits) = parseBestAltSetting(conn)
        Timber.tag(TAG).i("Auto-detected: clockSourceId=0x${clockSourceId.toString(16)}, " +
                "featureUnitId=0x${featureUnitId.toString(16)}, " +
                "audioControlIface=$audioControlIfaceId, " +
                "bestAlt=$bestAlt, bestBits=$bestBits")

        // Query the DAC's actual hardware volume range via GET_MIN/GET_MAX.
        // This prevents sending SET_CUR values outside the DAC's supported range,
        // which was the root cause of the "0-90% silence, 90-100% max" volume bug.
        val (volumeMin, volumeMax, volumeRangeFailed) = if (featureUnitId >= 0 && audioControlIfaceId >= 0) {
            queryVolumeRange(conn, featureUnitId, audioControlIfaceId)
        } else {
            Timber.tag(TAG).w("Skipping volume range query — no Feature Unit or AudioControl interface")
            Triple(UsbAudioDeviceInfo.DEFAULT_VOLUME_MIN, UsbAudioDeviceInfo.DEFAULT_VOLUME_MAX, false)
        }

        val info = UsbAudioDeviceInfo(
                connection = conn,
                fd = fd,
                deviceName = device.productName ?: "USB Audio Device",
                interfaceId = interfaceId,
                endpointOutAddress = endpointOut,
                endpointFeedbackAddress = endpointFeedback,
                maxPacketSize = maxPacketSize,
                altSettingCount = altSettingCount,
                clockSourceId = clockSourceId,
                featureUnitId = featureUnitId,
                bestAltSetting = bestAlt,
                bestBitDepth = bestBits,
                audioControlInterfaceId = if (audioControlIfaceId >= 0) audioControlIfaceId else 0,
                volumeMin = volumeMin,
                volumeMax = volumeMax,
                volumeRangeQueryFailed = volumeRangeFailed
        )
        cachedDeviceInfo = info
        return info
    }

    /**
     * Perform a USB device reset via native ioctl, then close and reopen.
     * This clears any stale clock/endpoint state left by the kernel driver.
     * After reset, the DAC reinitializes and will accept our SET_CUR.
     */
    fun resetAndReopen() {
        val conn = connection ?: return
        val fd = conn.fileDescriptor

        Timber.tag(TAG).i("Performing REAL USBDEVFS_RESET on fd=$fd...")

        // Real USB port reset via native ioctl — resets DAC clock state
        val ret = UsbAudioStream.nativeUsbReset(fd)
        Timber.tag(TAG).i("USBDEVFS_RESET result: $ret")

        // Reset releases all interface claims. The fd remains valid.
        // Clear cache so openDevice re-claims, but KEEP the connection
        // so the same fd is reused (native claims are on this fd).
        cachedDeviceInfo = null
        claimedInterface = null
        // DO NOT close connection — the fd from reset+native claim must be reused
        // The next openDevice() will see connection != null and skip re-opening
    }

    /**
     * Parse raw USB descriptors to find the UAC2 Clock Source entity ID.
     * This is the entity that controls the DAC's sample rate.
     *
     * Scans the AudioControl interface descriptors for a CLOCK_SOURCE
     * descriptor (bDescriptorSubtype = 0x0A) and returns its bClockID.
     *
     * @return Clock Source entity ID, or -1 if not found.
     */
    private fun parseClockSourceId(conn: UsbDeviceConnection): Int {
        val raw = conn.rawDescriptors ?: return -1

        var i = 0
        var inAudioControl = false

        while (i + 1 < raw.size) {
            val bLength = raw[i].toInt() and 0xFF
            if (bLength < 2) break
            if (i + bLength > raw.size) break

            val bDescriptorType = raw[i + 1].toInt() and 0xFF

            // Interface descriptor (0x04)
            if (bDescriptorType == 0x04 && bLength >= 9) {
                val bInterfaceClass = raw[i + 5].toInt() and 0xFF
                val bInterfaceSubClass = raw[i + 6].toInt() and 0xFF
                // AudioControl = class 1, subclass 1
                inAudioControl = (bInterfaceClass == 1 && bInterfaceSubClass == 1)
            }

            // CS_INTERFACE descriptor (0x24) inside AudioControl
            if (inAudioControl && bDescriptorType == 0x24 && bLength >= 3) {
                val bDescriptorSubtype = raw[i + 2].toInt() and 0xFF
                // CLOCK_SOURCE = 0x0A
                if (bDescriptorSubtype == 0x0A && bLength >= 5) {
                    val bClockID = raw[i + 3].toInt() and 0xFF
                    Timber.tag(TAG).i("parseClockSourceId: found CLOCK_SOURCE bClockID=0x${bClockID.toString(16)}")
                    return bClockID
                }
            }

            i += bLength
        }

        Timber.tag(TAG).w("parseClockSourceId: no CLOCK_SOURCE descriptor found")
        return -1
    }

    /**
     * Parse raw USB descriptors to find the UAC2 Feature Unit entity ID AND the AudioControl
     * interface number. Both are needed for hardware volume control:
     * - Feature Unit ID → upper byte of wIndex in SET_CUR/GET_CUR
     * - AudioControl interface number → lower byte of wIndex in SET_CUR/GET_CUR
     *
     * Returns a Pair(featureUnitId, audioControlInterfaceId). Either may be -1 if not found.
     */
    private fun parseFeatureUnitId(conn: UsbDeviceConnection): Pair<Int, Int> {
        val raw = conn.rawDescriptors ?: return Pair(-1, -1)

        var i = 0
        var inAudioControl = false
        var audioControlIfaceNum = -1

        while (i + 1 < raw.size) {
            val bLength = raw[i].toInt() and 0xFF
            if (bLength < 2) break
            if (i + bLength > raw.size) break

            val bDescriptorType = raw[i + 1].toInt() and 0xFF

            // Interface descriptor (0x04)
            if (bDescriptorType == 0x04 && bLength >= 9) {
                val bInterfaceNumber = raw[i + 2].toInt() and 0xFF
                val bInterfaceClass = raw[i + 5].toInt() and 0xFF
                val bInterfaceSubClass = raw[i + 6].toInt() and 0xFF
                // AudioControl = class 1, subclass 1
                inAudioControl = (bInterfaceClass == 1 && bInterfaceSubClass == 1)
                if (inAudioControl && audioControlIfaceNum < 0) {
                    audioControlIfaceNum = bInterfaceNumber
                    Timber.tag(TAG).i("parseFeatureUnitId: AudioControl interface number = $bInterfaceNumber")
                }
            }

            // CS_INTERFACE descriptor (0x24) inside AudioControl
            if (inAudioControl && bDescriptorType == 0x24 && bLength >= 4) {
                val bDescriptorSubtype = raw[i + 2].toInt() and 0xFF
                // FEATURE_UNIT = 0x06
                if (bDescriptorSubtype == 0x06 && bLength >= 7) {
                    val bUnitID = raw[i + 3].toInt() and 0xFF
                    val bSourceID = raw[i + 4].toInt() and 0xFF
                    val bControlSize = raw[i + 5].toInt() and 0xFF
                    // Parse bmControls bitmap — tells us which controls the DAC actually supports.
                    // Bit 0 (0x01): MUTE, Bit 1 (0x02): VOLUME, Bit 2 (0x04): BASS, etc.
                    val bmControls = if (bControlSize >= 1 && i + 6 < raw.size) raw[i + 6].toInt() and 0xFF else 0
                    val supportsMute = (bmControls and 0x01) != 0
                    val supportsVolume = (bmControls and 0x02) != 0
                    Timber.tag(TAG).i("parseFeatureUnitId: found FEATURE_UNIT bUnitID=0x${bUnitID.toString(16)} " +
                            "in AudioControl iface=$audioControlIfaceNum, bSourceID=0x${bSourceID.toString(16)}, " +
                            "bControlSize=$bControlSize, bmControls=0x${bmControls.toString(16)} " +
                            "(MUTE=$supportsMute, VOLUME=$supportsVolume)")
                    if (!supportsVolume) {
                        Timber.tag(TAG).w("parseFeatureUnitId: DAC does NOT advertise VOLUME control in bmControls!" +
                                " SET_CUR on CS=0x01 will be silently ignored by DAC firmware.")
                    }
                    return Pair(bUnitID, audioControlIfaceNum)
                }
            }

            i += bLength
        }

        Timber.tag(TAG).w("parseFeatureUnitId: no FEATURE_UNIT descriptor found — hardware volume control unavailable")
        return Pair(-1, audioControlIfaceNum)
    }

    /**
     * Query the DAC's hardware volume range via UAC2 GET_MIN and GET_MAX on the Feature Unit
     * volume control. Returns a Pair(min, max) of signed 16-bit values in 1/256 dB.
     *
     * If either query fails, returns the safe defaults (min = -50 dB, max = 0 dB) instead of
     * the full UAC2 range (min = -127.99 dB). This prevents sending SET_CUR values that are
     * outside the DAC's supported range, which can cause the DAC to mute entirely — the
     * root cause of the "0-90% = silence, 90-100% = max" volume bug.
     */
    private fun queryVolumeRange(conn: UsbDeviceConnection, fuId: Int, ifaceId: Int): Triple<Short, Short, Boolean> {
        val wValue = 0x0100
        val wIndex = (fuId shl 8) or (ifaceId and 0xFF)

        var minVal: Short = UsbAudioDeviceInfo.DEFAULT_VOLUME_MIN
        var maxVal: Short = UsbAudioDeviceInfo.DEFAULT_VOLUME_MAX
        var minFailed = false
        var maxFailed = false

        // GET_MIN (bRequest = 0x82)
        val minData = ByteArray(2)
        val minRet = conn.controlTransfer(
            0xA1, 0x82, wValue, wIndex, minData, minData.size, 500
        )
        if (minRet >= 2) {
            val raw = ((minData[1].toInt() and 0xFF) shl 8) or (minData[0].toInt() and 0xFF)
            val signed = raw.toShort()
            if (signed < 0) {
                minVal = if (signed <= -32768) -32767 else signed
                val db = minVal.toDouble() / 256.0
                Timber.tag(TAG).i("queryVolumeRange: GET_MIN = $signed → clamped to $minVal " +
                        "(0x${(minVal.toInt() and 0xFFFF).toString(16)}) = ${"%.2f".format(db)} dB")
            } else {
                minFailed = true
                Timber.tag(TAG).w("queryVolumeRange: GET_MIN returned non-negative value $signed — using default")
            }
        } else {
            minFailed = true
            Timber.tag(TAG).w("queryVolumeRange: GET_MIN failed (ret=$minRet) — using default min = -50 dB")
        }

        // GET_MAX (bRequest = 0x83)
        val maxData = ByteArray(2)
        val maxRet = conn.controlTransfer(
            0xA1, 0x83, wValue, wIndex, maxData, maxData.size, 500
        )
        if (maxRet >= 2) {
            val raw = ((maxData[1].toInt() and 0xFF) shl 8) or (maxData[0].toInt() and 0xFF)
            val signed = raw.toShort()
            if (signed >= 0) {
                maxVal = signed
                val db = signed.toDouble() / 256.0
                Timber.tag(TAG).i("queryVolumeRange: GET_MAX = $signed (0x${(raw and 0xFFFF).toString(16)}) = ${"%.2f".format(db)} dB")
            } else {
                maxFailed = true
                Timber.tag(TAG).w("queryVolumeRange: GET_MAX returned negative value $signed — using default")
            }
        } else {
            maxFailed = true
            Timber.tag(TAG).w("queryVolumeRange: GET_MAX failed (ret=$maxRet) — using default max = 0 dB")
        }

        // GET_RES (bRequest = 0x84) — resolution step. Some DACs support this even
        // when GET_MIN/GET_MAX fail. If it succeeds, the DAC DOES implement volume
        // control — we just need to figure out the right format.
        val resData = ByteArray(2)
        val resRet = conn.controlTransfer(
            0xA1, 0x84, wValue, wIndex, resData, resData.size, 500
        )
        if (resRet >= 2) {
            val resRaw = ((resData[1].toInt() and 0xFF) shl 8) or (resData[0].toInt() and 0xFF)
            Timber.tag(TAG).i("queryVolumeRange: GET_RES = $resRaw (0x${resRaw.toString(16)}) — DAC supports volume resolution")
        } else {
            Timber.tag(TAG).w("queryVolumeRange: GET_RES failed (ret=$resRet)")
        }

        // GET_CUR (bRequest = 0x81) — read current volume. This tells us what format
        // the DAC uses internally. If the DAC reports a non-zero value here, it's
        // likely using unsigned linear format, not signed dB.
        val curData = ByteArray(2)
        val curRet = conn.controlTransfer(
            0xA1, 0x81, wValue, wIndex, curData, curData.size, 500
        )
        if (curRet >= 2) {
            val curRaw = ((curData[1].toInt() and 0xFF) shl 8) or (curData[0].toInt() and 0xFF)
            val curSigned = curRaw.toShort()
            val curUnsigned = curRaw and 0xFFFF
            Timber.tag(TAG).i("queryVolumeRange: GET_CUR = signed=$curSigned unsigned=$curUnsigned (0x${curRaw.toString(16)})" +
                    " — current DAC volume level")
        } else {
            Timber.tag(TAG).w("queryVolumeRange: GET_CUR failed (ret=$curRet)")
        }

        val bothFailed = minFailed && maxFailed
        if (bothFailed) {
            Timber.tag(TAG).w("queryVolumeRange: BOTH GET_MIN and GET_MAX failed — DAC likely uses" +
                    " unsigned linear volume format (0x0000=mute, 0x7FFF=max). Will use unsigned format in setUsbVolume.")
        }

        // Degenerate-range detection
        if (minVal >= maxVal) {
            Timber.tag(TAG).w("queryVolumeRange: degenerate range [$minVal, $maxVal] — resetting to defaults")
            minVal = UsbAudioDeviceInfo.DEFAULT_VOLUME_MIN
            maxVal = UsbAudioDeviceInfo.DEFAULT_VOLUME_MAX
        }

        Timber.tag(TAG).i("queryVolumeRange: final range = [${minVal}, ${maxVal}] rangeQueryFailed=$bothFailed")
        return Triple(minVal, maxVal, bothFailed)
    }

    /**
     * Set the USB DAC hardware volume via UAC2 SET_CUR on the Feature Unit volume control.
     *
     * UAC2 FU_VOLUME_CONTROL uses a signed 16-bit fixed-point value in 1/256 dB units.
     * The DAC's actual volume range is hardware-dependent and is queried at device-open
     * time via GET_MIN/GET_MAX (stored in [UsbAudioDeviceInfo.volumeMin] and
     * [UsbAudioDeviceInfo.volumeMax]).
     *
     * We map the input float (0.0 = silent, 1.0 = max) to a PERCEPTUAL power curve
     * before applying a linear-dB scale across the DAC's actual supported range:
     *
     *   warped = sqrt(volume)
     *   dbFixed = volumeMax + (volumeMin - volumeMax) * (1 - warped)
     *
     * The sqrt() warp gives finer control at low slider positions (the user's "below
     * half = off" complaint) and coarser control at high positions. Without it, the
     * linear-dB mapping with the default -50 dB range produced:
     *   - Slider 0-30% → inaudible (-25 to -50 dB, below hearing threshold)
     *   - Slider 30-50% → barely audible (perceived as "still off")
     *   - Slider 50-100% → rapidly increasing (perceived as "sudden peak at half")
     *
     * With the sqrt() warp:
     *   - Slider 25% → warped 0.5 → -25 dB (audible)
     *   - Slider 50% → warped 0.707 → -14.6 dB (moderate)
     *   - Slider 75% → warped 0.866 → -6.7 dB (loud)
     *   - Slider 100% → warped 1.0 → 0 dB (max)
     *
     * This is the standard "audio taper" curve used by most pro-audio volume sliders.
     *
     * @param volume Linear gain in [0.0, 1.0]. Values outside this range are clamped.
     * @return True if the SET_CUR control transfer succeeded, false otherwise (e.g.
     *   no Feature Unit was parsed from descriptors, USB transfer failed).
     */
    fun setUsbVolume(volume: Float): Boolean {
        val conn = connection ?: return false
        val info = cachedDeviceInfo
        val fuId = info?.featureUnitId ?: -1
        if (fuId < 0) {
            Timber.tag(TAG).w("setUsbVolume: no Feature Unit ID available — hardware volume unsupported")
            return false
        }

        val clamped = volume.coerceIn(0f, 1f)
        val wValue = 0x0100
        val ifaceId = info?.audioControlInterfaceId ?: 0
        val wIndex = (fuId shl 8) or (ifaceId and 0xFF)

        // Determine volume format: if GET_MIN/GET_MAX both failed, the DAC likely uses
        // unsigned linear format (0x0000=mute, 0x7FFF=max) instead of UAC2 signed dB.
        // Neutron Music Player confirms CX31993 supports HW volume — we were just sending
        // the wrong format (signed dB when DAC expects unsigned linear).
        val useUnsignedLinear = info?.volumeRangeQueryFailed == true

        val raw16: Int = if (useUnsignedLinear) {
            // Unsigned linear: 0x0000 (0%) to 0x7FFF (100%).
            // Apply sqrt warp for perceptual taper (same as signed dB path).
            val warped = if (clamped <= 0f) 0.0 else sqrt(clamped.toDouble())
            val unsignedVal = (warped * 0x7FFF).toInt().coerceIn(0, 0x7FFF)
            Timber.tag(TAG).d("setUsbVolume: UNSIGNED LINEAR linear=$clamped → warped=${"%.4f".format(warped)}" +
                    " → raw=0x${unsignedVal.toString(16)} (unsigned)")
            unsignedVal
        } else {
            // Signed dB format (UAC2 standard). Original code path.
            if (clamped <= 0f) {
                val minFixed0 = maxOf(
                    (info?.volumeMin ?: UsbAudioDeviceInfo.DEFAULT_VOLUME_MIN).toInt(),
                    UsbAudioDeviceInfo.DEFAULT_VOLUME_MIN.toInt()
                )
                val maxFixed0 = minOf(
                    (info?.volumeMax ?: UsbAudioDeviceInfo.DEFAULT_VOLUME_MAX).toInt(),
                    UsbAudioDeviceInfo.CEILING_VOLUME_MAX.toInt()
                )
                val safeMin0 = if (minFixed0 < maxFixed0) minFixed0 else UsbAudioDeviceInfo.DEFAULT_VOLUME_MIN.toInt()
                safeMin0
            } else {
                val warped = sqrt(clamped.toDouble())
                val minFixed = maxOf(
                    (info?.volumeMin ?: UsbAudioDeviceInfo.DEFAULT_VOLUME_MIN).toInt(),
                    UsbAudioDeviceInfo.DEFAULT_VOLUME_MIN.toInt()
                )
                val maxFixed = minOf(
                    (info?.volumeMax ?: UsbAudioDeviceInfo.DEFAULT_VOLUME_MAX).toInt(),
                    UsbAudioDeviceInfo.CEILING_VOLUME_MAX.toInt()
                )
                val safeMin: Int
                val safeMax: Int
                if (minFixed < maxFixed) {
                    safeMin = minFixed
                    safeMax = maxFixed
                } else {
                    Timber.tag(TAG).w("setUsbVolume: degenerate range [$minFixed, $maxFixed] — using defaults")
                    safeMin = UsbAudioDeviceInfo.DEFAULT_VOLUME_MIN.toInt()
                    safeMax = UsbAudioDeviceInfo.CEILING_VOLUME_MAX.toInt()
                }
                val ratio = (1.0 - warped)
                val dbFixed = safeMax + ((safeMin - safeMax) * ratio).toInt()
                dbFixed.coerceIn(safeMin, safeMax)
            }
        }

        val data = ByteArray(2)
        data[0] = (raw16 and 0xFF).toByte()
        data[1] = ((raw16 shr 8) and 0xFF).toByte()

        val formatStr = if (useUnsignedLinear) "UNSIGNED" else "SIGNED_DB"
        val dbValue = raw16.toDouble() / 256.0
        Timber.tag(TAG).d("setUsbVolume: linear=$clamped format=$formatStr raw=0x${(raw16 and 0xFFFF).toString(16)}" +
                " (${if (useUnsignedLinear) "unsigned=$raw16" else "%.2f dB".format(dbValue)})," +
                " range=[${info?.volumeMin}, ${info?.volumeMax}], fuId=0x${fuId.toString(16)}, iface=$ifaceId")

        val ret = conn.controlTransfer(
                0x21, 0x01, wValue, wIndex, data, data.size, 500
        )
        if (ret >= 0) {
            Timber.tag(TAG).i("setUsbVolume($volume → $clamped, raw=0x${(raw16 and 0xFFFF).toString(16)}, fmt=$formatStr): " +
                    "SUCCESS with featureUnitId=0x${fuId.toString(16)}, iface=$ifaceId")

            // Verify with GET_CUR — read back the value the DAC actually stored.
            // If it matches what we sent, the DAC is implementing volume control.
            // If it differs, the DAC may be ignoring SET_CUR or using a different format.
            val verifyData = ByteArray(2)
            val verifyRet = conn.controlTransfer(
                0xA1, 0x81, wValue, wIndex, verifyData, verifyData.size, 500
            )
            if (verifyRet >= 2) {
                val verifyRaw = ((verifyData[1].toInt() and 0xFF) shl 8) or (verifyData[0].toInt() and 0xFF)
                val match = verifyRaw == (raw16 and 0xFFFF)
                Timber.tag(TAG).i("setUsbVolume: GET_CUR verify = 0x${verifyRaw.toString(16)}" +
                        " (sent=0x${(raw16 and 0xFFFF).toString(16)}, match=$match)")
            } else {
                Timber.tag(TAG).w("setUsbVolume: GET_CUR verify failed (ret=$verifyRet) — DAC may not support readback")
            }
            return true
        }
        Timber.tag(TAG).w("setUsbVolume($volume): SET_CUR failed (ret=$ret) — DAC may not support hardware volume")
        return false
    }

    /**
     * Read the current USB DAC hardware volume via UAC2 GET_CUR on the Feature Unit
     * volume control. Inverse of [setUsbVolume].
     *
     * @return The current volume as a linear gain in [0.0, 1.0], or -1f on error
     *   (e.g. no Feature Unit, GET_CUR failed).
     */
    fun getUsbVolume(): Float {
        val conn = connection ?: return -1f
        val info = cachedDeviceInfo
        val fuId = info?.featureUnitId ?: -1
        if (fuId < 0) return -1f

        val data = ByteArray(2)
        val wValue = 0x0100
        val ifaceId = info?.audioControlInterfaceId ?: 0
        val wIndex = (fuId shl 8) or (ifaceId and 0xFF)

        val ret = conn.controlTransfer(
                0xA1,    // bmRequestType: Device-to-Host, Class, Interface
                0x81,    // bRequest: GET_CUR
                wValue,
                wIndex,
                data,
                data.size,
                500
        )
        if (ret < 2) {
            Timber.tag(TAG).w("getUsbVolume: GET_CUR failed (ret=$ret)")
            return -1f
        }
        val raw16 = ((data[1].toInt() and 0xFF) shl 8) or (data[0].toInt() and 0xFF)
        val signed = if (raw16 >= 0x8000) raw16 - 0x10000 else raw16
        if (signed <= -32768) return 0f  // Mute (0x8000)

        // Reverse the perceptual sqrt() + linear-dB mapping from setUsbVolume:
        //   warped = sqrt(volume) → volume = warped^2
        //   dbFixed = volumeMax + (volumeMin - volumeMax) * (1 - warped)
        //   → warped = 1 - (dbFixed - volumeMax) / (volumeMin - volumeMax)
        //   → volume = warped^2
        //
        // R-1 fix: Mirror the hearing-threshold clamp from setUsbVolume. If the DAC
        // reports volumeMin below -50 dB but setUsbVolume clamps the effective min to
        // -50 dB, getUsbVolume must use the same effective min for the inverse math —
        // otherwise round-trip is broken (setUsbVolume(0.5) → getUsbVolume() != 0.5).
        //
        // Note: DEFAULT_VOLUME_MIN is Short; convert to Int for maxOf (see setUsbVolume).
        val minFixed = maxOf(
            (info?.volumeMin ?: UsbAudioDeviceInfo.DEFAULT_VOLUME_MIN).toInt(),
            UsbAudioDeviceInfo.DEFAULT_VOLUME_MIN.toInt()
        )
        // R-2 ceiling fix: Mirror the ceiling clamp from setUsbVolume. If setUsbVolume
        // hard-caps effective volumeMax at CEILING_VOLUME_MAX, getUsbVolume must use the
        // same effective max for the inverse math — otherwise round-trip is broken
        // (setUsbVolume(1.0) → raw16 = -496 → getUsbVolume without mirror returns 0.99
        // instead of 1.0). minOf picks the lower of (DAC's actual volumeMax, ceiling).
        val maxFixed = minOf(
            (info?.volumeMax ?: UsbAudioDeviceInfo.DEFAULT_VOLUME_MAX).toInt(),
            UsbAudioDeviceInfo.CEILING_VOLUME_MAX.toInt()
        )
        // Mirror the safety guard from setUsbVolume: if the cached range is degenerate
        // (min >= max), use safe defaults so round-trip stays consistent.
        val safeMin: Int
        val safeMax: Int
        if (minFixed < maxFixed) {
            safeMin = minFixed
            safeMax = maxFixed
        } else {
            Timber.tag(TAG).w("getUsbVolume: degenerate range [$minFixed, $maxFixed] — using defaults")
            safeMin = UsbAudioDeviceInfo.DEFAULT_VOLUME_MIN.toInt()
            safeMax = UsbAudioDeviceInfo.CEILING_VOLUME_MAX.toInt()
        }
        val range = safeMin - safeMax  // negative (min < max in dB)
        val linear = if (range == 0) {
            1f  // Degenerate range — no attenuation possible, always max
        } else {
            val warped = (1.0 - (signed - safeMax).toDouble() / range)
                .coerceIn(0.0, 1.0)  // 0.0 at min, 1.0 at max
            // Inverse of sqrt() is squaring — gives the slider position that would
            // produce this hardware volume level.
            (warped * warped).toFloat().coerceIn(0f, 1f)
        }
        return linear
    }

    /**
     * Parse raw USB descriptors to find the best (highest bit depth) alt setting
     * for the AudioStreaming interface.
     *
     * Scans AS Format Type I descriptors (CS_INTERFACE 0x02) for bBitResolution
     * and returns the alt setting with the highest value.
     *
     * @return Pair(altSetting, bitDepth), or Pair(1, 16) as default.
     */
    private fun parseBestAltSetting(conn: UsbDeviceConnection): Pair<Int, Int> {
        val raw = conn.rawDescriptors ?: return Pair(1, 16)
        val altSettings = mutableListOf<Pair<Int, Int>>()

        var i = 0
        var currentAlt = 0
        var inAudioStreaming = false
        var bestAlt = 1
        var bestBits = 16

        while (i + 1 < raw.size) {
            val bLength = raw[i].toInt() and 0xFF
            if (bLength < 2) break
            if (i + bLength > raw.size) break

            val bDescriptorType = raw[i + 1].toInt() and 0xFF

            // Interface descriptor (0x04)
            if (bDescriptorType == 0x04 && bLength >= 9) {
                val bInterfaceClass = raw[i + 5].toInt() and 0xFF
                val bInterfaceSubClass = raw[i + 6].toInt() and 0xFF
                val bAlternateSetting = raw[i + 3].toInt() and 0xFF
                inAudioStreaming = (bInterfaceClass == 1 && bInterfaceSubClass == 2)
                if (inAudioStreaming) currentAlt = bAlternateSetting
            }

            // CS_INTERFACE (0x24) in AudioStreaming — Format Type I (subtype 0x02)
            if (inAudioStreaming && bDescriptorType == 0x24 && bLength >= 6) {
                val bDescriptorSubtype = raw[i + 2].toInt() and 0xFF
                if (bDescriptorSubtype == 0x02) {
                    val bSubslotSize = raw[i + 4].toInt() and 0xFF
                    val bBitResolution = raw[i + 5].toInt() and 0xFF
                    Timber.tag(TAG).i("parseBestAltSetting: alt=$currentAlt subslotSize=$bSubslotSize bitResolution=$bBitResolution")

                    if (currentAlt > 0) {
                        altSettings.add(Pair(currentAlt, bBitResolution))
                    }
                    if (bBitResolution > bestBits && currentAlt > 0) {
                        bestBits = bBitResolution
                        bestAlt = currentAlt
                    }
                }
            }

            i += bLength
        }

        parsedAltSettings = altSettings
        Timber.tag(TAG).i("parseBestAltSetting: best alt=$bestAlt bits=$bestBits, all=$altSettings")
        return Pair(bestAlt, bestBits)
    }

    /**
     * Find the alt setting that matches the given source bit depth exactly.
     * If no exact match, returns the next higher bit depth.
     * Fallback: returns the best (highest) alt setting.
     *
     * @return Pair(altSetting, bitDepth)
     */
    fun findAltSettingForBitDepth(targetBitDepth: Int): Pair<Int, Int> {
        if (parsedAltSettings.isEmpty()) {
            val info = cachedDeviceInfo ?: return Pair(1, 16)
            return Pair(info.bestAltSetting, info.bestBitDepth)
        }

        // Exact match
        val exact = parsedAltSettings.firstOrNull { it.second == targetBitDepth }
        if (exact != null) {
            Timber.tag(TAG).i("findAltSettingForBitDepth($targetBitDepth): exact match alt=${exact.first}")
            return exact
        }

        // Next higher
        val higher = parsedAltSettings
                .filter { it.second > targetBitDepth }
                .minByOrNull { it.second }
        if (higher != null) {
            Timber.tag(TAG).i("findAltSettingForBitDepth($targetBitDepth): next higher alt=${higher.first} bits=${higher.second}")
            return higher
        }

        // Fallback to best
        val best = parsedAltSettings.maxByOrNull { it.second } ?: Pair(1, 16)
        Timber.tag(TAG).i("findAltSettingForBitDepth($targetBitDepth): fallback to best alt=${best.first} bits=${best.second}")
        return best
    }

    /**
     * Close the USB device and release all resources.
     */
    fun closeDevice() {
        cachedDeviceInfo = null
        claimedInterface?.let { iface ->
            connection?.releaseInterface(iface)
            claimedInterface = null
        }
        connection?.close()
        connection = null
        currentDevice = null
        Timber.tag(TAG).i("USB device closed")
    }

    /**
     * Set the sample rate on a UAC2 Clock Source entity via SET_CUR control transfer.
     *
     * Tries multiple common clock source entity IDs since we can't easily read
     * the AudioControl descriptors from userspace on Android.
     *
     * UAC2 SET_CUR format:
     *   bmRequestType = 0x21 (Host-to-Device, Class, Interface)
     *   bRequest = 0x01 (SET_CUR)
     *   wValue = (CS_SAM_FREQ_CONTROL << 8) | 0 = 0x0100
     *   wIndex = (clockSourceEntityId << 8) | audioControlInterfaceNumber
     *   data = 4-byte LE sample rate
     */
    fun setSampleRate(sampleRateHz: Int): Boolean {
        val conn = connection ?: return false

        val data = ByteArray(4)
        data[0] = (sampleRateHz and 0xFF).toByte()
        data[1] = ((sampleRateHz shr 8) and 0xFF).toByte()
        data[2] = ((sampleRateHz shr 16) and 0xFF).toByte()
        data[3] = ((sampleRateHz shr 24) and 0xFF).toByte()

        // Use auto-detected clock source ID from USB descriptors.
        // If not available, fall back to brute-force trying common IDs.
        val detectedId = cachedDeviceInfo?.clockSourceId ?: -1
        val clockSourceIds = if (detectedId > 0) {
            intArrayOf(detectedId)  // use the one we parsed from descriptors
        } else {
            intArrayOf(0x05, 0x09, 0x0A, 0x0B, 0x0C, 0x0D,
                    0x28, 0x29, 0x2A, 0x06, 0x07, 0x08,
                    0x10, 0x11, 0x12, 0x20, 0x21, 0x22)
        }

        // N1 fix: Use the auto-detected AudioControl interface number for wIndex lower
        // byte, instead of hardcoded 0. UAC2 spec requires wIndex = (entityId << 8) |
        // audioControlInterfaceNumber. Some DACs use a non-zero AudioControl interface
        // number — hardcoding 0 causes SET_CUR to fail silently on those DACs.
        // Falls back to 0 when the interface number is unknown (e.g., device opened
        // before parseFeatureUnitId ran, or parseFeatureUnitId returned -1).
        val ifaceId = cachedDeviceInfo?.audioControlInterfaceId?.let { if (it >= 0) it else 0 } ?: 0

        for (csId in clockSourceIds) {
            val wIndex = (csId shl 8) or (ifaceId and 0xFF)  // entityId << 8 | audioControlIface
            val ret = conn.controlTransfer(
                    0x21,    // bmRequestType: Host-to-Device, Class, Interface
                    0x01,    // bRequest: SET_CUR
                    0x0100,  // wValue: CS_SAM_FREQ_CONTROL
                    wIndex,
                    data,
                    data.size,
                    1000     // timeout ms
            )
            if (ret >= 0) {
                Timber.tag(TAG).i("setSampleRate($sampleRateHz Hz): SUCCESS with clockSourceId=0x${csId.toString(16)} iface=$ifaceId (wIndex=0x${wIndex.toString(16)}, ret=$ret)")
                return true
            }
        }

        Timber.tag(TAG).w("setSampleRate($sampleRateHz Hz): all clock source IDs failed, DAC may auto-detect")
        return false
    }

    /**
     * Read the current sample rate from the DAC via UAC2 GET_CUR.
     * This verifies whether our SET_CUR actually took effect.
     */
    fun readSampleRate(): Int {
        val conn = connection ?: return -1
        val data = ByteArray(4)

        val detectedId = cachedDeviceInfo?.clockSourceId ?: -1
        val clockSourceIds = if (detectedId > 0) intArrayOf(detectedId)
                else intArrayOf(0x05, 0x09, 0x0A, 0x0B, 0x0C, 0x28, 0x29)
        // N1 fix: Use auto-detected AudioControl interface number (see setSampleRate).
        val ifaceId = cachedDeviceInfo?.audioControlInterfaceId?.let { if (it >= 0) it else 0 } ?: 0
        for (csId in clockSourceIds) {
            val wIndex = (csId shl 8) or (ifaceId and 0xFF)
            val ret = conn.controlTransfer(
                    0xA1,    // bmRequestType: Device-to-Host, Class, Interface
                    0x01,    // bRequest: GET_CUR (actually CUR is 0x01 for both)
                    0x0100,  // wValue: CS_SAM_FREQ_CONTROL
                    wIndex,
                    data,
                    data.size,
                    1000
            )
            if (ret >= 4) {
                val rate = (data[0].toInt() and 0xFF) or
                        ((data[1].toInt() and 0xFF) shl 8) or
                        ((data[2].toInt() and 0xFF) shl 16) or
                        ((data[3].toInt() and 0xFF) shl 24)
                Timber.tag(TAG).i("readSampleRate: GET_CUR clockSourceId=0x${csId.toString(16)} " +
                        "returned $rate Hz (raw=${data.joinToString(",") { "0x${(it.toInt() and 0xFF).toString(16)}" }})")
                return rate
            }
        }
        Timber.tag(TAG).w("readSampleRate: all GET_CUR attempts failed")
        return -1
    }

    /**
     * Read the CLOCK_VALID control from the DAC via UAC2 GET_CUR.
     * This checks whether the Clock Source entity's clock is locked and stable
     * after a sample rate change. Standard practice per UAC2 spec: verify clock after SET_CUR before proceeding.
     *
     * UAC2 spec: Clock Source descriptor, CS = 0x02 (CUR_CLOCK_VALID_CONTROL)
     * Returns: true if clock is valid, false if not or on error.
     */
    fun readClockValid(): Boolean {
        val conn = connection ?: return false
        val data = ByteArray(1)

        val detectedId = cachedDeviceInfo?.clockSourceId ?: -1
        val clockSourceIds = if (detectedId > 0) intArrayOf(detectedId)
                else intArrayOf(0x05, 0x09, 0x0A, 0x0B, 0x0C, 0x28, 0x29)
        // N1 fix: Use auto-detected AudioControl interface number (see setSampleRate).
        val ifaceId = cachedDeviceInfo?.audioControlInterfaceId?.let { if (it >= 0) it else 0 } ?: 0
        for (csId in clockSourceIds) {
            val wIndex = (csId shl 8) or (ifaceId and 0xFF)
            val ret = conn.controlTransfer(
                    0xA1,    // bmRequestType: Device-to-Host, Class, Interface
                    0x01,    // bRequest: GET_CUR
                    0x0200,  // wValue: CS=0x02 (CLOCK_VALID_CONTROL), CN=0x00
                    wIndex,
                    data,
                    data.size,
                    1000
            )
            if (ret >= 1) {
                val valid = data[0].toInt() and 0x01
                Timber.tag(TAG).i("readClockValid: clockSourceId=0x${csId.toString(16)} iface=$ifaceId valid=$valid")
                return valid == 1
            }
        }
        Timber.tag(TAG).w("readClockValid: all GET_CUR attempts failed")
        return false
    }

    /**
     * Set the alternate setting on the streaming interface via Java API.
     * This may properly allocate USB bandwidth, which the native ioctl might not.
     */
    fun setAltSetting(altSetting: Int): Boolean {
        val conn = connection ?: return false
        val device = currentDevice ?: return false

        // Find the UsbInterface with the matching alt setting
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == UsbConstants.USB_CLASS_AUDIO &&
                iface.interfaceSubclass == 2 &&
                iface.alternateSetting == altSetting) {
                val result = conn.setInterface(iface)
                Timber.tag(TAG).i("setAltSetting($altSetting) via Java API: $result " +
                        "(iface id=${iface.id}, endpoints=${iface.endpointCount})")
                return result
            }
        }

        Timber.tag(TAG).w("setAltSetting($altSetting): no matching UsbInterface found, " +
                "trying all AudioStreaming interfaces...")

        // Fallback: try any AudioStreaming interface with matching alt
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == UsbConstants.USB_CLASS_AUDIO &&
                iface.interfaceSubclass == 2) {
                Timber.tag(TAG).d("  interface $i: id=${iface.id} alt=${iface.alternateSetting} " +
                        "endpoints=${iface.endpointCount}")
            }
        }

        return false
    }

}
