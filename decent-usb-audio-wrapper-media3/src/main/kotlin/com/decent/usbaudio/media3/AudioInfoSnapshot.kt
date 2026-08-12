package com.decent.usbaudio.media3

/**
 * Immutable snapshot of the current audio pipeline state in [UsbAudioSink].
 *
 * Designed to be safely read from any thread (the UI layer polls this at a fixed
 * interval). All fields are plain JVM types so the DTO can cross module boundaries
 * without dragging in Android or media3 dependencies.
 *
 * @property usbAudioDeviceName Product name of the connected USB audio DAC
 *           (e.g. "Topping DX3 Pro+", "iFi Uno"). Null when no USB DAC is open.
 * @property decoderInfo Human-readable description of the active decoder
 *           (e.g. "Native FLAC (libFLAC)", "Native Opus (libopus)",
 *           "FFmpeg (float → int)", "ExoPlayer (MediaCodec)").
 *           Null when no track has been configured yet.
 * @property musicFormat Container/codec format of the source file
 *           (e.g. "FLAC", "OGG/Opus", "MP3", "AAC", "WAV"). Null if unknown.
 * @property engineUsed Which engine is currently producing samples
 *           (e.g. "Native FLAC", "Native Opus", "FFmpeg → int",
 *           "ExoPlayer MediaCodec", "None"). Never null.
 * @property resamplerStatus Whether resampling is being applied
 *           (e.g. "Native (no resampling)", "Resampling 44.1 → 48 kHz",
 *           "Not applicable"). Never null.
 * @property passthroughStatus Whether bit-perfect passthrough is active
 *           (e.g. "Passthrough", "PCM (decoded)", "Not applicable"). Never null.
 * @property outputChannelCount Number of channels currently sent to the DAC
 *           (1 = mono, 2 = stereo, 6 = 5.1, etc.). 0 when not configured.
 * @property samplingInfo Effective output sample rate + encoding
 *           (e.g. "96 kHz / 24-bit", "48 kHz / 16-bit"). Null when not configured.
 * @property bitPerfectInfo Whether the pipeline is currently bit-perfect
 *           (e.g. "Bit-perfect", "Converted", "Off"). Never null.
 * @property audioBitInfo Bitrate of the source file
 *           (e.g. "1411 kbps (PCM)", "320 kbps", "—"). Never null.
 */
data class AudioInfoSnapshot(
    val usbAudioDeviceName: String?,
    val decoderInfo: String?,
    val musicFormat: String?,
    val engineUsed: String,
    val resamplerStatus: String,
    val passthroughStatus: String,
    val outputChannelCount: Int,
    val samplingInfo: String?,
    val bitPerfectInfo: String,
    val audioBitInfo: String,
)
