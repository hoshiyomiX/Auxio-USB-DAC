package com.decent.usbaudio

import timber.log.Timber

/**
 * Native Ogg/Opus decode -> USB audio engine.
 *
 * Mirrors [NativeAudioEngine] (the FLAC engine) for Ogg/Opus files. Bypasses the entire ExoPlayer
 * audio pipeline for `.opus` and `.ogg` (with Opus payload) files. A single native C++ thread
 * handles: Ogg page parsing -> Opus decode -> bit-depth padding -> USB isochronous transfers. Zero
 * JNI in the hot path.
 *
 * Bit-perfect rationale: libopus natively outputs int16 PCM (Opus is a 16-bit codec). No float
 * conversion happens anywhere in this path. The only conversion is int16 -> int32 (left-shift by
 * 16, bit-exact) when the USB DAC alt setting is 32-bit -- same lossless padding the FLAC engine
 * uses for 16-bit FLAC files.
 *
 * Usage:
 * ```kotlin
 * val engine = NativeOpusEngine()
 * if (!engine.isAvailable) {
 *     // libopus.so failed to load -- fall back to FFmpeg pipeline
 *     return
 * }
 * engine.createFromFd(opusFd, usbStreamHandle)
 * engine.start()
 * // ... engine runs autonomously, reports position via getPositionUs()
 * engine.stop()
 * engine.destroy()
 * ```
 *
 * @see NativeAudioEngine for the FLAC equivalent
 * @see UsbAudioStream for USB stream lifecycle management
 */
class NativeOpusEngine : NativeEngine {

    private var handle: Long = 0L

    /**
     * True when libopus native library is available on this device. If false, [createFromFd] will
     * always return false -- caller should fall back to the ExoPlayer + FFmpeg pipeline (NOT
     * bit-perfect).
     */
    val isAvailable: Boolean = COMPANION_LOADED

    /** True when the engine has been created and not yet destroyed. */
    override val isCreated: Boolean
        get() = handle != 0L

    /** True when the decode thread is actively running. */
    override val isRunning: Boolean
        get() = handle != 0L && nativeIsRunning(handle)

    /**
     * Create the engine from a file descriptor pointing to an Ogg/Opus file.
     *
     * @param fd File descriptor for the `.opus` or `.ogg` (Opus payload) file (will be dup'd
     *   internally).
     * @param usbHandle Native handle from [UsbAudioStream] (the USB output context).
     * @return true if creation succeeded (OpusHead parsed, buffers allocated). false if libopus is
     *   unavailable, the file is not a valid Ogg/Opus stream, or buffer allocation failed.
     */
    override fun createFromFd(fd: Int, usbHandle: Long): Boolean {
        if (!isAvailable) {
            Timber.tag(TAG).w("createFromFd: libopus native library not available")
            return false
        }
        if (handle != 0L) {
            Timber.tag(TAG).w("Engine already created, destroying first")
            destroy()
        }
        handle =
            try {
                nativeCreateFromFd(fd, usbHandle)
            } catch (e: UnsatisfiedLinkError) {
                // Build was performed without libopus (setup.sh not run) -- the
                // .so loaded but the Opus JNI symbols are missing. Caller falls
                // back to the FFmpeg pipeline.
                Timber.tag(TAG).w("Opus JNI symbols unavailable (build without libopus?): ${e.message}")
                0L
            } catch (e: RuntimeException) {
                // F-3 fix: Catch broader RuntimeException (e.g. JNI env setup failure,
                // OutOfMemoryError in native buffer allocation) to prevent app crash.
                // Caller falls back to the FFmpeg pipeline, same as UnsatisfiedLinkError.
                Timber.tag(TAG).w("Opus engine creation failed (RuntimeException): ${e.message}")
                0L
            }
        if (handle == 0L) {
            Timber.tag(TAG).e("Failed to create native Opus engine")
            return false
        }
        Timber.tag(TAG).i("Created: ${getSampleRate()}Hz ${getBitsPerSample()}-bit ${getChannels()}ch")
        return true
    }

