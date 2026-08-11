package com.decent.usbaudio

/**
 * Common contract for native decoders that drive the USB audio pipeline directly (bypassing
 * ExoPlayer's audio sink).
 *
 * Implemented by:
 * - [NativeAudioEngine] -- FLAC files (bit-perfect via libFLAC)
 * - [NativeOpusEngine] -- Ogg/Opus files (bit-perfect via libopus)
 *
 * UsbAudioSink holds a single `NativeEngine?` reference and dispatches to whichever concrete engine
 * was created for the current track. Adding a new format (e.g. MP3, Vorbis) means implementing this
 * interface -- no changes required in UsbAudioSink.
 */
interface NativeEngine {

    /** True when the engine has been created (createFromFd succeeded) and not yet destroyed. */
    val isCreated: Boolean

    /** True when the decode thread is actively running (between start() and stop()/pause()/EOF). */
    val isRunning: Boolean

    /**
     * Create the engine from a file descriptor.
     *
     * @param fd File descriptor for the media file (will be dup'd internally).
     * @param usbHandle Native handle from [UsbAudioStream] (the USB output context).
     * @return true if creation succeeded (metadata parsed, buffers allocated).
     */
    fun createFromFd(fd: Int, usbHandle: Long): Boolean

    /** Start the decode thread. Audio flows immediately to USB. */
    fun start(): Boolean

    /** Pause the decode loop (thread stays alive, USB pipeline drains). */
    fun pause()

    /** Resume the decode loop after pause. */
    fun resume()

    /**
     * Seek to a position in the stream.
     *
     * @param positionUs Target position in microseconds.
     * @return true if seek was accepted (async -- actual seek happens in decode thread).
     */
    fun seek(positionUs: Long): Boolean

    /** Stop the decode thread (blocks until thread exits). Thread-safe. */
    fun stop()

    /** Destroy the engine and free all native resources. Thread-safe / idempotent. */
    fun destroy()

    /** Current playback position in microseconds (from decoded samples). */
    fun getPositionUs(): Long

    /** Source sample rate (e.g., 96000 for FLAC, 48000 for Opus). */
    fun getSampleRate(): Int

    /** Source channel count (1 or 2). */
    fun getChannels(): Int

    /** Source bits per sample (16 for Opus; 16/24/32 for FLAC). */
    fun getBitsPerSample(): Int
}
