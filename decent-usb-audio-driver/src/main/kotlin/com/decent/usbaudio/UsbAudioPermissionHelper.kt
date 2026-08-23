package com.decent.usbaudio

import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log

/**
 * Helper for handling USB_DEVICE_ATTACHED intents and permissions.
 *
 * Usage in your Activity:
 * ```
 * override fun onCreate(savedInstanceState: Bundle?) {
 *     super.onCreate(savedInstanceState)
 *     UsbAudioPermissionHelper.handleIntent(this, intent)
 * }
 *
 * override fun onNewIntent(intent: Intent) {
 *     super.onNewIntent(intent)
 *     UsbAudioPermissionHelper.handleIntent(this, intent)
 * }
 * ```
 */
object UsbAudioPermissionHelper {

    private const val TAG = "UsbAudioPermission"

    /**
     * Handle a USB_DEVICE_ATTACHED intent. Claims the device immediately
     * to prevent the kernel snd-usb-audio driver from configuring it.
     *
     * F-2 fix: [openDevice] is dispatched to a background thread to avoid potential ANR
     * when called from the main thread (Activity's onCreate/onNewIntent). The USB
     * descriptor parsing + controlTransfer calls inside openDevice can block for up to
     * 500ms each (GET_MIN, GET_MAX, etc.), which on slow DACs can trigger ANR.
     *
     * @return The USB device if it was an audio device and was claimed, null otherwise.
     *         Note: when permission needs to be requested first, this returns null
     *         immediately and the device is opened asynchronously in the callback.
     */
    fun handleIntent(context: Context, intent: Intent): UsbDevice? {
        if (intent.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return null

        val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE) ?: return null
        Log.i(TAG, "USB_DEVICE_ATTACHED: ${device.productName}")

        val usbAudioDevice = UsbAudioDevice.getInstance(context)
        val audioDevice = usbAudioDevice.findUsbAudioDevice() ?: return null

        if (usbAudioDevice.hasPermission(audioDevice)) {
            // F-2: Dispatch openDevice to background thread — it does USB control transfers
            // (GET_MIN, GET_MAX, descriptor parsing) that can block for up to 500ms each
            Thread {
                val info = usbAudioDevice.openDevice(audioDevice)
                if (info != null) {
                    Log.i(TAG, "USB audio device claimed: ${info.deviceName}")
                } else {
                    Log.w(TAG, "USB audio device open failed (background thread)")
                }
            }.start()
            return audioDevice
        } else {
            usbAudioDevice.requestPermission(audioDevice) { granted ->
                if (granted) {
                    // F-2: Permission callback also runs openDevice on background thread
                    Thread {
                        val info = usbAudioDevice.openDevice(audioDevice)
                        Log.i(TAG, "Permission granted, device claimed: ${info?.deviceName}")
                    }.start()
                }
            }
        }
        return null
    }
}
