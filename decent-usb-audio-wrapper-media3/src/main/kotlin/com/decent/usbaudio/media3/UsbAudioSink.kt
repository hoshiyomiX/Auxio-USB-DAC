package com.decent.usbaudio.media3

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import timber.log.Timber
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import com.decent.usbaudio.NativeAudioEngine
import com.decent.usbaudio.NativeOpusEngine
import com.decent.usbaudio.UsbAudioDevice
import com.decent.usbaudio.UsbAudioStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * ExoPlayer [androidx.media3.exoplayer.audio.AudioSink] that sends PCM directly
 * to a USB Audio Class 2.0 DAC via isochronous transfers, bypassing the entire
 * Android audio stack (AudioFlinger, AudioTrack, AAudio).
 *
 * The delegate [DefaultAudioSink] is kept alive (muted) for ExoPlayer's clock
 * and position tracking. Audio data is routed to the USB DAC via a dedicated
 * streaming thread with a producer-consumer queue, decoupling USB timing from
 * the delegate's AudioTrack timing.
 *
 * @param delegate  The [DefaultAudioSink] owned by the ExoPlayer renderer.
 * @param context   Application context for USB device detection and audio routing.
 * @param config    Configuration options (default: bit-perfect enabled, route to speaker).
 */
@OptIn(UnstableApi::class)
class UsbAudioSink(
    private val delegate: DefaultAudioSink,
    private val context: Context,
    private val config: UsbAudioSinkConfig = UsbAudioSinkConfig()
) : ForwardingAudioSink(delegate) {

    /**
     * Runtime-toggleable flag mirroring [UsbAudioSinkConfig.bitPerfectEnabled]. Initialized from
     * the config at construction, but can be flipped at runtime via [setBitPerfectEnabled] when
     * the user toggles USB DAC mode mid-session. All bit-perfect code paths read this field
     * instead of the immutable [config] value so that the change takes effect without an
     * ExoPlayer rebuild.
     */
    @Volatile
    var bitPerfectEnabled: Boolean = config.bitPerfectEnabled
        private set

    /**
     * Toggle bit-perfect mode at runtime, without rebuilding the ExoPlayer.
     *
     * When turning [enabled] off, synchronously tears down the USB stream (stops native
     * engine, drains URBs, unmutes the delegate AudioTrack) so audio immediately falls
     * back to the normal Android AudioFlinger path. When turning [enabled] on, simply
     * flips the flag — the next [configure] call (triggered by the caller via
     * `player.seekTo(currentPosition)`) will set up the USB stream.
     *
     * Thread-safe: synchronized on the sink to serialize with [releaseUsbStream] and
     * [snapshotAudioInfo].
     *
     * @param enabled True to enable USB DAC bit-perfect output, false to fall back to
     * the Android audio stack.
     */
    @Synchronized
    fun setBitPerfectEnabled(enabled: Boolean) {
        if (bitPerfectEnabled == enabled) {
            Timber.tag(TAG).i("setBitPerfectEnabled($enabled) — no change, skipping")
            return
        }
        bitPerfectEnabled = enabled
        Timber.tag(TAG).i("setBitPerfectEnabled($enabled) — applying runtime toggle")
        if (!enabled) {
            // Tear down USB stream immediately so the delegate AudioTrack unmutes and
            // audio resumes through the normal Android path on the next buffer.
            releaseUsbStream()
            clearForcedRouting()
            unmuteDelegateIfNeeded()
        }
        // When turning on, no action needed here — the caller forces a renderer
        // reconfigure (via player.seekTo(currentPosition)) which triggers configure()
        // to set up the USB stream afresh.
    }

    /** Receiver for [UsbManager.ACTION_USB_DEVICE_DETACHED].
     *  Releases the USB stream on a background thread so the main thread is not
     *  blocked by URB draining / native engine teardown. Declared before the
     *  init block so it is initialized when the block runs. */
    private val usbDetachedReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            if (intent?.action != UsbManager.ACTION_USB_DEVICE_DETACHED) return
            val device = intent.getParcelableExtra<android.hardware.usb.UsbDevice>(
                UsbManager.EXTRA_DEVICE
            )
            Timber.tag(TAG).i("USB_DEVICE_DETACHED: ${device?.productName} — releasing USB stream")
            // releaseUsbStream does blocking I/O (drainUrbs, nativeEngine.stop+join);
            // run on a worker thread to avoid ANR on the main thread.
            Thread { releaseUsbStream() }.start()
        }
    }

    init {
        // H1 fix: Listen for USB_DEVICE_DETACHED so we can release the USB stream
        // and unmute the delegate when the DAC is unplugged mid-playback. Without
        // this, the stream stays "alive-but-broken" and ExoPlayer keeps muting the
        // delegate, leaving the user with no audio at all after unplug.
        // RECEIVER_NOT_EXPORTED: only the system can broadcast this intent to us.
        ContextCompat.registerReceiver(
            context,
            usbDetachedReceiver,
            IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        Timber.tag(TAG).i("USB_DEVICE_DETACHED receiver registered")
    }

    /** Source file bit depth (16, 24, 32). Auto-detected from NativeAudioEngine. */
    private var trackBitDepth: Int = 0

    /** True when the native engine is running. Read by [NativeEngineAwareLoadControl]
     *  to stop ExoPlayer from loading data (prevents SD card I/O contention).
     *  Temporarily set to false during seek to allow one post-seek load. */
    @Volatile
    var isNativeEngineActive: Boolean = false
        private set


    /** File path of the current track. Set internally by [PlayerIntegrationListener]
     *  from the MediaItem URI. When non-null and pointing to a FLAC file, the native
     *  audio engine is used. For HTTP URIs, this is null (ExoPlayer pipeline fallback). */
    private var currentTrackPath: String? = null

    /** Clean up a finished native engine and apply deferred USB config.
     *  @return true if an engine was cleaned up (caller should restart playback). */
    private fun cleanupFinishedEngine(): Boolean {
        val engine = nativeEngine
        val opusEngine = nativeOpusEngine
        val flacFinished = engine != null && !engine.isRunning
        val opusFinished = opusEngine != null && !opusEngine.isRunning
        if (flacFinished || opusFinished) {
            if (flacFinished) {
                engine!!.destroy()
                nativeEngine = null
                Timber.tag(TAG).i("cleanupFinishedEngine: old FLAC engine cleared")
            }
            if (opusFinished) {
                opusEngine!!.destroy()
                nativeOpusEngine = null
                Timber.tag(TAG).i("cleanupFinishedEngine: old Opus engine cleared")
            }
            isNativeEngineActive = false
            activeEnginePath = null
            windowOffsetUs = -1L
            usbStartMediaTimeNeedsInit = true

            // Apply deferred USB reconfiguration (cross-rate transition)
            if (hasDeferredConfig) {
                Timber.tag(TAG).i("cleanupFinishedEngine: applying deferred config rate=$deferredRate")
                configureUsbBitPerfect(deferredRate, deferredChannels, deferredEncoding)
                hasDeferredConfig = false
            }
            return true
        }
        return false
    }

    /** Creates a native engine if the USB stream is ready and no engine exists.
     *  Replaces the streaming thread fallback if one was set up due to rate mismatch. */
    private fun createEngineIfNeeded() {
        if (nativeEngine?.isRunning == true || nativeOpusEngine?.isRunning == true) return  // already running
        val stream = usbAudioStream
        if (stream != null && stream.isAlive) {
            // Clean up dead engine if exists
            val old = nativeEngine
            if (old != null && !old.isRunning) {
                old.destroy()
                nativeEngine = null
                activeEnginePath = null
            }
            val oldOpus = nativeOpusEngine
            if (oldOpus != null && !oldOpus.isRunning) {
                oldOpus.destroy()
                nativeOpusEngine = null
                activeEnginePath = null
            }
            if (nativeEngine == null && nativeOpusEngine == null) {
                windowOffsetUs = -1L
                usbStartMediaTimeNeedsInit = true
                startNativeEngineIfSupported(stream)
                // Engine starts paused with engineNeedsInitialSeek = true.
                // Temporarily unblock LoadControl so ExoPlayer sends at least one
                // handleBuffer — needed to capture presentationTimeUs and seek.
                // Without this, the LoadControl blocks immediately and the engine
                // stays paused forever (HTTP→local transition race).
                if (nativeEngine != null || nativeOpusEngine != null) {
                    isNativeEngineActive = false
                }
                Timber.tag(TAG).i("createEngineIfNeeded: flacEngine=${nativeEngine != null} opusEngine=${nativeOpusEngine != null}")
            }
        }
    }

    private var usbAudioStream: UsbAudioStream? = null
    private val usbAudioDevice = UsbAudioDevice.getInstance(context)

    // True when the USB pipeline is ready to produce audio: USB stream is alive OR a native
    // engine (FLAC/Opus) is running. Polled by ExoPlaybackStateHolder after toggle ON to
    // determine when to resume playback — avoids resuming before the USB stream is ready.
    val isUsbPipelineReady: Boolean
        get() = (bitPerfectEnabled && usbAudioStream?.isAlive == true) || isNativeEngineActive
    /** Product name of the currently-opened USB DAC (e.g. "Topping DX3 Pro+"). Null when no device is open.
     *  Captured at [configureUsbStream] time from [UsbAudioDeviceInfo.deviceName] and surfaced via
     *  [snapshotAudioInfo] for the audio info overlay. */
    private var currentUsbDeviceName: String? = null
    private var usbStreamingThread: UsbStreamingThread? = null
    private var nativeEngine: NativeAudioEngine? = null
    /** Native Opus engine for `.opus` / `.ogg`-with-Opus files. Null when not active.
     *  Path C of the bit-perfect implementation: bypasses FFmpeg's float pipeline
     *  by using libopus directly (int16 PCM output, losslessly padded to DAC bit depth). */
    private var nativeOpusEngine: NativeOpusEngine? = null
    private val engineLock = Any()

    private var currentEncoding: Int = C.ENCODING_PCM_16BIT
    private var currentSampleRate: Int = 0
    private var currentChannelCount: Int = 0
    // P3 fix: pendingVolume and delegateMuted are accessed from multiple threads:
    //   - Main thread: ExoPlaybackStateHolder calls player.setVolume → UsbAudioSink.setVolume
    //   - Renderer thread: handleBuffer → muteDelegateIfNeeded / unmuteDelegateIfNeeded
    //   - MediaSession volume callback thread: UsbDacVolumeProvider.onVolumeChanged → setVolume
    // Without @Volatile, a write by one thread (e.g., setVolume updating pendingVolume)
    // may not be visible to another thread (e.g., unmuteDelegateIfNeeded reading it) —
    // causing the delegate to unmute with a stale volume value.
    @Volatile private var pendingVolume: Float = 1f
    @Volatile private var delegateMuted: Boolean = false
    private var handleBufferCallCount: Long = 0

    /**
     * Media timeline offset captured from the first buffer's presentationTimeUs
     * after each flush/init. Maps framesWritten=0 to the correct song position.
     * DefaultAudioSink calls this startMediaTimeUs internally.
     */
    private var usbStartMediaTimeUs: Long = 0L
    private var usbStartMediaTimeNeedsInit: Boolean = true
    private var handledEndOfStream: Boolean = false

    /** ExoPlayer's window offset, captured once per track. Never reset by flush.
     *  Used to convert between ExoPlayer timeline and FLAC absolute position. */
    private var windowOffsetUs: Long = -1L

    /** True when the engine was just created and needs its first seek from handleBuffer.
     *  Prevents play() from resuming the engine before the correct position is known. */
    private var engineNeedsInitialSeek: Boolean = false

    /** Path of the file the current native engine is decoding. Used to detect track changes. */
    private var activeEnginePath: String? = null


    /** Max queue entries before returning false for backpressure (paces ExoPlayer).
     *  Pause responsiveness is handled by pauseStreaming(), not queue size. */
    private val QUEUE_BACKPRESSURE_THRESHOLD = 16

    /** Tracks ExoPlayer's play/pause state so seek-while-paused doesn't auto-resume. */
    private var isPlaying = false

    /** Deferred USB reconfiguration — applied after engine finishes playing. */
    private var deferredRate: Int = 0
    private var deferredChannels: Int = 0
    private var deferredEncoding: Int = 0
    private var hasDeferredConfig: Boolean = false


    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        val enc = inputFormat.pcmEncoding
        if (enc != Format.NO_VALUE) currentEncoding = enc

        // If native engine is still playing the SAME track AND the rate didn't change,
        // don't touch it. This happens when ExoPlayer pre-buffers the next track ~10s
        // before EOF. But if the track or rate changed, destroy and reconfigure.
        if (nativeEngine?.isRunning == true || nativeOpusEngine?.isRunning == true) {
            val trackChanged = currentTrackPath != activeEnginePath
            if (!trackChanged) {
                // Same track, ExoPlayer pre-buffering — defer reconfiguration
                if (inputFormat.sampleRate != currentSampleRate || inputFormat.channelCount != currentChannelCount) {
                    deferredRate = inputFormat.sampleRate
                    deferredChannels = inputFormat.channelCount
                    deferredEncoding = enc
                    hasDeferredConfig = true
                    Timber.tag(TAG).i("configure: engine running, pre-buffer — deferred rate=${inputFormat.sampleRate}")
                } else {
                    Timber.tag(TAG).i("configure: engine running, same rate — keeping alive")
                }
                super.configure(inputFormat, specifiedBufferSize, outputChannels)
                muteDelegateIfNeeded()
                return
            }
            // Track changed (manual skip) — destroy engine and proceed
            Timber.tag(TAG).i("configure: track changed, destroying engine")
        }
        // Track changed or engine finished — destroy old engines
        val oldEngine = nativeEngine
        if (oldEngine != null) {
            oldEngine.stop()
            oldEngine.destroy()
            nativeEngine = null
            Timber.tag(TAG).i("configure: destroyed old FLAC engine")
        }
        val oldOpusEngine = nativeOpusEngine
        if (oldOpusEngine != null) {
            oldOpusEngine.stop()
            oldOpusEngine.destroy()
            nativeOpusEngine = null
            Timber.tag(TAG).i("configure: destroyed old Opus engine")
        }
        isNativeEngineActive = false
        activeEnginePath = null

        handleBufferCallCount = 0
        val sr = inputFormat.sampleRate.takeIf { it > 0 }
        val ch = inputFormat.channelCount.takeIf { it > 0 }

        Timber.tag(TAG).i("configure: pcmEncoding=${when(enc) {
            C.ENCODING_PCM_FLOAT -> "FLOAT"; C.ENCODING_PCM_16BIT -> "16BIT"
            C.ENCODING_PCM_24BIT -> "24BIT"; C.ENCODING_PCM_32BIT -> "32BIT"
            else -> "UNKNOWN($enc)"
        }} rate=${inputFormat.sampleRate} ch=${inputFormat.channelCount}")

        if (bitPerfectEnabled && sr != null && ch != null) {
            val device = usbAudioDevice.findUsbAudioDevice()
            if (device != null && usbAudioDevice.hasPermission(device)) {
                configureUsbBitPerfect(sr, ch, enc)
                windowOffsetUs = -1L
                usbStartMediaTimeNeedsInit = true
                if (config.forceRouteToSpeaker) forceMediaToSpeaker()
                super.configure(inputFormat, specifiedBufferSize, outputChannels)
                muteDelegateIfNeeded()
                Timber.tag(TAG).i("Delegate configured (muted, routed to speaker)")
                return
            } else if (device != null) {
                // USB DAC is plugged in but we don't have permission yet. Request
                // permission asynchronously; when granted, force a renderer
                // reconfigure via seekTo(currentPosition) so configure() runs again
                // and the USB pipeline is set up on the next pass. Without this, the
                // permission popup only appears when the DAC is physically plugged in
                // while bit-perfect mode is already ON — toggling bit-perfect ON while
                // the DAC is already connected would never request permission, leaving
                // the overlay stuck on "FFmpeg" / blank engine / blank USB device.
                Timber.tag(TAG).w("USB DAC found but no permission — requesting...")
                usbAudioDevice.requestPermission(device) { granted ->
                    if (granted) {
                        Timber.tag(TAG).i("USB permission granted — forcing reconfigure")
                        // Post to main thread to avoid re-entrant configure() calls
                        // if requestPermission invoked the callback synchronously
                        // (which happens when permission was already granted).
                        attachedPlayer?.let { player ->
                            Handler(Looper.getMainLooper()).post {
                                val pos = player.currentPosition
                                Timber.tag(TAG).i("Reconfigure seekTo($pos) after permission grant")
                                player.seekTo(pos)
                                // Force renderer reconfigure by re-applying track selection
                                // parameters. Without this, seekTo alone does NOT trigger
                                // configure() when the track format hasn't changed — leaving
                                // usbAudioStream null and volume control broken until the
                                // next track change or toggle event.
                                player.trackSelectionParameters = player.trackSelectionParameters
                            }
                        }
                    } else {
                        Timber.tag(TAG).w("USB permission denied by user")
                    }
                }
            }
        }

        super.configure(inputFormat, specifiedBufferSize, outputChannels)

        if (usbAudioStream != null && !bitPerfectEnabled) {
            releaseUsbStream()
        }
    }

    override fun handleBuffer(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int
    ): Boolean {
        val stream = usbAudioStream
        if (bitPerfectEnabled && stream?.isAlive == true) {
            muteDelegateIfNeeded()

            // Fallback engine creation: if no engine and no streaming thread,
            // try creating one now (path and USB rate should both be correct by this point)
            if (nativeEngine == null && nativeOpusEngine == null && usbStreamingThread == null) {
                startNativeEngineIfSupported(stream)
                // Engine starts paused. The usbStartMediaTimeNeedsInit block below
                // will capture presentationTimeUs and seek to the correct position.
            }

            // Capture media timeline offset from first buffer (needed for position tracking)
            if (usbStartMediaTimeNeedsInit) {
                usbStartMediaTimeUs = maxOf(0L, presentationTimeUs)
                usbStartMediaTimeNeedsInit = false
                // Save window offset once per track (not reset by flush/seek).
                // windowOffset = ExoPlayer timeline position of track start (position 0).
                // On fresh start: initialPlayerPosition=0 → offset = pts (correct).
                // On restore at 158s: initialPlayerPosition=158s → offset = pts - 158s (correct).
                if (windowOffsetUs < 0) {
                    windowOffsetUs = presentationTimeUs - initialPlayerPositionUs
                }
                Timber.tag(TAG).i("startMediaTimeUs=$usbStartMediaTimeUs windowOffset=$windowOffsetUs initialPos=${initialPlayerPositionUs / 1000}ms")

                // After a flush (seek) or initial start, seek the native engine
                // to the correct position and resume it.
                val engine = nativeEngine
                val opusEngine = nativeOpusEngine
                if (windowOffsetUs >= 0) {
                    val enginePositionUs = presentationTimeUs - windowOffsetUs
                    if (enginePositionUs >= 0) {
                        if (engine != null) {
                            engine.seek(enginePositionUs)
                            if (isPlaying) engine.resume()
                            engineNeedsInitialSeek = false
                            Timber.tag(TAG).i("Native FLAC engine seek to ${enginePositionUs / 1_000_000}s (playing=$isPlaying)")
                        } else if (opusEngine != null) {
                            opusEngine.seek(enginePositionUs)
                            if (isPlaying) opusEngine.resume()
                            engineNeedsInitialSeek = false
                            Timber.tag(TAG).i("Native Opus engine seek to ${enginePositionUs / 1_000_000}s (playing=$isPlaying)")
                        }
                    }
                }
                // Re-block LoadControl now that we have the position.
                // flush() temporarily unblocked it to allow this handleBuffer call.
                if (nativeEngine?.isRunning == true || nativeOpusEngine?.isRunning == true) {
                    isNativeEngineActive = true
                }
            }

            // Native engine handles decode+USB directly — ignore ExoPlayer data.
            val engine = nativeEngine
            val opusEngine = nativeOpusEngine
            if (engine != null || opusEngine != null) {
                val running = (engine?.isRunning == true) || (opusEngine?.isRunning == true)
                if (running) {
                    buffer.position(buffer.limit())
                    return true
                }
                // Engine finished playing — clean up for next track.
                // Lazy creation at the top of handleBuffer will create a new engine
                // with the correct currentTrackPath on the next call.
                Timber.tag(TAG).i("Native engine finished — cleaning up for next track")
                engine?.destroy()
                nativeEngine = null
                opusEngine?.destroy()
                nativeOpusEngine = null
                isNativeEngineActive = false
                activeEnginePath = null
                windowOffsetUs = -1L
                usbStartMediaTimeNeedsInit = true
                // Return true for this buffer — next handleBuffer will create new engine
                buffer.position(buffer.limit())
                return true
            }

            val thread = usbStreamingThread ?: return true

            // Backpressure: if queue is nearly full, tell ExoPlayer to retry later.
            // This paces the renderer to the USB DAC's consumption rate without
            // depending on the delegate AudioTrack.
            if (thread.queueSize() >= QUEUE_BACKPRESSURE_THRESHOLD) {
                return false
            }

            handleBufferCallCount++
            val snapshot: ByteBuffer = buffer.slice().order(buffer.order())

            if (currentEncoding == C.ENCODING_PCM_FLOAT) {
                val totalSamples = snapshot.remaining() / 4
                if (totalSamples > 0) {
                    val floatBuf = FloatArray(totalSamples)
                    snapshot.asFloatBuffer().get(floatBuf)
                    if (handleBufferCallCount <= 3) {
                        Timber.tag(TAG).i("handleBuffer #$handleBufferCallCount: FLOAT samples=$totalSamples")
                    }
                    thread.enqueue(floatBuf)
                }
            } else {
                val remaining = snapshot.remaining()
                if (remaining > 0) {
                    val rawBytes = ByteArray(remaining)
                    snapshot.get(rawBytes)
                    if (handleBufferCallCount <= 3) {
                        val bps = PcmUtils.bytesPerSample(currentEncoding)
                        Timber.tag(TAG).i("handleBuffer #$handleBufferCallCount: RAW ${bps*8}bit bytes=$remaining")
                    }
                    thread.enqueueRaw(rawBytes, currentEncoding)
                }
            }

            // Advance buffer and return true — no delegate dependency.
            buffer.position(buffer.limit())
            return true
        }

        // H3 fix: USB stream died mid-track (cable yanked, DAC power-lost, or URB
        // submission failed). The stream reference is stale — if we don't release
        // it, (a) usbAudioStream stays non-null and configure() will skip stream
        // recreation on the next track, (b) clearForcedRouting() is never called
        // so the delegate stays routed to speaker, (c) every subsequent handleBuffer
        // call hits this same fallthrough path with a dead stream.
        // releaseUsbStream() nulls usbAudioStream, drains/closes the fd, clears
        // forced routing, and unmutes the delegate — exactly what's needed for
        // ExoPlayer to resume through the normal Android audio path.
        // Run synchronously here (we're already on the renderer thread, and a dead
        // stream drains URBs near-instantly since they've already failed).
        if (bitPerfectEnabled && stream != null && !stream.isAlive) {
            Timber.tag(TAG).w("handleBuffer: USB stream died mid-track — releasing and falling back to delegate")
            releaseUsbStream()
        }

        unmuteDelegateIfNeeded()
        return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
    }

    // ── Position tracking via USB framesWritten ────────────────────────

    private var posLogCount = 0L

    private var engineEndNotified = false

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        if (bitPerfectEnabled) {
            val streamAlive = usbAudioStream?.isAlive == true
            val engine = nativeEngine ?: nativeOpusEngine
            val engineCreated = engine?.isCreated == true

            if (++posLogCount % 500 == 1L) {
                Timber.tag(TAG).i("getPositionUs: streamAlive=$streamAlive engine=$engineCreated " +
                        "running=${engine?.isRunning} window=$windowOffsetUs enginePos=${engine?.getPositionUs()}")
            }

            // Detect engine finished — advance to next track internally.
            // ExoPlayer's renderer never reaches outputStreamEnded because
            // LoadControl blocked loading, so we skip externally via the Player ref.
            if (engine != null && !engine.isRunning && !engineEndNotified) {
                engineEndNotified = true
                Timber.tag(TAG).i("Engine finished — advancing to next track")
                val p = attachedPlayer
                if (p != null) {
                    Handler(Looper.getMainLooper()).post {
                        if (p.hasNextMediaItem()) {
                            p.seekToNextMediaItem()
                        } else {
                            p.pause()
                        }
                    }
                }
            }

            // Native engine: absolute position + window offset (FLAC or Opus)
            if (streamAlive && engineCreated && windowOffsetUs >= 0) {
                return windowOffsetUs + engine!!.getPositionUs()
            }

            // ExoPlayer pipeline fallback: relative framesWritten + startMediaTime
            if (streamAlive) {
                if (usbStartMediaTimeNeedsInit) return AudioSink.CURRENT_POSITION_NOT_SET
                val frames = usbAudioStream?.framesWritten ?: 0L
                return if (currentSampleRate > 0) {
                    usbStartMediaTimeUs + frames * C.MICROS_PER_SECOND / currentSampleRate
                } else AudioSink.CURRENT_POSITION_NOT_SET
            }
        }
        return super.getCurrentPositionUs(sourceEnded)
    }

    override fun isEnded(): Boolean {
        if (bitPerfectEnabled) {
            val engine = nativeEngine ?: nativeOpusEngine
            // Engine still running → not ended
            if (engine != null && engine.isRunning) return false
            // Engine exists but stopped → it finished (EOF). Signal ended directly.
            // Cannot delegate to super because LoadControl blocked ExoPlayer's loading,
            // so the delegate never reached end-of-stream on its own.
            if (engine != null && !engine.isRunning) return true
        }
        return super.isEnded()
    }

    override fun hasPendingData(): Boolean {
        if (bitPerfectEnabled) {
            // Engine running → has pending data
            if (nativeEngine?.isRunning == true || nativeOpusEngine?.isRunning == true) return true
            if (usbStreamingThread?.hasPendingData() == true) return true
        }
        return super.hasPendingData()
    }

    override fun playToEndOfStream() {
        handledEndOfStream = true
        // Always propagate to delegate — ExoPlayer needs this signal to
        // detect end-of-stream and transition to the next track.
        super.playToEndOfStream()
    }

    override fun play() {
        super.play()
        isPlaying = true
        val resumed = if (!engineNeedsInitialSeek) {
            nativeEngine?.resume(); nativeOpusEngine?.resume(); true
        } else false
        usbStreamingThread?.resumeStreaming()
        Timber.tag(TAG).i("play() needsSeek=$engineNeedsInitialSeek resumed=$resumed")
    }

    override fun pause() {
        isPlaying = false
        if (!engineNeedsInitialSeek) {
            nativeEngine?.pause()
            nativeOpusEngine?.pause()
        }
        usbStreamingThread?.pauseStreaming()
        super.pause()
    }

    override fun setVolume(volume: Float) {
        pendingVolume = volume
        val streamAlive = usbAudioStream?.isAlive == true
        Timber.tag(TAG).d("setVolume($volume): bitPerfect=$bitPerfectEnabled streamAlive=$streamAlive")
        if (bitPerfectEnabled && streamAlive) {
            muteDelegateIfNeeded()
            try {
                val ok = usbAudioDevice.setUsbVolume(volume)
                if (!ok) {
                    Timber.tag(TAG).w("setVolume($volume): USB DAC hardware volume unavailable — output remains at DAC's current level")
                }
            } catch (e: Exception) {
                Timber.tag(TAG).w("setVolume($volume): USB DAC hardware volume threw: ${e.message}")
            }
        } else {
            unmuteDelegateIfNeeded()
            // Diagnose why USB DAC volume is not active. This branch is a no-op for USB DAC
            // volume — the delegate AudioTrack handles volume via AudioFlinger instead.
            // Common causes: usbAudioStream is null (DAC not opened), stream not alive
            // (DAC disconnected), or bitPerfectEnabled is false.
            if (bitPerfectEnabled && !streamAlive) {
                Timber.tag(TAG).w("setVolume($volume): bitPerfect ON but USB stream not alive — volume has no effect on USB DAC output. usbAudioStream=${if (usbAudioStream != null) "exists" else "null"}")
            }
        }
    }

    override fun flush() {
        super.flush()
        // Native engine handles its own flush/seek internally
        // ExoPlayer pipeline: flush queue + native stream
        usbStreamingThread?.flush()
        usbAudioStream?.flush()
        usbStartMediaTimeNeedsInit = true
        handledEndOfStream = false
        // Temporarily unblock LoadControl so ExoPlayer loads at least one chunk
        // after seek. handleBuffer will re-block once it captures presentationTimeUs.
        // Without this, the LoadControl blocks ALL post-seek loading and the engine
        // never knows where to seek to.
        if (nativeEngine?.isRunning == true || nativeOpusEngine?.isRunning == true) {
            isNativeEngineActive = false
        }
    }

    override fun reset() {
        super.reset()
        // USB stream survives reset — configure() manages its lifecycle.
        // ExoPlayer calls reset() frequently (track changes, seeks).
        // Killing USB here causes audio to briefly route to the speaker.
    }

    override fun release() {
        // H1 fix: unregister the DETACHED receiver so we don't leak a Context-bound
        // BroadcastReceiver after the sink is released. try/catch guards against
        // "Receiver not registered" on devices where the receiver was never
        // successfully registered (e.g., security exceptions on some OEMs).
        try {
            context.unregisterReceiver(usbDetachedReceiver)
            Timber.tag(TAG).i("USB_DEVICE_DETACHED receiver unregistered")
        } catch (e: IllegalArgumentException) {
            // Already unregistered or never registered — safe to ignore.
        }
        releaseUsbStream()
        super.release()
    }

    // ── USB bit-perfect configuration ───────────────────────────────

    private fun configureUsbBitPerfect(sampleRate: Int, channelCount: Int, encoding: Int) {
        // NOTE: engine is NOT destroyed here. configure() returns early if engine
        // is still running. If we reach here, the engine is already dead or null.

        // Cache check — avoid needless USB stream recreation
        if (sampleRate == currentSampleRate && channelCount == currentChannelCount
            && usbAudioStream?.isAlive == true) {
            Timber.tag(TAG).d("USB stream cached for rate=$sampleRate ch=$channelCount — reusing")
            // Engine will be created lazily in handleBuffer when currentTrackPath is set
            return
        }

        if (usbAudioStream != null) releaseUsbStream()

        val usbDevice = usbAudioDevice.findUsbAudioDevice() ?: return
        var deviceInfo = usbAudioDevice.openDevice(usbDevice)
        if (deviceInfo == null) {
            Timber.tag(TAG).e("Failed to open USB device")
            return
        }
        currentUsbDeviceName = deviceInfo.deviceName

        // Always use the DAC's highest supported bit depth (standard practice).
        // Sources with lower bit depth are zero-padded in the LSBs.
        val bitDepth = deviceInfo.bestBitDepth
        val altSetting = deviceInfo.bestAltSetting
        Timber.tag(TAG).i("Bit-perfect: source=${trackBitDepth}bit → alt=$altSetting usb=${bitDepth}bit " +
                "clockSource=0x${deviceInfo.clockSourceId.toString(16)}")

        var stream = UsbAudioStream(
            fd = deviceInfo.fd,
            interfaceId = deviceInfo.interfaceId,
            endpointOut = deviceInfo.endpointOutAddress,
            endpointFeedback = deviceInfo.endpointFeedbackAddress,
            sampleRate = sampleRate,
            channelCount = channelCount,
            bitDepth = bitDepth,
            maxPacketSize = deviceInfo.maxPacketSize
        )

        if (!stream.isReady) {
            Timber.tag(TAG).e("USB stream creation failed")
            stream.release()
            return
        }

        // ─── xHCI-verified transition sequence (from USB protocol analysis) ───
        //
        // 1. setAlt(0)       → xHCI Configure Endpoint (FREE old rings)
        // 2. SET_CUR          → write new sample rate to Clock Source
        // 3. GET_CUR          → verify clock accepted (CLOCK_VALID_CONTROL)
        // 4. setAlt(0) AGAIN  → defensive reset after clock change
        // 5. setAlt(N)        → xHCI Configure Endpoint (ALLOC new rings)
        // 6. wait ~47ms       → DAC PLL lock time
        // 7. start            → submit URBs

        // Step 1: setAlt(0) — FREE old ISO rings
        if (!usbAudioDevice.setAltSetting(0)) {
            Timber.tag(TAG).w("setAlt(0) failed — stale fd, reopening device...")
            usbAudioDevice.closeDevice()
            stream.release()
            deviceInfo = usbAudioDevice.openDevice(usbDevice)
            if (deviceInfo == null) {
                Timber.tag(TAG).e("Failed to reopen USB device")
                return
            }
            stream = UsbAudioStream(
                fd = deviceInfo.fd,
                interfaceId = deviceInfo.interfaceId,
                endpointOut = deviceInfo.endpointOutAddress,
                endpointFeedback = deviceInfo.endpointFeedbackAddress,
                sampleRate = sampleRate,
                channelCount = channelCount,
                bitDepth = bitDepth,
                maxPacketSize = deviceInfo.maxPacketSize
            )
            if (!stream.isReady) {
                Timber.tag(TAG).e("USB stream recreation failed after reopen")
                stream.release()
                return
            }
            Timber.tag(TAG).i("Device reopened with fresh fd=${deviceInfo.fd}")
        }
        Timber.tag(TAG).i("Step 1: setAlt(0) — old ISO ring freed")

        // Step 2: SET_CUR — write new sample rate
        usbAudioDevice.setSampleRate(sampleRate)

        // Step 3: GET_CUR(CLOCK_VALID_CONTROL) — verify clock is locked
        val clockValid = usbAudioDevice.readClockValid()
        Timber.tag(TAG).i("Step 2-3: SET_CUR=$sampleRate, CLOCK_VALID=$clockValid")

        // Step 4: setAlt(0) AGAIN — defensive reset after clock change
        usbAudioDevice.setAltSetting(0)
        Timber.tag(TAG).i("Step 4: setAlt(0) again — defensive reset")

        // Step 5: setAlt(N) — ALLOC new ISO rings
        val altResult = usbAudioDevice.setAltSetting(altSetting)
        Timber.tag(TAG).i("Step 5: setAlt($altSetting): $altResult — new ISO ring allocated")

        // Step 6: wait ~47ms — DAC PLL lock time
        Thread.sleep(50)

        if (!stream.start()) {
            Timber.tag(TAG).e("USB stream start failed")
            stream.release()
            return
        }

        usbAudioStream = stream
        currentSampleRate = sampleRate
        currentChannelCount = channelCount
        muteDelegateIfNeeded()

        // Try to create engine now (works for first track where onMediaItemTransition
        // fired before configure). For subsequent tracks, createEngineIfNeeded() in
        // onMediaItemTransition handles it (path is correct by then).
        startNativeEngineIfSupported(stream)

        Timber.tag(TAG).i("USB bit-perfect stream ACTIVE: rate=$sampleRate ch=$channelCount " +
                "bits=$bitDepth device=${deviceInfo.deviceName}")
    }

    /** Try to start a native engine (FLAC or Opus) for the current track.
     *  Falls back to ExoPlayer streaming thread if no native engine applies.
     *
     *  Dispatch by file extension:
     *  - `.flac` → NativeAudioEngine (FLAC parser + bit-perfect decode)
     *  - `.opus` → NativeOpusEngine (libopus JNI + Ogg demuxer)
     *  - `.ogg`  → sniff OpusHead magic; if present → NativeOpusEngine,
     *              else fall through to ExoPlayer (Vorbis/FLAC-in-Ogg handled by FFmpeg)
     *  - other   → ExoPlayer pipeline (FFmpeg float, NOT bit-perfect)
     */
    @Synchronized
    private fun startNativeEngineIfSupported(stream: UsbAudioStream) {
        if (nativeEngine != null || nativeOpusEngine != null) return  // already created

        // Stop existing streaming thread (mutually exclusive with native engine)
        usbStreamingThread?.stop()
        usbStreamingThread = null

        val path = currentTrackPath
        if (path != null) {
            val lower = path.lowercase()
            when {
                lower.endsWith(".flac") -> startFlacEngine(stream, path)
                lower.endsWith(".opus") -> startOpusEngine(stream, path)
                lower.endsWith(".ogg") -> {
                    // .ogg may contain Opus, Vorbis, or FLAC. Sniff first ~512 bytes
                    // for "OpusHead" magic to confirm Opus payload.
                    if (isOpusPayload(path)) {
                        startOpusEngine(stream, path)
                    } else {
                        Timber.tag(TAG).i("Ogg file is not Opus payload, using ExoPlayer: ${File(path).name}")
                    }
                }
                else -> Timber.tag(TAG).i("Unsupported format for native engine: ${File(path).name}")
            }
        }

        // If no native engine was created, fall back to ExoPlayer streaming thread
        if (nativeEngine == null && nativeOpusEngine == null && usbStreamingThread == null) {
            usbStreamingThread = UsbStreamingThread(stream).also { it.start() }
            Timber.tag(TAG).i("Using ExoPlayer pipeline (no native engine available)")
        }
    }

    /** Create and start the native FLAC engine. Sets [nativeEngine] on success. */
    private fun startFlacEngine(stream: UsbAudioStream, path: String) {
        val engine = NativeAudioEngine()
        try {
            val fd = android.os.ParcelFileDescriptor.open(
                File(path), android.os.ParcelFileDescriptor.MODE_READ_ONLY
            )
            val created = engine.createFromFd(fd.fd, stream.nativeHandle)
            fd.close()
            if (created && engine.start()) {
                // Verify FLAC sample rate matches USB stream — prevents distortion
                // when ExoPlayer's queue and onMediaItemTransition disagree about
                // which track is playing (e.g., cross-album Recently Played lists).
                if (engine.getSampleRate() != currentSampleRate) {
                    Timber.tag(TAG).w("Rate mismatch: FLAC=${engine.getSampleRate()} USB=$currentSampleRate" +
                            " — falling back to ExoPlayer pipeline")
                    engine.stop()
                    engine.destroy()
                } else {
                    // Start paused — will resume in handleBuffer after capturing
                    // the correct seek position from ExoPlayer's presentationTimeUs.
                    engine.pause()
                    nativeEngine = engine
                    isNativeEngineActive = true
                    engineNeedsInitialSeek = true
                    engineEndNotified = false
                    activeEnginePath = path
                    trackBitDepth = engine.getBitsPerSample()
                    Timber.tag(TAG).i("Native FLAC engine started (paused, awaiting seek) for: ${File(path).name} ${trackBitDepth}-bit")
                    return
                }
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w("Native FLAC engine failed: ${e.message}")
        }
        engine.destroy()
    }

    /** Create and start the native Opus engine. Sets [nativeOpusEngine] on success.
     *
     *  Path C of the bit-perfect implementation: uses libopus directly via JNI to
     *  decode Ogg/Opus to int16 PCM, bypassing FFmpeg's float pipeline. The int16
     *  PCM is losslessly padded (left-shift by 16) when the DAC alt setting is
     *  32-bit, matching the FLAC engine's behavior for 16-bit FLAC files.
     *
     *  Fallback conditions (any → ExoPlayer pipeline takes over):
     *  - libopus native library not built (setup.sh not run) → isAvailable = false
     *  - File is not a valid Ogg/Opus stream (OpusHead parse fails)
     *  - Opus sample rate (48kHz) doesn't match USB DAC alt setting
     *  - Buffer allocation fails
     */
    private fun startOpusEngine(stream: UsbAudioStream, path: String) {
        val probe = NativeOpusEngine()
        if (!probe.isAvailable) {
            Timber.tag(TAG).w("Native Opus engine unavailable (libopus not built) — " +
                    "run decent-usb-audio-driver/setup.sh, then rebuild. " +
                    "Falling back to ExoPlayer pipeline.")
            return
        }

        val engine = NativeOpusEngine()
        try {
            val fd = android.os.ParcelFileDescriptor.open(
                File(path), android.os.ParcelFileDescriptor.MODE_READ_ONLY
            )
            val created = engine.createFromFd(fd.fd, stream.nativeHandle)
            fd.close()
            if (created && engine.start()) {
                // Verify Opus sample rate matches USB stream. Opus is always 48kHz
                // internally; if the DAC alt setting is different (e.g., 44.1kHz),
                // we fall back to ExoPlayer which will resample.
                if (engine.getSampleRate() != currentSampleRate) {
                    Timber.tag(TAG).w("Rate mismatch: Opus=${engine.getSampleRate()} USB=$currentSampleRate" +
                            " — falling back to ExoPlayer pipeline")
                    engine.stop()
                    engine.destroy()
                } else {
                    // Start paused — will resume in handleBuffer after capturing
                    // the correct seek position from ExoPlayer's presentationTimeUs.
                    engine.pause()
                    nativeOpusEngine = engine
                    isNativeEngineActive = true
                    engineNeedsInitialSeek = true
                    engineEndNotified = false
                    activeEnginePath = path
                    trackBitDepth = engine.getBitsPerSample()  // always 16 for Opus
                    Timber.tag(TAG).i("Native Opus engine started (paused, awaiting seek) for: ${File(path).name} ${trackBitDepth}-bit")
                    return
                }
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w("Native Opus engine failed: ${e.message}")
        }
        engine.destroy()
    }

    /** Sniff first ~512 bytes of an .ogg file for the "OpusHead" magic.
     *  RFC 7845: the second Ogg page (first audio page after the OggS page) starts
     *  with "OpusHead" for Opus-in-Ogg streams. Returns false for Vorbis/FLAC-in-Ogg. */
    private fun isOpusPayload(path: String): Boolean {
        return try {
            File(path).inputStream().use { input ->
                val header = ByteArray(512)
                val read = input.read(header)
                if (read < 32) return@use false
                // Look for "OpusHead" magic anywhere in the first 512 bytes.
                // It appears at the start of the second Ogg page's first packet
                // (after the "OggS" page header + segment table).
                val magic = "OpusHead".toByteArray(Charsets.US_ASCII)
                // Simple substring search
                for (i in 0..(read - magic.size)) {
                    var match = true
                    for (j in magic.indices) {
                        if (header[i + j] != magic[j]) { match = false; break }
                    }
                    if (match) return@use true
                }
                false
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w("isOpusPayload: failed to sniff $path: ${e.message}")
            false
        }
    }

    // ── USB stream release ──────────────────────────────────────────

    @Synchronized
    private fun releaseUsbStream() {
        val stream = usbAudioStream ?: return
        usbAudioStream = null
        currentUsbDeviceName = null

        // Stop USB stream FIRST — sets ctx->running=false, which unblocks
        // submitPcmToUrbs inside the native engine's decode thread.
        // Without this, nativeEngine.stop() deadlocks on pthread_join.
        stream.stop()

        // Now safe to stop native engines (decode thread can exit)
        nativeEngine?.stop()
        nativeEngine?.destroy()
        nativeEngine = null
        nativeOpusEngine?.stop()
        nativeOpusEngine?.destroy()
        nativeOpusEngine = null
        isNativeEngineActive = false

        // Stop the streaming thread (drains queue, joins thread)
        usbStreamingThread?.stop()
        usbStreamingThread = null

        // Drain ALL in-flight URBs — MUST complete before setAlt(0)
        val drained = stream.drainUrbs()
        Timber.tag(TAG).i("USB stream drained $drained URBs")

        // Release native context
        stream.release()

        // Keep device connection open between tracks (standard practice)
        clearForcedRouting()
        unmuteDelegateIfNeeded()
        Timber.tag(TAG).i("USB audio stream released (device kept open)")
    }

    // ── Delegate volume management ──────────────────────────────────

    private fun muteDelegateIfNeeded() {
        if (!delegateMuted) { super.setVolume(0f); delegateMuted = true }
    }

    private fun unmuteDelegateIfNeeded() {
        if (delegateMuted) { super.setVolume(pendingVolume); delegateMuted = false }
    }

    // ── Audio routing helpers ───────────────────────────────────────

    private fun forceMediaToSpeaker() {
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val speaker = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            if (speaker != null) {
                delegate.setPreferredDevice(speaker)
                Timber.tag(TAG).i("Delegate routed to speaker")
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w("forceMediaToSpeaker failed: ${e.message}")
        }
    }

    private fun clearForcedRouting() {
        try { delegate.setPreferredDevice(null) } catch (_: Exception) {}
    }

    // ── Player integration (attachToPlayer) ──────────────────────

    @Volatile private var attachedPlayer: Player? = null
    private var integrationListener: Player.Listener? = null

    /**
     * Attach this sink to an ExoPlayer instance. Registers an internal
     * [Player.Listener] that handles:
     * - Extracting the file path from each [MediaItem]'s URI
     * - Cleaning up finished native engines on track transitions
     * - Creating new native engines for local FLAC files
     * - Advancing to the next track when the native engine reaches EOF
     *
     * Must be called on the main thread, after [ExoPlayer.Builder.build].
     */
    fun attachToPlayer(player: Player) {
        val oldListener = integrationListener
        val oldPlayer = attachedPlayer
        if (oldListener != null && oldPlayer != null) {
            oldPlayer.removeListener(oldListener)
        }

        val listener = PlayerIntegrationListener()
        player.addListener(listener)
        attachedPlayer = player
        integrationListener = listener
        Timber.tag(TAG).i("attachToPlayer: integration listener registered")
    }

    /** Detach from the current player. Call before player.release(). */
    fun detachFromPlayer() {
        val listener = integrationListener
        val player = attachedPlayer
        if (listener != null && player != null) {
            player.removeListener(listener)
        }
        attachedPlayer = null
        integrationListener = null
    }

    /** Player position (us) captured in onMediaItemTransition. Used to calculate
     *  the correct window offset on restore (first handleBuffer pts is at the
     *  restored position, not at 0). */
    @Volatile
    private var initialPlayerPositionUs: Long = 0L

    private inner class PlayerIntegrationListener : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (mediaItem == null) return

            // Capture player position BEFORE engine creation. On restore this is
            // the saved position (e.g., 158s). On fresh start this is 0.
            initialPlayerPositionUs = (attachedPlayer?.currentPosition ?: 0L) * 1000L
            Timber.tag(TAG).i("onMediaItemTransition: initialPlayerPos=${initialPlayerPositionUs / 1000}ms")

            val uri = mediaItem.localConfiguration?.uri

            // 1. Clean up finished engine from previous track
            val engineFinished = cleanupFinishedEngine()

            // 2. Resolve file path from URI
            val resolvedPath = resolveTrackPath(uri)
            currentTrackPath = resolvedPath
            Timber.tag(TAG).i("onMediaItemTransition: uri=$uri path=$resolvedPath")

            // 3. Create engine if local FLAC
            if (resolvedPath != null) {
                createEngineIfNeeded()
            }

            // 4. If previous engine finished, reset position for new track
            if (engineFinished) {
                attachedPlayer?.seekTo(0)
            }
        }
    }

    /**
     * Resolve a [MediaItem]'s URI to a local file path for the native engine.
     *
     * - `file:///path/to/song.flac` → `/path/to/song.flac`
     * - `/storage/.../song.flac` (bare path) → as-is
     * - `content://media/external/audio/123` → resolved via ContentResolver
     * - `http://` or `https://` → null (ExoPlayer pipeline handles these)
     */
    private fun resolveTrackPath(uri: Uri?): String? {
        if (uri == null) return null
        return when (uri.scheme) {
            "file" -> uri.path
            "content" -> resolveContentUri(uri)
            "http", "https" -> {
                Timber.tag(TAG).i("resolveTrackPath: HTTP URI → ExoPlayer pipeline (no native engine)")
                null
            }
            null -> {
                // Bare path string (no scheme) — common in local music players
                val pathStr = uri.toString()
                if (pathStr.startsWith("/")) pathStr else null
            }
            else -> null
        }
    }

    private fun resolveContentUri(uri: Uri): String? {
        return try {
            context.contentResolver.query(
                uri,
                arrayOf(MediaStore.Audio.Media.DATA),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
                    if (idx >= 0) cursor.getString(idx) else null
                } else null
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w("resolveContentUri failed: ${e.message}")
            null
        }
    }

    /**
     * Take a thread-safe snapshot of the current audio pipeline state for UI display.
     *
     * This is intended to be polled at a fixed interval (e.g. 500ms) by the UI layer.
     * All reads happen under the engine lock to prevent tearing when the engine is
     * being reconfigured mid-track.
     *
     * Returns null when the USB pipeline is not actually active — i.e., no DAC is open
     * ([usbAudioStream] is null or dead) AND no native decoder engine is running. In
     * this state, audio is flowing through Android's default AudioFlinger sink (the
     * delegate), and a non-null snapshot would report misleading fields: the decoder
     * would claim "FFmpeg (float → int)" based solely on the file extension (even
     * though FFmpeg is not feeding any USB stream), the engine would be "None"
     * (rendered as "—"), and the USB device name would be null ("—").
     *
     * By returning null, the caller ([AudioInfoProvider]) will report null to the UI,
     * which causes [AudioInfo.from] to use its null-snapshot path — correctly showing
     * "Android (AudioFlinger)" as the engine and "ExoPlayer MediaCodec (codec)" as the decoder
     * (NOT "Pending (codec)" — that was a misleading label that implied a transient state which
     * never resolves when no DAC is connected). This gives the user an accurate picture:
     * bit-perfect mode is enabled but no DAC is connected, so audio is going through Android's
     * default pipeline. See the SOP in [AudioInfo] class KDoc for details.
     *
     * @return An [AudioInfoSnapshot] describing the current decoder, engine, output,
     *         and bit-perfect state, or null when the USB pipeline is not active.
     */
    @Synchronized
    fun snapshotAudioInfo(): AudioInfoSnapshot? {
        val path = currentTrackPath
        val flacEngine = nativeEngine
        val opusEngine = nativeOpusEngine
        val stream = usbAudioStream
        val streamAlive = stream?.isAlive == true
        val bitPerfectOn = bitPerfectEnabled

        // If no USB pipeline is actually active (no alive stream AND no running native
        // engine), audio is going through Android's AudioFlinger. Return null so the
        // UI layer falls back to the "Android (AudioFlinger)" display path instead of
        // showing misleading fields (e.g., "FFmpeg (float → int)" decoder when FFmpeg
        // isn't actually feeding any USB stream, or "—" for engine/device).
        if (!streamAlive &&
            flacEngine?.isRunning != true &&
            opusEngine?.isRunning != true
        ) {
            return null
        }

        // --- Decoder info ---
        //
        // Native engine labels are based on engine EXISTENCE (not just isRunning):
        // a paused native engine (e.g., right after createFromFd + pause() at line 940,
        // before handleBuffer resumes it) is STILL the active decoder. Using isRunning
        // here caused a transient mislabel where the overlay showed "FFmpeg (float → int)"
        // during the brief paused window — see deferred-bug note from prior audit.
        //
        // The `usbStreamingThread != null` branch (before `path == null`) fixes the case
        // where `currentTrackPath` is null (Android 10+ scoped storage where
        // MediaStore.Audio.Media.DATA column returns null) but FFmpeg is still actively
        // feeding PCM to the USB DAC. Without this branch, decoderInfo returned null,
        // causing AudioInfo.from() to fall back to deriveDecoderInfo(song) which labels
        // "ExoPlayer MediaCodec" — even though the actual decoder is FFmpeg (matching
        // engineUsed "FFmpeg → int"). VLM screenshot confirmed this mislabel for Opus.
        val decoderInfo: String? = when {
            flacEngine != null -> "Native FLAC (libFLAC)"
            opusEngine != null -> "Native Opus (libopus)"
            usbStreamingThread != null -> "FFmpeg (float → int)"  // FFmpeg feeding USB stream
            path == null -> null  // No engine, no streaming thread — unknown
            path.lowercase().endsWith(".flac") && flacEngine == null ->
                "FFmpeg (float → int)"  // FLAC via FFmpeg fallback (engine failed to start)
            path.lowercase().endsWith(".opus") && opusEngine == null ->
                "FFmpeg (float → int)"  // Opus via FFmpeg fallback
            else -> "FFmpeg (float → int)"  // All non-native formats go through FFmpeg
        }

        // --- Music format (from file extension) ---
        val musicFormat: String? = path?.let { inferFormatFromPath(it) }

        // --- Engine used ---
        // Same existence check as decoderInfo: a paused native engine is still the
        // configured engine — reporting "FFmpeg → int" in that window is misleading.
        val engineUsed: String = when {
            flacEngine != null -> "Native FLAC"
            opusEngine != null -> "Native Opus"
            usbStreamingThread != null -> "FFmpeg → int"
            streamAlive -> "ExoPlayer pipeline"
            else -> "None"
        }

        // --- Resampler status ---
        val resamplerStatus: String = when {
            !bitPerfectOn -> "Not applicable"
            flacEngine != null || opusEngine != null ->
                "Native (no resampling)"
            streamAlive -> "Native (no resampling)"  // USB stream matches source
            else -> "Not applicable"
        }

        // --- Passthrough status ---
        // Native engine existence (not just isRunning) = true passthrough path.
        //
        // Labeling accuracy per audiophile standard:
        // - FLAC: "Passthrough" = accurate. FLAC is lossless, decoded PCM is
        //   bit-identical to the original recording. No resampling, no float math.
        // - Opus: "Native decode" (NOT "Passthrough"). Opus is a lossy codec —
        //   the decoded PCM is a reconstruction, not the original. "Passthrough"
        //   in audiophile terminology means sending the raw compressed bitstream
        //   to an external decoder (e.g. S/PDIF Dolby/DTS). Opus is decoded
        //   locally by libopus, so "Native decode" is the honest label.
        // - FFmpeg path: "PCM (decoded)" = accurate. FFmpeg decodes to float,
        //   then converts to int — already decoded, not passthrough.
        val passthroughStatus: String = when {
            !bitPerfectOn -> "Not applicable"
            flacEngine != null -> "Passthrough"
            opusEngine != null -> "Native decode"
            streamAlive -> "PCM (decoded)"
            else -> "Off"
        }

        // --- Output channel count ---
        val outputChannelCount: Int = currentChannelCount

        // --- Sampling info (output rate + bit depth) ---
        val samplingInfo: String? = if (currentSampleRate > 0) {
            val bits = trackBitDepth.takeIf { it > 0 } ?: encodingToBits(currentEncoding)
            "${formatRate(currentSampleRate)} / ${bits}-bit"
        } else null

        // --- Bit-perfect info ---
        // Native engine existence (not just isRunning) = bit-perfect path active.
        //
        // Labeling accuracy per audiophile standard:
        // - FLAC: "Bit-perfect" = accurate. FLAC is lossless — decoded PCM is
        //   bit-identical to the original recording. The decode→USB path adds
        //   zero processing (no float, no resampling, no software gain).
        // - Opus: "Lossy source" (NOT "Bit-perfect" or "Lossless path"). Opus
        //   is a lossy codec — source material has already lost data during
        //   compression. The decode→USB transport is lossless (libopus outputs
        //   int16 directly, no float conversion), but the source itself is not.
        //   "Lossy source" is unambiguous: it tells the user the file format
        //   is lossy, without implying the transport is lossy.
        //   ("Lossless path" was rejected — ambiguous, could be read as
        //   "true lossless audio" like FLAC/ALAC/WAV.)
        // - FFmpeg path: "PCM (converted)" (NOT "PCM (lossless)"). FFmpeg
        //   outputs float32, which is converted to int16/24/32 via truncation.
        //   This conversion introduces quantization error (~0.5 LSB noise).
        //   "Lossless" implies zero quality loss, which is not true here.
        val bitPerfectInfo: String = when {
            !bitPerfectOn -> "Off"
            flacEngine != null -> "Bit-perfect"
            opusEngine != null -> "Lossy source"
            streamAlive -> "PCM (converted)"
            else -> "Off"
        }

        // --- Audio bit info (source bitrate) ---
        val audioBitInfo: String = if (currentSampleRate > 0 && currentChannelCount > 0) {
            val bits = trackBitDepth.takeIf { it > 0 } ?: encodingToBits(currentEncoding)
            if (bits > 0) {
                val kbps = (currentSampleRate * bits * currentChannelCount) / 1000
                "${kbps} kbps (PCM)"
            } else "—"
        } else "—"

        return AudioInfoSnapshot(
            usbAudioDeviceName = currentUsbDeviceName,
            decoderInfo = decoderInfo,
            musicFormat = musicFormat,
            engineUsed = engineUsed,
            resamplerStatus = resamplerStatus,
            passthroughStatus = passthroughStatus,
            outputChannelCount = outputChannelCount,
            samplingInfo = samplingInfo,
            bitPerfectInfo = bitPerfectInfo,
            audioBitInfo = audioBitInfo,
        )
    }

    /** Infer a human-readable format name from the file extension of [path]. */
    private fun inferFormatFromPath(path: String): String {
        val lower = path.lowercase()
        return when {
            lower.endsWith(".flac") -> "FLAC"
            lower.endsWith(".opus") -> "OGG/Opus"
            lower.endsWith(".ogg") -> "OGG"
            lower.endsWith(".mp3") -> "MP3"
            lower.endsWith(".m4a") || lower.endsWith(".mp4") || lower.endsWith(".alac") -> "MP4/ALAC"
            lower.endsWith(".aac") -> "AAC"
            lower.endsWith(".wav") -> "WAV"
            else -> "Unknown"
        }
    }

    /** Convert a media3 [C] PCM encoding constant to a bit depth (0 if unknown/float). */
    private fun encodingToBits(encoding: Int): Int = when (encoding) {
        C.ENCODING_PCM_8BIT -> 8
        C.ENCODING_PCM_16BIT -> 16
        C.ENCODING_PCM_24BIT -> 24
        C.ENCODING_PCM_32BIT -> 32
        C.ENCODING_PCM_FLOAT -> 32  // Float is stored as 32 bits
        else -> 0
    }

    /** Format a sample rate as a human-readable kHz string. */
    private fun formatRate(hz: Int): String {
        val khz = hz / 1000.0
        return if (khz == khz.toLong().toDouble()) "${khz.toLong()} kHz"
        else "${"%.1f".format(khz)} kHz"
    }

    companion object {
        private const val TAG = "UsbAudioSink"

        /**
         * Wraps a [LoadControl] to suppress ExoPlayer loading when the native
         * FLAC engine is decoding directly to USB. Call BEFORE [ExoPlayer.Builder.build].
         *
         * @param delegate       Your app's LoadControl (e.g., DefaultLoadControl).
         * @param isEngineActive Lambda returning true when native engine is active.
         *                       Typical: `{ usbSink?.isNativeEngineActive == true }`
         */
        @JvmStatic
        @OptIn(UnstableApi::class)
        fun wrapLoadControl(
            delegate: LoadControl,
            isEngineActive: () -> Boolean
        ): LoadControl = NativeEngineAwareLoadControl(delegate, isEngineActive)
    }
}
