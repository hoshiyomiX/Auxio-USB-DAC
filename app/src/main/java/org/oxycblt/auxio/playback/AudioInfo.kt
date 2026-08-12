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
import org.oxycblt.musikr.Song
import org.oxycblt.musikr.fs.Format

/**
 * UI-facing representation of the current audio pipeline state, suitable for display in the
 * album-art overlay.
 *
 * This is a thin wrapper around [AudioInfoSnapshot] that adds an [usbDacModeActive] flag (so the UI
 * can show "USB DAC mode is off" instead of empty fields when the feature is disabled in settings)
 * and provides sensible defaults for null fields (displayed as "—" instead of empty strings).
 *
 * Passthrough and bit-perfect fields are normalized to a single glyph ([CHECK] or [CROSS]) so the
 * overlay can render them as a status icon rather than a verbose textual description.
 *
 * Use [from] to create an instance from the latest snapshot + setting state.
 */
data class AudioInfo(
    /**
     * Product name of the connected USB DAC (e.g. "Topping DX3 Pro+"). "—" when no DAC is open
     * (either USB DAC mode is off, or the mode is on but no device is plugged in).
     */
    val usbAudioDeviceName: String,
    /** Human-readable description of the active decoder (e.g. "Native FLAC (libFLAC)"). */
    val decoderInfo: String,
    /** Container/codec format of the source file (e.g. "FLAC", "OGG/Opus"). */
    val musicFormat: String,
    /**
     * Which engine is producing samples (e.g. "Native FLAC", "FFmpeg → int",
     * "Android (AudioFlinger)" when playback falls back to the default sink).
     */
    val engineUsed: String,
    /** Whether bit-perfect passthrough is active. Rendered as [CHECK] or [CROSS]. */
    val passthroughStatus: String,
    /** Whether the pipeline is currently bit-perfect. Rendered as [CHECK] or [CROSS]. */
    val bitPerfectInfo: String,
    /** Whether resampling is being applied. */
    val resamplerStatus: String,
    /** Output channel count label (e.g. "Stereo (2)", "Mono (1)"). */
    val outputChannel: String,
    /** Bitrate of the source file (e.g. "1411 kbps (PCM)", "—"). */
    val audioBitInfo: String,
    /**
     * Effective output sample rate + bit depth (e.g. "96 kHz / 24-bit"). When playing through the
     * default Android sink, only the source sample rate is shown (bit depth is unknown).
     */
    val samplingInfo: String,
    /**
     * Whether USB DAC mode is enabled in settings. When false, all fields above reflect the state
     * of ExoPlayer's default audio sink (i.e., Android's AudioFlinger).
     */
    val usbDacModeActive: Boolean,
) {
    companion object {
        private const val DASH = "—"
        /** Glyph for an active/positive status (Passthrough, Bit-perfect). */
        private const val CHECK = "\u2713"
        /** Glyph for an inactive/negative status (Passthrough, Bit-perfect). */
        private const val CROSS = "\u2717"

        /**
         * Create an [AudioInfo] from the latest [AudioInfoSnapshot], the current USB DAC mode
         * setting, and the currently playing [Song].
         *
         * The [Song] is used as a fallback source for fields that the sink snapshot cannot populate
         * when USB DAC mode is off, when no DAC is plugged in, or when the sink has not yet
         * received a track transition. This prevents the overlay from showing mostly "—" for
         * decoder/format/sampling/bitrate whenever the USB DAC pipeline is inactive, which is
         * the common case for users testing the overlay without a DAC attached.
         *
         * Passthrough and bit-perfect are normalized to a single glyph ([CHECK] / [CROSS]) here so
         * the overlay can render them as status icons. The conversion rules:
         *  - Passthrough: [CHECK] only when the snapshot reports true bit-perfect passthrough
         *    ("Passthrough"); [CROSS] for any decoded/mixed/off path.
         *  - Bit-perfect: [CHECK] for true bit-perfect ("Bit-perfect") and lossless PCM
         *    ("PCM (lossless)"); [CROSS] for any converted/off path.
         *
         * Note: The overlay no longer has a "Resolution" field — the source sample rate is
         * already shown by the Sampling field, and the resampling state is shown by the
         * Resampler field. Resolution was redundant.
         *
         * @param snapshot The latest snapshot from [com.decent.usbaudio.media3.UsbAudioSink], or
         *   null if the USB DAC sink is not active (e.g., the setting is off or no sink has been
         *   created yet).
         * @param usbDacModeActive Whether USB DAC mode is enabled in settings.
         * @param song The currently playing [Song], or null if no track is loaded. Used to populate
         *   display fields when the sink snapshot lacks the information.
         * @return An [AudioInfo] instance with all fields populated (never null). When [snapshot]
         *   is null and [song] is null, all fields are "—".
         */
        fun from(
            snapshot: AudioInfoSnapshot?,
            usbDacModeActive: Boolean,
            song: Song? = null,
        ): AudioInfo {
            if (snapshot == null) {
                // No sink bound — USB DAC mode is off, or no playback session is active, or the
                // USB DAC toggle is on but no DAC is plugged in (audio falls back to Android's
                // default AudioFlinger sink). Fall back to Song metadata when available so the
                // overlay is still useful, and report the engine/output as the Android default.
                return AudioInfo(
                    usbAudioDeviceName = DASH,
                    decoderInfo = song?.let { deriveDecoderInfo(it, usbDacModeActive) } ?: DASH,
                    musicFormat = song?.let { formatSongFormat(it.format) } ?: DASH,
                    engineUsed = "Android (AudioFlinger)",
                    passthroughStatus = CROSS,
                    bitPerfectInfo = CROSS,
                    resamplerStatus =
                        if (usbDacModeActive) "Not applicable" else "Android resampler",
                    outputChannel = "Stereo (2)",
                    audioBitInfo = song?.let { formatSongBitrate(it) } ?: DASH,
                    samplingInfo = song?.let { formatSongSampling(it) } ?: DASH,
                    usbDacModeActive = usbDacModeActive,
                )
            }
            return AudioInfo(
                usbAudioDeviceName = snapshot.usbAudioDeviceName ?: DASH,
                decoderInfo =
                    snapshot.decoderInfo
                        ?: song?.let { deriveDecoderInfo(it, usbDacModeActive) }
                        ?: DASH,
                musicFormat =
                    snapshot.musicFormat ?: song?.let { formatSongFormat(it.format) } ?: DASH,
                engineUsed =
                    if (snapshot.engineUsed == "None") DASH else snapshot.engineUsed,
                passthroughStatus =
                    if (snapshot.passthroughStatus == "Passthrough") CHECK else CROSS,
                bitPerfectInfo =
                    when (snapshot.bitPerfectInfo) {
                        "Bit-perfect", "PCM (lossless)" -> CHECK
                        else -> CROSS
                    },
                resamplerStatus = snapshot.resamplerStatus,
                outputChannel = formatChannelCount(snapshot.outputChannelCount),
                audioBitInfo =
                    if (snapshot.audioBitInfo != "—" && snapshot.audioBitInfo.isNotBlank())
                        snapshot.audioBitInfo
                    else song?.let { formatSongBitrate(it) } ?: DASH,
                samplingInfo =
                    snapshot.samplingInfo ?: song?.let { formatSongSampling(it) } ?: DASH,
                usbDacModeActive = usbDacModeActive,
            )
        }

        /**
         * Derive a human-readable decoder description from the [Song]'s [Format] when the sink has
         * not provided one. The result reflects what the Android/ExoPlayer pipeline would use when
         * USB DAC mode is off (MediaCodec for compressed formats, AudioTrack for PCM).
         */
        private fun deriveDecoderInfo(song: Song, usbDacModeActive: Boolean): String {
            val codecName = formatSongFormat(song.format)
            return if (usbDacModeActive) "Pending ($codecName)"
            else "ExoPlayer MediaCodec ($codecName)"
        }

        /** Map a [Song]'s [Format] sealed type to a short display string for the overlay. */
        private fun formatSongFormat(format: Format): String =
            when (format) {
                is Format.MPEG3 -> "MP3"
                is Format.MPEG4 -> format.containing?.let { "MP4/${formatSongFormat(it)}" } ?: "MP4"
                is Format.AAC -> "AAC"
                is Format.ALAC -> "ALAC"
                is Format.Ogg -> format.containing?.let { "OGG/${formatSongFormat(it)}" } ?: "OGG"
                is Format.Opus -> "OGG/Opus"
                is Format.Vorbis -> "OGG/Vorbis"
                is Format.FLAC -> "FLAC"
                is Format.Wav -> "WAV"
                is Format.Unknown -> format.extension ?: "Unknown"
            }

        /**
         * Format the [Song]'s effective sampling info for the default-speaker fallback path.
         *
         * Only the source sample rate is shown — Android's AudioFlinger output bit depth is
         * implementation-defined (typically 16-bit, sometimes 24-bit on HiFi Android), so reporting
         * a fixed value would be misleading. The previous "${rate} / ?-bit" form was confusing
         * because the "?-bit" placeholder looked like a bug rather than an honest unknown.
         */
        private fun formatSongSampling(song: Song): String =
            if (song.sampleRateHz > 0) formatRate(song.sampleRateHz) else DASH

        /** Format the [Song]'s source bitrate in kbps. */
        private fun formatSongBitrate(song: Song): String =
            if (song.bitrateKbps > 0) "${song.bitrateKbps} kbps" else DASH

        /** Format a sample rate in Hz as a human-readable kHz string. */
        private fun formatRate(hz: Int): String =
            if (hz >= 1000) {
                val khz = hz / 1000.0
                if (khz == khz.toInt().toDouble()) "${khz.toInt()} kHz" else "${khz} kHz"
            } else {
                "$hz Hz"
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
