package com.decent.usbaudio

import android.hardware.usb.UsbDeviceConnection

/**
 * Information about an opened USB audio device, ready for native I/O.
 *
 * @property featureUnitId UAC2 Feature Unit entity ID (parsed from descriptors). Used for
 *   hardware volume control via SET_CUR on FU_VOLUME_CONTROL. -1 when no Feature Unit
 *   was found in the descriptors — in that case hardware volume control is unavailable
 *   and the app should fall back to software gain (which breaks bit-perfect output).
 * @property audioControlInterfaceId The bInterfaceNumber of the AudioControl interface.
 *   Used as the lower byte of wIndex in UAC2 SET_CUR/GET_CUR control transfers.
 *   Typically 0, but some DACs use a different interface number. -1 if not found.
 * @property volumeMin The minimum volume value in 1/256 dB (signed 16-bit), queried via
 *   UAC2 GET_MIN on the Feature Unit. Represents the DAC's maximum attenuation (most
 *   negative). 0x8001 (-127.99 dB) if the DAC supports the full range. If GET_MIN
 *   failed, defaults to -12800 (-50 dB) — practical lower bound for consumer setups.
 * @property volumeMax The maximum volume value in 1/256 dB (signed 16-bit), queried via
 *   UAC2 GET_MAX on the Feature Unit. Represents the DAC's maximum output (usually
 *   0 dB = 0x0000 = unity gain). If GET_MAX failed, defaults to 0 (0 dB).
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
    val bestBitDepth: Int,
    val audioControlInterfaceId: Int = 0,
    val volumeMin: Short = DEFAULT_VOLUME_MIN,
    val volumeMax: Short = DEFAULT_VOLUME_MAX,
) {
    companion object {
        /**
         * Default min volume if GET_MIN fails: -50 dB in 1/256 dB = -12800.
         *
         * Previously -60 dB (-15360), but in practice anything below -50 dB is inaudible
         * on consumer headphones / IEMs with typical USB DACs. Using -60 dB made the bottom
         * ~30% of the slider effectively silent, which combined with linear-dB mapping
         * produced the "below half = off, sudden peak at half" perception. -50 dB keeps
         * the bottom of the slider in the audible range.
         */
        const val DEFAULT_VOLUME_MIN: Short = -12800

        /** Default max volume if GET_MAX fails: 0 dB in 1/256 dB = 0. */
        const val DEFAULT_VOLUME_MAX: Short = 0

        /**
         * Hard ceiling for volume output, regardless of what the DAC reports as volumeMax.
         *
         * = -496 in 1/256 dB units = -1.94 dB attenuation = 0.8 linear gain.
         *
         * Rationale: 0 dB FS (full-scale) on a USB DAC with sensitive IEMs/headphones can
         * produce 110+ dB SPL — well above the WHO safe-listening threshold (85 dB for
         * prolonged exposure, 100 dB for short bursts). Without a ceiling, slider 100%
         * sends raw 0 dB to the DAC, which can damage hearing and speakers.
         *
         * This constant caps the effective volumeMax so slider 100% never exceeds -1.94 dB.
         * The ceiling is applied via minOf(volumeMax, CEILING_VOLUME_MAX) in setUsbVolume
         * and getUsbVolume — both directions stay consistent for accurate round-trip.
         *
         * Bit-perfect preservation: This clamp affects only the UAC2 SET_CUR control
         * value (hardware analog gain stage in the DAC). The PCM stream sent via USB
         * isochronous transfers is NEVER modified — bit-perfect output is 100% preserved.
         *
         * Edge cases:
         * - DAC with volumeMax < ceiling (e.g., -3 dB): minOf picks volumeMax, ceiling
         *   is not forced upward — the DAC's natural max is respected.
         * - DAC with volumeMax > ceiling (e.g., +6 dB gain stage): minOf picks ceiling,
         *   hard-capping output at -1.94 dB even if the DAC could go louder.
         * - DAC degenerate (volumeMin == volumeMax == 0): SET_CUR is sent with -496,
         *   DAC may ignore (no-op) — no regression vs prior behavior.
         */
        const val CEILING_VOLUME_MAX: Short = -496
    }
}
