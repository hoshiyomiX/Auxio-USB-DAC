/*
 * Copyright (c) 2026 Auxio Project
 * CoverPagerAdapter.kt is part of Auxio.
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
 
package org.oxycblt.auxio.playback.ui.swiper

import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import org.oxycblt.auxio.databinding.ItemCoverBinding
import org.oxycblt.auxio.list.adapter.FlexibleListAdapter
import org.oxycblt.auxio.list.adapter.SimpleDiffCallback
import org.oxycblt.auxio.playback.AudioInfo
import org.oxycblt.auxio.playback.ui.stepper.StepperOverlay
import org.oxycblt.auxio.util.inflater
import org.oxycblt.musikr.Song

/**
 * A [FlexibleListAdapter] that hosts [CoverViewHolder]s containing a [Song]'s cover, step gesture
 * overlays, and audio info overlay.
 *
 * @param listener The [StepperOverlay.Listener] that step gesture events will be forwarded to
 * @param onCoverSingleTap Callback invoked when the user single-taps the album art area. Used by
 *   [org.oxycblt.auxio.playback.PlaybackPanelFragment] to toggle the audio info overlay visibility.
 * @author Alexander Capehart (OxygenCobalt)
 */
class CoverPagerAdapter(
    private val listener: StepperOverlay.Listener,
    private val onCoverSingleTap: () -> Unit,
) : FlexibleListAdapter<Song, CoverViewHolder>(CoverViewHolder.DIFF_CALLBACK) {

    /** Current audio info to display on all bound holders. Updated via [updateAudioInfo]. */
    private var currentAudioInfo: AudioInfo? = null

    /** Current overlay visibility state. Updated via [updateOverlayVisible]. */
    private var currentOverlayVisible: Boolean = false

    override fun onCreateViewHolder(parent: ViewGroup, pos: Int) = CoverViewHolder.from(parent)

    override fun onBindViewHolder(viewHolder: CoverViewHolder, pos: Int) {
        viewHolder.bind(
            currentList[pos],
            listener,
            onCoverSingleTap,
            currentAudioInfo,
            currentOverlayVisible,
        )
    }

    /**
     * Update the audio info shown on all currently-bound holders. Called by
     * [org.oxycblt.auxio.playback.PlaybackPanelFragment] when the polled audio info changes (every
     * 500ms).
     */
    fun updateAudioInfo(info: AudioInfo) {
        currentAudioInfo = info
        notifyItemRangeChanged(0, itemCount)
    }

    /**
     * Update the visibility of the audio info overlay on all currently-bound holders. Called by
     * [org.oxycblt.auxio.playback.PlaybackPanelFragment] when the user toggles the overlay.
     */
    fun updateOverlayVisible(visible: Boolean) {
        currentOverlayVisible = visible
        notifyItemRangeChanged(0, itemCount)
    }
}

/**
 * A [RecyclerView.ViewHolder] that displays a [Song]'s cover, step gesture overlays, and audio info
 * overlay.
 *
 * @author Alexander Capehart (OxygenCobalt)
 */
class CoverViewHolder private constructor(private val binding: ItemCoverBinding) :
    RecyclerView.ViewHolder(binding.root) {
    /**
     * Bind new data to this instance.
     *
     * @param song The new [Song] to bind.
     * @param listener An [StepperOverlay.Listener] to bind fast seek interactions to.
     * @param onSingleTap Callback invoked when the user single-taps the album art area (used to
     *   toggle the audio info overlay).
     * @param audioInfo The current [AudioInfo] to display in the overlay, or null if no info has
     *   been loaded yet.
     * @param overlayVisible Whether the audio info overlay should be visible.
     */
    fun bind(
        song: Song,
        listener: StepperOverlay.Listener,
        onSingleTap: () -> Unit,
        audioInfo: AudioInfo?,
        overlayVisible: Boolean,
    ) {
        binding.cover.bind(song)
        binding.coverFastSeekOverlay.listener = listener
        binding.coverFastSeekOverlay.onSingleTap = onSingleTap
        if (audioInfo != null) {
            binding.coverAudioInfoOverlay.bind(audioInfo)
        }
        binding.coverAudioInfoOverlay.visibility = if (overlayVisible) View.VISIBLE else View.GONE
    }

    companion object {
        /**
         * Create a new instance.
         *
         * @param parent The parent to inflate this instance from.
         * @return A new instance.
         */
        fun from(parent: ViewGroup) =
            CoverViewHolder(ItemCoverBinding.inflate(parent.context.inflater, parent, false))

        /** A comparator that can be used with DiffUtil. */
        val DIFF_CALLBACK =
            object : SimpleDiffCallback<Song>() {
                override fun areContentsTheSame(oldItem: Song, newItem: Song) =
                    oldItem.cover == newItem.cover
            }
    }
}
