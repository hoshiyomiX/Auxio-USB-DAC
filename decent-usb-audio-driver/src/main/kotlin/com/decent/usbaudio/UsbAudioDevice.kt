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
import android.util.Log
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
                    Log.i(TAG, "Found USB audio device: ${device.productName} " +
                            "(vendor=0x${device.vendorId.toString(16)}, " +
                            "product=0x${device.productId.toString(16)})")
                    return device
                }
            }
        }
        Log.d(TAG, "No USB audio device found")
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
            Log.i(TAG, "Permission already granted for ${device.productName}")
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
                    Log.i(TAG, "USB permission result: granted=$granted for ${device.productName}")
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
        Log.i(TAG, "Permission requested for ${device.productName}")
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
            Log.i(TAG, "Device already open, reusing fd=${cached.fd}")
            return cached
        }
        // Close any stale connection before opening new
        closeDevice()
        val conn = usbManager.openDevice(device)
        if (conn == null) {
            Log.e(TAG, "Failed to open device ${device.productName}")
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
                                Log.i(TAG, "Found ISO OUT endpoint: address=0x${ep.address.toString(16)}, " +
                                        "maxPacket=$maxPacketSize, interval=${ep.interval}")
                            }
                            // Isochronous IN endpoint (feedback)
                            ep.type == UsbConstants.USB_ENDPOINT_XFER_ISOC &&
                                    ep.direction == UsbConstants.USB_DIR_IN -> {
                                endpointFeedback = ep.address
                                Log.i(TAG, "Found ISO IN (feedback) endpoint: address=0x${ep.address.toString(16)}, " +
                                        "interval=${ep.interval}")
                            }
                        }
                    }
                }
            }
        }

        if (streamingInterface == null || endpointOut < 0) {
            Log.e(TAG, "No suitable AudioStreaming interface/endpoint found")
            conn.close()
            return null
        }

        // Claim the AudioControl interface (0) with force=true to disconnect kernel driver
        val controlInterface = (0 until device.interfaceCount)
                .map { device.getInterface(it) }
                .firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_AUDIO && it.interfaceSubclass == 1 }

        if (controlInterface != null) {
            val claimed = conn.claimInterface(controlInterface, true)
            Log.i(TAG, "Claimed AudioControl interface ${controlInterface.id} force=true: $claimed")
        }

        // Claim the AudioStreaming interface with force=true to disconnect kernel driver (snd-usb-audio)
        // NOTE: We claim the zero-bandwidth alt setting (alt=0). The actual streaming alt setting
        // will be activated later via setInterface() which allocates USB bandwidth.
        val claimed = conn.claimInterface(streamingInterface, true)
        Log.i(TAG, "Claimed AudioStreaming interface ${streamingInterface.id} force=true: $claimed " +
                "(alt=${streamingInterface.alternateSetting}, endpoints=${streamingInterface.endpointCount})")
        if (!claimed) {
            Log.e(TAG, "Failed to claim streaming interface — kernel driver may still be active")
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
            Log.i(TAG, "Reset streaming to alt=0 (zero-bandwidth)")
        }
        Thread.sleep(100)

        // Log all available alt settings for debugging
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == UsbConstants.USB_CLASS_AUDIO && iface.interfaceSubclass == 2) {
                Log.d(TAG, "  AudioStreaming alt=${iface.alternateSetting}: " +
                        "id=${iface.id}, endpoints=${iface.endpointCount}")
            }
        }

        val fd = conn.fileDescriptor
        val interfaceId = streamingInterface.id

        Log.i(TAG, "Device opened: ${device.productName}, fd=$fd, " +
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
        Log.i(TAG, "Auto-detected: clockSourceId=0x${clockSourceId.toString(16)}, " +
                "featureUnitId=0x${featureUnitId.toString(16)}, " +
                "audioControlIface=$audioControlIfaceId, " +
                "bestAlt=$bestAlt, bestBits=$bestBits")

        // Query the DAC's actual hardware volume range via GET_MIN/GET_MAX.
        // This prevents sending SET_CUR values outside the DAC's supported range,
        // which was the root cause of the "0-90% silence, 90-100% max" volume bug.
        val (volumeMin, volumeMax) = if (featureUnitId >= 0 && audioControlIfaceId >= 0) {
            queryVolumeRange(conn, featureUnitId, audioControlIfaceId)
        } else {
            Log.w(TAG, "Skipping volume range query — no Feature Unit or AudioControl interface")
            Pair(UsbAudioDeviceInfo.DEFAULT_VOLUME_MIN, UsbAudioDeviceInfo.DEFAULT_VOLUME_MAX)
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
                volumeMax = volumeMax
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

        Log.i(TAG, "Performing REAL USBDEVFS_RESET on fd=$fd...")

        // Real USB port reset via native ioctl — resets DAC clock state
        val ret = UsbAudioStream.nativeUsbReset(fd)
        Log.i(TAG, "USBDEVFS_RESET result: $ret")

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
                    Log.i(TAG, "parseClockSourceId: found CLOCK_SOURCE bClockID=0x${bClockID.toString(16)}")
                    return bClockID
                }
            }

            i += bLength
        }

        Log.w(TAG, "parseClockSourceId: no CLOCK_SOURCE descriptor found")
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
                    Log.i(TAG, "parseFeatureUnitId: AudioControl interface number = $bInterfaceNumber")
                }
            }

            // CS_INTERFACE descriptor (0x24) inside AudioControl
            if (inAudioControl && bDescriptorType == 0x24 && bLength >= 4) {
                val bDescriptorSubtype = raw[i + 2].toInt() and 0xFF
                // FEATURE_UNIT = 0x06
                if (bDescriptorSubtype == 0x06 && bLength >= 5) {
                    val bUnitID = raw[i + 3].toInt() and 0xFF
                    Log.i(TAG, "parseFeatureUnitId: found FEATURE_UNIT bUnitID=0x${bUnitID.toString(16)} " +
                            "in AudioControl iface=$audioControlIfaceNum")
                    return Pair(bUnitID, audioControlIfaceNum)
                }
            }

            i += bLength
        }

        Log.w(TAG, "parseFeatureUnitId: no FEATURE_UNIT descriptor found — hardware volume control unavailable")
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
    private fun queryVolumeRange(conn: UsbDeviceConnection, fuId: Int, ifaceId: Int): Pair<Short, Short> {
        val wValue = 0x0100  // CS=0x01 (VOLUME_CONTROL), CN=0x00 (master channel)
        val wIndex = (fuId shl 8) or (ifaceId and 0xFF)

        var minVal: Short = UsbAudioDeviceInfo.DEFAULT_VOLUME_MIN
        var maxVal: Short = UsbAudioDeviceInfo.DEFAULT_VOLUME_MAX

        // GET_MIN (bRequest = 0x82)
        val minData = ByteArray(2)
        val minRet = conn.controlTransfer(
            0xA1,    // bmRequestType: Device-to-Host, Class, Interface
            0x82,    // bRequest: GET_MIN
            wValue, wIndex, minData, minData.size, 500
        )
        if (minRet >= 2) {
            val raw = ((minData[1].toInt() and 0xFF) shl 8) or (minData[0].toInt() and 0xFF)
            val signed = raw.toShort()
            // Only use the queried value if it's strictly negative (attenuation). A min of 0
            // is nonsensical — it would mean "0 dB is the minimum", implying the DAC has no
            // attenuation capability at all. Some buggy DACs report min=0/max=0, which
            // previously caused an inverted range in setUsbVolume (minFixed > maxFixed after
            // the ceiling clamp), producing the "0% peak, 1-100% no sound" bug.
            if (signed < 0) {
                // Clamp to 0x8001 (-32767, -127.99 dB) — 0x8000 (-32768) is the UAC2 mute
                // value and must not be used as the minimum non-mute volume.
                minVal = if (signed <= -32768) -32767 else signed
                val db = minVal.toDouble() / 256.0
                Log.i(TAG, "queryVolumeRange: GET_MIN = $signed → clamped to $minVal " +
                        "(0x${(minVal.toInt() and 0xFFFF).toString(16)}) = ${"%.2f".format(db)} dB")
            } else {
                Log.w(TAG, "queryVolumeRange: GET_MIN returned non-negative value $signed — using default")
            }
        } else {
            Log.w(TAG, "queryVolumeRange: GET_MIN failed (ret=$minRet) — using default min = -50 dB")
        }

        // GET_MAX (bRequest = 0x83)
        val maxData = ByteArray(2)
        val maxRet = conn.controlTransfer(
            0xA1,    // bmRequestType: Device-to-Host, Class, Interface
            0x83,    // bRequest: GET_MAX
            wValue, wIndex, maxData, maxData.size, 500
        )
        if (maxRet >= 2) {
            val raw = ((maxData[1].toInt() and 0xFF) shl 8) or (maxData[0].toInt() and 0xFF)
            val signed = raw.toShort()
            // Max should be >= 0 (0 dB = unity, or positive for gain). Use the queried value.
            if (signed >= 0) {
                maxVal = signed
                val db = signed.toDouble() / 256.0
                Log.i(TAG, "queryVolumeRange: GET_MAX = $signed (0x${(raw and 0xFFFF).toString(16)}) = ${"%.2f".format(db)} dB")
            } else {
                Log.w(TAG, "queryVolumeRange: GET_MAX returned negative value $signed — using default")
            }
        } else {
            Log.w(TAG, "queryVolumeRange: GET_MAX failed (ret=$maxRet) — using default max = 0 dB")
        }

        // Degenerate-range detection: if minVal >= maxVal, the DAC reported a nonsensical
        // range (e.g., both 0, or min > max). This would invert the volume math in
        // setUsbVolume, producing the "0% peak, 1-100% no sound" bug. Reset to safe
        // defaults so the slider works correctly even on misbehaving DACs.
        if (minVal >= maxVal) {
            Log.w(TAG, "queryVolumeRange: degenerate range [$minVal, $maxVal] " +
                    "(min >= max) — resetting to defaults [-12800, 0] (-50 dB to 0 dB)")
            minVal = UsbAudioDeviceInfo.DEFAULT_VOLUME_MIN
            maxVal = UsbAudioDeviceInfo.DEFAULT_VOLUME_MAX
        }

        Log.i(TAG, "queryVolumeRange: final range = [${minVal}, ${maxVal}] " +
                "(${"%.2f".format(minVal.toDouble()/256.0)} dB to ${"%.2f".format(maxVal.toDouble()/256.0)} dB)")
        return Pair(minVal, maxVal)
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
            Log.w(TAG, "setUsbVolume: no Feature Unit ID available — hardware volume unsupported")
            return false
        }

        val clamped = volume.coerceIn(0f, 1f)
        val raw16: Short = if (clamped <= 0f) {
            // Mute: 0x8000 (-∞ dB)
            (-32768).toShort()
        } else {
            // Apply perceptual sqrt() warp so low slider positions produce audible output
            // (the linear-dB mapping alone made the bottom 30% of the slider inaudible).
            val warped = sqrt(clamped.toDouble())
            // Map warped 0..1 to the DAC's actual [volumeMin, volumeMax] range using a
            // linear-dB curve across the warped value:
            //   dbFixed = volumeMax + (volumeMin - volumeMax) * (1 - warped)
            //   warped = 1.0 → dbFixed = volumeMax (max, typically 0 dB)
            //   warped = 0.0 → dbFixed = volumeMin (would be mute, but we handle 0.0 above)
            //
            // R-1 fix: Clamp the effective minimum to DEFAULT_VOLUME_MIN (-50 dB) even if
            // the DAC reports a wider range (e.g., -127.99 dB for full UAC2 compliance).
            // Without this clamp, wide-range DACs push all audible volume into the upper
            // half of the slider — the original "0%-middle off, sudden peak at middle"
            // bug. -50 dB is the practical inaudibility threshold for consumer
            // headphones/IEMs; anything below it wastes slider real estate.
            //
            // Note: DEFAULT_VOLUME_MIN is declared as Short (UAC2 volume is signed 16-bit),
            // but minFixed is Int (for arithmetic with maxFixed). Convert both operands to
            // Int before maxOf to avoid Kotlin's "no overload for maxOf(Int, Short)" error.
            val minFixed = maxOf(
                (info?.volumeMin ?: UsbAudioDeviceInfo.DEFAULT_VOLUME_MIN).toInt(),
                UsbAudioDeviceInfo.DEFAULT_VOLUME_MIN.toInt()
            )
            // R-2 ceiling fix: Hard-cap effective volumeMax at CEILING_VOLUME_MAX (-1.94 dB
            // = 0.8 linear gain) so slider 100% never sends raw 0 dB to the DAC. This
            // protects hearing/speakers on sensitive IEMs/headphones where 0 dB FS can
            // produce 110+ dB SPL. minOf picks the lower of (DAC's actual volumeMax,
            // ceiling) — if DAC max is already below ceiling (e.g., -3 dB), respect it;
            // if DAC supports >ceiling (e.g., +6 dB gain stage), hard-cap at ceiling.
            //
            // Bit-perfect preserved: SET_CUR controls DAC's analog gain stage, NOT the
            // PCM stream. PCM data sent via USB isochronous transfers is untouched.
            val maxFixed = minOf(
                (info?.volumeMax ?: UsbAudioDeviceInfo.DEFAULT_VOLUME_MAX).toInt(),
                UsbAudioDeviceInfo.CEILING_VOLUME_MAX.toInt()
            )
            // Safety guard: if minFixed >= maxFixed (can happen if cachedDeviceInfo has a
            // degenerate range that wasn't caught by queryVolumeRange — e.g., the device
            // was opened before the fix, or the cached info is stale), reset to safe
            // defaults. Without this guard, the ratio math below produces inverted volume
            // (low slider → high dB, high slider → low dB) and the coerceIn at the end
            // either no-ops or pushes everything to maxFixed.
            val safeMin: Int
            val safeMax: Int
            if (minFixed < maxFixed) {
                safeMin = minFixed
                safeMax = maxFixed
            } else {
                Log.w(TAG, "setUsbVolume: degenerate range [$minFixed, $maxFixed] " +
                        "(min >= max) — using defaults [-12800, -496]")
                safeMin = UsbAudioDeviceInfo.DEFAULT_VOLUME_MIN.toInt()
                safeMax = UsbAudioDeviceInfo.CEILING_VOLUME_MAX.toInt()
            }
            val ratio = (1.0 - warped)  // 0.0 at max, 1.0 at min
            val dbFixed = safeMax + ((safeMin - safeMax) * ratio).toInt()
            // Clamp to the effective range to be safe
            val clampedFixed = dbFixed.coerceIn(safeMin, safeMax)
            clampedFixed.toShort()
        }

        // UAC2 SET_CUR for Feature Unit volume control:
        //   bmRequestType = 0x21 (Host-to-Device, Class, Interface)
        //   bRequest      = 0x01 (SET_CUR)
        //   wValue        = (CS << 8) | CN = 0x0100 (FU_VOLUME_CONTROL=0x01, channel 0=master)
        //   wIndex        = (entityId << 8) | interfaceNumber
        //   data          = 2-byte LE signed 16-bit volume
        val data = ByteArray(2)
        data[0] = (raw16.toInt() and 0xFF).toByte()
        data[1] = ((raw16.toInt() shr 8) and 0xFF).toByte()

        val wValue = 0x0100  // CS=0x01 (VOLUME_CONTROL), CN=0x00 (master channel)
        // Use the actual AudioControl interface number (was hardcoded to 0, which caused
        // SET_CUR to fail on DACs where the AudioControl interface is not 0).
        val ifaceId = info?.audioControlInterfaceId ?: 0
        val wIndex = (fuId shl 8) or (ifaceId and 0xFF)

        val dbValue = raw16.toDouble() / 256.0
        Log.d(TAG, "setUsbVolume: linear=$clamped → dbFixed=$raw16 (${"%.2f".format(dbValue)}" +
                " dB), range=[${info?.volumeMin}, ${info?.volumeMax}], " +
                "fuId=0x${fuId.toString(16)}, iface=$ifaceId")

        val ret = conn.controlTransfer(
                0x21,    // bmRequestType: Host-to-Device, Class, Interface
                0x01,    // bRequest: SET_CUR
                wValue,
                wIndex,
                data,
                data.size,
                500      // timeout ms (shorter than sample rate — volume is non-critical)
        )
        if (ret >= 0) {
            Log.i(TAG, "setUsbVolume($volume → $clamped, raw=0x${(raw16.toInt() and 0xFFFF).toString(16)}): " +
                    "SUCCESS with featureUnitId=0x${fuId.toString(16)}, iface=$ifaceId")
            return true
        }
        Log.w(TAG, "setUsbVolume($volume): SET_CUR failed (ret=$ret) — DAC may not support hardware volume " +
                "or the value is outside its range")
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
            Log.w(TAG, "getUsbVolume: GET_CUR failed (ret=$ret)")
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
            Log.w(TAG, "getUsbVolume: degenerate range [$minFixed, $maxFixed] — using defaults")
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
                    Log.i(TAG, "parseBestAltSetting: alt=$currentAlt subslotSize=$bSubslotSize bitResolution=$bBitResolution")

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
        Log.i(TAG, "parseBestAltSetting: best alt=$bestAlt bits=$bestBits, all=$altSettings")
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
            Log.i(TAG, "findAltSettingForBitDepth($targetBitDepth): exact match alt=${exact.first}")
            return exact
        }

        // Next higher
        val higher = parsedAltSettings
                .filter { it.second > targetBitDepth }
                .minByOrNull { it.second }
        if (higher != null) {
            Log.i(TAG, "findAltSettingForBitDepth($targetBitDepth): next higher alt=${higher.first} bits=${higher.second}")
            return higher
        }

        // Fallback to best
        val best = parsedAltSettings.maxByOrNull { it.second } ?: Pair(1, 16)
        Log.i(TAG, "findAltSettingForBitDepth($targetBitDepth): fallback to best alt=${best.first} bits=${best.second}")
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
        Log.i(TAG, "USB device closed")
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
                Log.i(TAG, "setSampleRate($sampleRateHz Hz): SUCCESS with clockSourceId=0x${csId.toString(16)} iface=$ifaceId (wIndex=0x${wIndex.toString(16)}, ret=$ret)")
                return true
            }
        }

        Log.w(TAG, "setSampleRate($sampleRateHz Hz): all clock source IDs failed, DAC may auto-detect")
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
                Log.i(TAG, "readSampleRate: GET_CUR clockSourceId=0x${csId.toString(16)} " +
                        "returned $rate Hz (raw=${data.joinToString(",") { "0x${(it.toInt() and 0xFF).toString(16)}" }})")
                return rate
            }
        }
        Log.w(TAG, "readSampleRate: all GET_CUR attempts failed")
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
                Log.i(TAG, "readClockValid: clockSourceId=0x${csId.toString(16)} iface=$ifaceId valid=$valid")
                return valid == 1
            }
        }
        Log.w(TAG, "readClockValid: all GET_CUR attempts failed")
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
                Log.i(TAG, "setAltSetting($altSetting) via Java API: $result " +
                        "(iface id=${iface.id}, endpoints=${iface.endpointCount})")
                return result
            }
        }

        Log.w(TAG, "setAltSetting($altSetting): no matching UsbInterface found, " +
                "trying all AudioStreaming interfaces...")

        // Fallback: try any AudioStreaming interface with matching alt
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == UsbConstants.USB_CLASS_AUDIO &&
                iface.interfaceSubclass == 2) {
                Log.d(TAG, "  interface $i: id=${iface.id} alt=${iface.alternateSetting} " +
                        "endpoints=${iface.endpointCount}")
            }
        }

        return false
    }

}