    /** Start the decode thread. Audio flows immediately to USB. */
    override fun start(): Boolean {
        if (handle == 0L) return false
        return nativeStart(handle)
    }

    /** Pause the decode loop (thread stays alive, USB pipeline drains). */
    override fun pause() {
        if (handle != 0L) nativePause(handle)
    }

    /** Resume the decode loop after pause. */
    override fun resume() {
        if (handle != 0L) nativeResume(handle)
    }

    /**
     * Seek to a position in the Opus stream.
     *
     * Uses linear scan from file start (O(N) in number of Ogg pages, ~100ms for a typical 4-minute
     * song). Not as fast as FLAC's seek table, but correct and sufficient for interactive seeking.
     *
     * @param positionUs Target position in microseconds.
     * @return true if seek was accepted (async -- actual seek happens in decode thread).
     */
    override fun seek(positionUs: Long): Boolean {
        if (handle == 0L) return false
        return nativeSeek(handle, positionUs)
    }

    /** Stop the decode thread (blocks until thread exits). Thread-safe. */
    @Synchronized
    override fun stop() {
        if (handle != 0L) nativeStop(handle)
    }

    /** Destroy the engine and free all native resources. Thread-safe / idempotent. */
    @Synchronized
    override fun destroy() {
        if (handle != 0L) {
            nativeDestroy(handle)
            handle = 0L
        }
    }

    /** Current playback position in microseconds (from decoded samples). */
    override fun getPositionUs(): Long = if (handle != 0L) nativeGetPositionUs(handle) else 0L

    /** Opus sample rate (always 48000 -- Opus is natively 48kHz). */
    override fun getSampleRate(): Int = if (handle != 0L) nativeGetSampleRate(handle) else 0

    /** Opus channel count (1 or 2). */
    override fun getChannels(): Int = if (handle != 0L) nativeGetChannels(handle) else 0

    /**
     * Opus bits per sample (always 16 -- the Opus codec is 16-bit only). When the USB DAC is
     * 32-bit, native code left-shifts int16 to int32 (lossless bit-exact padding, same as the FLAC
     * engine uses for 16-bit FLAC).
     */
    override fun getBitsPerSample(): Int = if (handle != 0L) nativeGetBitsPerSample(handle) else 0

    // ── JNI declarations ───────────────────────────────────────────

    private external fun nativeCreateFromFd(fd: Int, usbHandle: Long): Long

    private external fun nativeStart(handle: Long): Boolean

    private external fun nativePause(handle: Long)

    private external fun nativeResume(handle: Long)

    private external fun nativeSeek(handle: Long, positionUs: Long): Boolean

    private external fun nativeStop(handle: Long)

    private external fun nativeDestroy(handle: Long)

    private external fun nativeGetPositionUs(handle: Long): Long

    private external fun nativeGetSampleRate(handle: Long): Int

    private external fun nativeGetChannels(handle: Long): Int

    private external fun nativeGetBitsPerSample(handle: Long): Int

    private external fun nativeIsRunning(handle: Long): Boolean

    companion object {
        private const val TAG = "NativeOpusEngine"

        /**
         * True if the shared library (libdecent_usb_audio.so) was loaded.
         *
         * The .so is shared with NativeAudioEngine (the FLAC engine) and is already loaded by its
         * companion init in most cases. loadLibrary is idempotent so we call it again for safety.
         *
         * Note: this flag does NOT verify that the Opus JNI symbols are present (libopus might not
         * have been built in if setup.sh was not run before gradle build). The actual
         * symbol-availability check happens lazily in [createFromFd] via try/catch on
         * UnsatisfiedLinkError. If libopus was not built in, createFromFd returns false and the
         * caller falls back to the FFmpeg pipeline.
         */
        private val COMPANION_LOADED: Boolean =
            try {
                System.loadLibrary("decent_usb_audio")
                true
            } catch (e: UnsatisfiedLinkError) {
                Timber.tag(TAG).w("Native library unavailable: ${e.message}")
                false
            }
    }
}
