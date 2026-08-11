/*
 * Copyright (c) 2026 Auxio Project
 * AudioInfo.kt is part of Auxio.
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

import com.decent.usbaudio.media3.AudioInfoSnapshot

/**
 * UI-facing representation of the current audio pipeline state, suitable for display in the
 * album-art overlay.
 *
 * This is a thin wrapper around [AudioInfoSnapshot] that adds an [usbDacModeActive] flag (so the UI
 * can show "USB DAC mode is off" instead of empty fields when the feature is disabled in settings)
 * and provides sensible defaults for null fields (displayed as "—" instead of empty strings).
 *
 * Use [from] to create an instance from the latest snapshot + setting state.
 */
data class AudioInfo(
    /** Human-readable description of the active decoder (e.g. "Native FLAC (libFLAC)"). */
    val decoderInfo: String,
    /** Container/codec format of the source file (e.g. "FLAC", "OGG/Opus"). */
    val musicFormat: String,
    /** Source bit depth + sample rate (e.g. "24-bit / 96 kHz"). */
    val musicResolution: String,
    /** Which engine is producing samples (e.g. "Native FLAC", "FFmpeg → int"). */
    val engineUsed: String,
    /** Whether resampling is being applied. */
    val resamplerStatus: String,
    /** Whether bit-perfect passthrough is active. */
    val passthroughStatus: String,
    /** Output channel count label (e.g. "Stereo (2)", "Mono (1)"). */
    val outputChannel: String,
    /** Effective output sample rate + bit depth (e.g. "96 kHz / 24-bit"). */
    val samplingInfo: String,
    /** Whether the pipeline is currently bit-perfect. */
    val bitPerfectInfo: String,
    /** Bitrate of the source file (e.g. "1411 kbps (PCM)", "—"). */
    val audioBitInfo: String,
    /**
     * Whether USB DAC mode is enabled in settings. When false, all fields above reflect the state
     * of ExoPlayer's default audio sink (i.e., Android's AudioFlinger).
     */
    val usbDacModeActive: Boolean,
) {
    companion object {
        private const val DASH = "—"

        /**
         * Create an [AudioInfo] from the latest [AudioInfoSnapshot] and the current USB DAC mode
         * setting.
         *
         * @param snapshot The latest snapshot from [com.decent.usbaudio.media3.UsbAudioSink], or
         *   null if the USB DAC sink is not active (e.g., the setting is off or no sink has been
         *   created yet).
         * @param usbDacModeActive Whether USB DAC mode is enabled in settings.
         * @return An [AudioInfo] instance with all fields populated (never null). When [snapshot]
         *   is null and [usbDacModeActive] is false, all fields are "—" and the overlay effectively
         *   shows "USB DAC mode is off".
         */
        fun from(snapshot: AudioInfoSnapshot?, usbDacModeActive: Boolean): AudioInfo {
            if (snapshot == null) {
                return AudioInfo(
                    decoderInfo = DASH,
                    musicFormat = DASH,
                    musicResolution = DASH,
                    engineUsed = if (usbDacModeActive) "None" else "Android (AudioFlinger)",
                    resamplerStatus =
                        if (usbDacModeActive) "Not applicable" else "Android resampler",
                    passthroughStatus = if (usbDacModeActive) "Off" else "PCM (mixed)",
                    outputChannel = DASH,
                    samplingInfo = DASH,
                    bitPerfectInfo = if (usbDacModeActive) "Off" else "Off (Android)",
                    audioBitInfo = DASH,
                    usbDacModeActive = usbDacModeActive,
                )
            }
            return AudioInfo(
                decoderInfo = snapshot.decoderInfo ?: DASH,
                musicFormat = snapshot.musicFormat ?: DASH,
                musicResolution = snapshot.musicResolution ?: DASH,
                engineUsed = snapshot.engineUsed,
                resamplerStatus = snapshot.resamplerStatus,
                passthroughStatus = snapshot.passthroughStatus,
                outputChannel = formatChannelCount(snapshot.outputChannelCount),
                samplingInfo = snapshot.samplingInfo ?: DASH,
                bitPerfectInfo = snapshot.bitPerfectInfo,
                audioBitInfo = snapshot.audioBitInfo,
                usbDacModeActive = usbDacModeActive,
            )
        }

        private fun formatChannelCount(count: Int): String =
            when (count) {
                0 -> DASH
                1 -> "Mono (1)"
                2 -> "Stereo (2)"
                else -> "${count}ch"
            }
    }
}
