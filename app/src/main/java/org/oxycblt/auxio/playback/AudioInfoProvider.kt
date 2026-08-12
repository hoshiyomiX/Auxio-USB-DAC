/*
 * Copyright (c) 2026 Auxio Project
 * AudioInfoProvider.kt is part of Auxio.
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
import com.decent.usbaudio.media3.UsbAudioSink
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-wide bridge between the [UsbAudioSink] instance (owned by the playback service) and the
 * UI layer (owned by the activity).
 *
 * [UsbAudioSink] is instantiated inside [ExoPlaybackStateHolder.Factory.create] whenever a new
 * playback session starts, and is destroyed when the session ends. The UI layer (PlaybackViewModel)
 * needs to poll the sink for the audio info overlay but cannot directly access the sink instance.
 * This singleton provides a safe indirection:
 * 1. [ExoPlaybackStateHolder.Factory] calls [bind] when a new sink is created (or with null when no
 *    sink is created because USB DAC mode is off).
 * 2. [PlaybackViewModel] calls [snapshot] on a 500ms polling loop to fetch the latest
 *    [AudioInfoSnapshot] for display.
 *
 * The reference is held weakly via a [Volatile] var — when the playback service is destroyed,
 * [bind] is called with null to clear the reference. This avoids leaking the sink across sessions.
 *
 * Thread-safety: [bind] and [snapshot] are both safe to call from any thread. The underlying
 * [UsbAudioSink.snapshotAudioInfo] is itself `@Synchronized`.
 */
@Singleton
class AudioInfoProvider @Inject constructor() {
    @Volatile private var sinkRef: UsbAudioSink? = null

    /**
     * Bind (or unbind) the currently active [UsbAudioSink]. Pass null to clear the reference when
     * the playback session ends or when USB DAC mode is disabled.
     *
     * @param sink The active sink, or null.
     */
    fun bind(sink: UsbAudioSink?) {
        sinkRef = sink
    }

    /**
     * Take a snapshot of the current audio pipeline state.
     *
     * @return The latest [AudioInfoSnapshot], or null if no sink is bound (USB DAC mode is off, or
     *   no playback session is active) OR if the sink is bound but the USB pipeline is not actually
     *   active (e.g., bit-perfect mode is enabled but no DAC is connected, so audio is flowing
     *   through Android's default AudioFlinger). In the latter case, a null return causes the UI to
     *   show "Android (AudioFlinger)" instead of misleading fields like "FFmpeg" decoder with blank
     *   engine/device.
     */
    fun snapshot(): AudioInfoSnapshot? = sinkRef?.snapshotAudioInfo()
}
