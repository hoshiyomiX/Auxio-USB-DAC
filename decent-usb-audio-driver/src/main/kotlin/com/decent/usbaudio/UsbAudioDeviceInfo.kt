package com.decent.usbaudio

import android.hardware.usb.UsbDeviceConnection

/**
 * Information about an opened USB audio device, ready for native I/O.
 *
 * @property featureUnitId UAC2 Feature Unit entity ID (parsed from descriptors). Used for
 *   hardware volume control via SET_CUR on FU_VOLUME_CONTROL. -1 when no Feature Unit
 *   was found in the descriptors — in that case hardware volume control is unavailable
 *   and the app should fall back to software gain (which breaks bit-perfect output).
 */
data class UsbAudioDeviceInfo(
    val connection: UsbDeviceConnection,
    val fd: Int,
    val deviceName: String,
    val interfaceId: Int,
    val endpointOutAddress: Int,
    val endpointFeedbackAddress: Int,
    val maxPacketSize: Int,
    val altSettingCount: Int,
    val clockSourceId: Int,
    val featureUnitId: Int,
    val bestAltSetting: Int,
    val bestBitDepth: Int
)
