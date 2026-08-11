/*
 * Copyright (c) 2026 Auxio Project
 * UsbDacVolumeProvider.kt is part of Auxio.
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
 
package org.oxycblt.auxio.playback.service

import android.media.AudioManager
import androidx.media.VolumeProviderCompat
import timber.log.Timber as L

/**
 * A [VolumeProviderCompat] that routes system volume adjustments (hardware volume keys, the system
 * volume slider, Bluetooth headset buttons, etc.) to the USB DAC's hardware volume control via
 * [android.media.MediaPlayer.setVolume] → [com.decent.usbaudio.media3.UsbAudioSink.setVolume] →
 * [com.decent.usbaudio.UsbAudioDevice.setUsbVolume] → UAC2 SET_CUR on the Feature Unit.
 *
 * Registered on the [android.support.v4.media.session.MediaSessionCompat] via
 * `setPlaybackToRemote(volumeProvider)` only when USB DAC bit-perfect mode is active. When USB DAC
 * mode is off, the MediaSession falls back to the default local playback (STREAM_MUSIC) routing, so
 * the system volume keys work normally for the speaker / headphones / Bluetooth.
 *
 * Why 100 steps:
 * - The system volume slider renders with discrete steps. 100 steps is the conventional max for
 *   `VolumeProviderCompat` (Android caps it at 100 anyway for the absolute volume control type).
 * - Each step maps to a linear float in [0.0, 1.0] = step / MAX_STEPS. The USB DAC's hardware
 *   volume control does the dB conversion (see [com.decent.usbaudio.UsbAudioDevice.setUsbVolume]).
 *
 * @param onVolumeChanged Called with the new linear volume (0..1) whenever the system adjusts the
 *   volume. The caller (MediaSessionHolder) is responsible for forwarding this to the ExoPlayer via
 *   `player.setVolume(float)`.
 * @param initialVolume The starting linear volume (0..1), typically the player's current volume or
 *   1.0 (max) if unknown.
 */
class UsbDacVolumeProvider(
    private val onVolumeChanged: (Float) -> Unit,
    initialVolume: Float = 1f,
) :
    VolumeProviderCompat(
        VOLUME_CONTROL_ABSOLUTE,
        MAX_STEPS,
        (initialVolume.coerceIn(0f, 1f) * MAX_STEPS).toInt().coerceIn(0, MAX_STEPS),
    ) {

    /**
     * System (or MediaSession) requested an incremental volume change. Translate the direction to a
     * new absolute step, update [currentVolume], and propagate the linear volume downstream.
     *
     * Mute/Unmute commands ([AudioManager.ADJUST_MUTE] / [AudioManager.ADJUST_UNMUTE]) are also
     * handled: mute maps to step 0, unmute restores the last non-zero step (or jumps to MAX_STEPS /
     * 2 = 50% if no previous step was set).
     */
    override fun onAdjustVolume(direction: Int) {
        val oldStep = currentVolume
        val newStep: Int =
            when (direction) {
                AudioManager.ADJUST_RAISE -> (oldStep + 1).coerceAtMost(MAX_STEPS)
                AudioManager.ADJUST_LOWER -> (oldStep - 1).coerceAtLeast(0)
                AudioManager.ADJUST_MUTE -> 0
                AudioManager.ADJUST_UNMUTE ->
                    if (lastNonZeroStep > 0) lastNonZeroStep else MAX_STEPS / 2
                AudioManager.ADJUST_TOGGLE_MUTE ->
                    if (oldStep > 0) 0 else (lastNonZeroStep.takeIf { it > 0 } ?: MAX_STEPS / 2)
                // Same as ADJUST_RAISE per AudioManager docs.
                AudioManager.ADJUST_SAME -> return
                else -> {
                    L.w("unknown adjust direction $direction — ignoring")
                    return
                }
            }

        if (newStep == oldStep) {
            L.d("adjust $direction → step unchanged at $oldStep")
            return
        }

        // Track the last non-zero step so unmute can restore a sensible level. Only update when
        // moving to a non-zero step — we don't want to lose the remembered level when muting.
        if (newStep > 0) {
            lastNonZeroStep = newStep
        }

        setCurrentVolume(newStep)
        val linear = newStep.toFloat() / MAX_STEPS
        L.d("adjust $direction → step $oldStep → $newStep (linear=$linear)")
        onVolumeChanged(linear)
    }

    /**
     * System (or MediaSession) requested an absolute volume. Clamp, update, propagate downstream.
     *
     * Note: [androidx.media.VolumeProviderCompat.onSetVolumeTo] takes only the volume parameter (no
     * flags). The flags variant `setVolumeTo(volume, flags)` is a final public method on the parent
     * class that internally calls this override.
     */
    override fun onSetVolumeTo(volume: Int) {
        val newStep = volume.coerceIn(0, MAX_STEPS)
        if (newStep > 0) lastNonZeroStep = newStep
        setCurrentVolume(newStep)
        val linear = newStep.toFloat() / MAX_STEPS
        L.d("setVolumeTo($volume) → step $newStep (linear=$linear)")
        onVolumeChanged(linear)
    }

    /**
     * Push an externally-driven volume change (e.g. from the in-app UI, if any) into the provider's
     * state without re-invoking [onVolumeChanged]. This keeps the system volume slider in sync with
     * app-side volume changes.
     */
    fun updateFromExternal(linear: Float) {
        val step = (linear.coerceIn(0f, 1f) * MAX_STEPS).toInt().coerceIn(0, MAX_STEPS)
        if (step > 0) lastNonZeroStep = step
        if (step != currentVolume) {
            setCurrentVolume(step)
        }
    }

    private var lastNonZeroStep: Int =
        (initialVolume.coerceIn(0f, 1f) * MAX_STEPS).toInt().coerceIn(1, MAX_STEPS)

    private companion object {
        /**
         * 100 discrete steps. Matches the conventional max for [VOLUME_CONTROL_ABSOLUTE] and gives
         * ~1% granularity per step, which feels smooth on the system slider.
         */
        private const val MAX_STEPS = 100
    }
}
