/*
 * Copyright (c) 2026 Auxio Project
 * UsbDacConnectionMonitor.kt is part of Auxio.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
 
package org.oxycblt.auxio.playback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import androidx.core.content.ContextCompat
import com.decent.usbaudio.UsbAudioDevice
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber as L

/**
 * Process-wide monitor that tracks whether a USB Audio Class DAC is currently physically connected
 * to the device.
 *
 * Responsibilities:
 * - Hold a [StateFlow] that reflects "is a USB DAC currently plugged in" (independent of whether
 *   USB DAC bit-perfect mode is enabled in settings).
 * - Register a system [BroadcastReceiver] for [UsbManager.ACTION_USB_DEVICE_ATTACHED] and
 *   [UsbManager.ACTION_USB_DEVICE_DETACHED] to react to plug/unplug events in real time.
 * - On each event, re-scan [UsbManager.deviceList] via [UsbAudioDevice.findUsbAudioDevice] and
 *   update the [StateFlow] accordingly.
 *
 * Why a separate singleton:
 * - [PlaybackViewModel] needs to gray-out the toolbar toggle and settings preference when no DAC is
 *   connected, but it has no direct access to [UsbManager] broadcasts (the existing
 *   [UsbAudioPermissionHelper] only handles the case where the user explicitly enabled USB DAC mode
 *   and then plugged in a device).
 * - A Hilt [@Singleton] tied to the application process is the cheapest way to share a single
 *   receiver + StateFlow across all consumers (PlaybackViewModel, AudioPreferenceFragment) without
 *   leaking the receiver when the activity is recreated.
 *
 * Thread-safety:
 * - [usbDacConnected] is a [MutableStateFlow], which is thread-safe for atomic reads/writes.
 * - The receiver's [BroadcastReceiver.onReceive] runs on the main thread, so the StateFlow update
 *   also happens on the main thread — no dispatcher switching needed.
 * - [UsbAudioDevice.findUsbAudioDevice] performs a synchronous scan of the USB device list (a
 *   binder call to the system service), which is fast (<5ms) and safe to run on the main thread for
 *   an event-driven (not polling) path.
 *
 * Lifecycle:
 * - The singleton is created lazily on first injection (when [PlaybackViewModel] is first
 *   instantiated by MainActivity).
 * - The receiver is registered in the [init] block and never unregistered — it lives for the entire
 *   application process, which is the desired behavior (we want to know about DAC plug/unplug
 *   events regardless of which activity is in the foreground).
 */
@Singleton
class UsbDacConnectionMonitor
@Inject
constructor(@ApplicationContext private val context: Context) {
    private val _usbDacConnected = MutableStateFlow(false)
    /**
     * Whether a USB Audio Class DAC is currently physically connected to the device. Updated in
     * real time from system broadcasts. Drives the gray-out state of the toolbar toggle (in
     * [PlaybackPanelFragment]) and the audio settings preference (in [AudioPreferenceFragment]).
     */
    val usbDacConnected: StateFlow<Boolean> = _usbDacConnected

    private val usbAudioDevice = UsbAudioDevice.getInstance(context)

    private val receiver =
        object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                when (intent?.action) {
                    UsbManager.ACTION_USB_DEVICE_ATTACHED,
                    UsbManager.ACTION_USB_DEVICE_DETACHED -> refreshConnectionState()
                }
            }
        }

    init {
        // Initial state: check if a DAC is already plugged in before we registered the receiver
        // (e.g. app launched with DAC connected). Subsequent plug/unplug events will be handled
        // by the receiver.
        refreshConnectionState()

        // Register for system USB device attach/detach broadcasts. These are protected system
        // broadcasts (only the system can send them), so RECEIVER_NOT_EXPORTED is safe and
        // prevents other apps from spoofing events.
        val filter =
            IntentFilter().apply {
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            }
        ContextCompat.registerReceiver(
            context,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        L.d(
            "UsbDacConnectionMonitor initialized; initial state: connected=${_usbDacConnected.value}"
        )
    }

    /**
     * Re-scan [UsbManager.deviceList] for a USB audio device and update [usbDacConnected]. Called
     * on init and on every ATTACHED/DETACHED broadcast. Cheap (<5ms) and safe to call on the main
     * thread.
     */
    private fun refreshConnectionState() {
        val connected = usbAudioDevice.findUsbAudioDevice() != null
        if (connected != _usbDacConnected.value) {
            _usbDacConnected.value = connected
            L.d("USB DAC connection state changed: connected=$connected")
        }
    }
}
