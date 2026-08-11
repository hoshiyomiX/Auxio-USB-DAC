/*
 * Copyright (c) 2026 Auxio Project
 * AudioInfoOverlay.kt is part of Auxio.
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
 
package org.oxycblt.auxio.playback.ui

import android.content.Context
import android.content.res.ColorStateList
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.MotionEvent
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.withStyledAttributes
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import org.oxycblt.auxio.R
import org.oxycblt.auxio.playback.AudioInfo
import org.oxycblt.auxio.ui.UISettings

/**
 * Overlay rendered on top of the album art that shows the current audio pipeline state (decoder,
 * format, sample rate, bit-perfect status, etc.).
 *
 * Mirrors the visual pattern of [org.oxycblt.auxio.playback.ui.stepper.StepperOverlay]: same 1:1
 * aspect-ratio sibling of [org.oxycblt.auxio.image.CoverView], same rounded-corner clipping via
 * [MaterialShapeDrawable] that respects [UISettings.roundMode]. Does not consume touch events —
 * single-tap-to-toggle is handled by the parent
 * [org.oxycblt.auxio.playback.ui.swiper.CoverViewHolder] via the StepperOverlay's
 * [org.oxycblt.auxio.playback.ui.stepper.StepperOverlay.onSingleTap] callback.
 *
 * Visibility is driven by [org.oxycblt.auxio.playback.PlaybackViewModel.overlayVisible]: when
 * false, the overlay's child views are hidden (alpha = 0) but the overlay itself remains attached
 * so that re-showing is instant and the StepperOverlay's single-tap handling continues to work.
 */
@AndroidEntryPoint
class AudioInfoOverlay(context: Context, attrs: AttributeSet?) : ConstraintLayout(context, attrs) {

    @Inject lateinit var uiSettings: UISettings

    private val decoderInfoValue: TextView
    private val musicFormatValue: TextView
    private val musicResolutionValue: TextView
    private val engineUsedValue: TextView
    private val resamplerStatusValue: TextView
    private val passthroughStatusValue: TextView
    private val outputChannelValue: TextView
    private val samplingInfoValue: TextView
    private val bitPerfectInfoValue: TextView
    private val audioBitInfoValue: TextView

    init {
        LayoutInflater.from(context).inflate(R.layout.audio_info_overlay, this, true)

        // Set up shape appearance based on UISettings similar to CoverView/StepperOverlay
        context.withStyledAttributes(attrs, R.styleable.AudioInfoOverlay) {
            val shapeAppearanceRes =
                getResourceId(
                    R.styleable.AudioInfoOverlay_shapeAppearance,
                    com.google.android.material.R.style.ShapeAppearance_Material3_Corner_Medium,
                )

            background =
                MaterialShapeDrawable().apply {
                    shapeAppearanceModel =
                        if (uiSettings.roundMode) {
                            ShapeAppearanceModel.builder(context, shapeAppearanceRes, -1).build()
                        } else {
                            ShapeAppearanceModel.builder().build()
                        }
                    // Semi-transparent dark scrim so white text remains legible over any album art
                    fillColor = ColorStateList.valueOf(0xCC000000.toInt())
                }
        }

        decoderInfoValue = findViewById(R.id.audio_info_decoder_value)
        musicFormatValue = findViewById(R.id.audio_info_format_value)
        musicResolutionValue = findViewById(R.id.audio_info_resolution_value)
        engineUsedValue = findViewById(R.id.audio_info_engine_value)
        resamplerStatusValue = findViewById(R.id.audio_info_resampler_value)
        passthroughStatusValue = findViewById(R.id.audio_info_passthrough_value)
        outputChannelValue = findViewById(R.id.audio_info_channel_value)
        samplingInfoValue = findViewById(R.id.audio_info_sampling_value)
        bitPerfectInfoValue = findViewById(R.id.audio_info_bitperfect_value)
        audioBitInfoValue = findViewById(R.id.audio_info_bitrate_value)

        // Apply shape clipping so the scrim respects rounded corners
        clipToOutline = true

        // Do not consume touch events — single-tap-to-toggle is handled by StepperOverlay
        // below this overlay in the Z-order. By returning false from dispatchTouchEvent,
        // the parent ConstraintLayout will dispatch the touch to the next sibling (StepperOverlay),
        // which uses a GestureDetector to distinguish single-tap (toggle overlay) from
        // double-tap (seek ±10s).
        isClickable = false
        isFocusable = false
    }

    /**
     * Never dispatch touch events to children (ScrollView) and never consume them. This forces all
     * touches to fall through to the [StepperOverlay] sibling below, which handles single-tap
     * (toggle overlay) and double-tap (seek) gestures.
     */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean = false

    /**
     * Bind a new [AudioInfo] snapshot to the overlay. Updates all 9 value TextViews.
     *
     * @param info The new audio info to display. Never null (callers should pass the "empty"
     *   instance from [AudioInfo.from] with a null snapshot when no track is loaded).
     */
    fun bind(info: AudioInfo) {
        decoderInfoValue.text = info.decoderInfo
        musicFormatValue.text = info.musicFormat
        musicResolutionValue.text = info.musicResolution
        engineUsedValue.text = info.engineUsed
        resamplerStatusValue.text = info.resamplerStatus
        passthroughStatusValue.text = info.passthroughStatus
        outputChannelValue.text = info.outputChannel
        samplingInfoValue.text = info.samplingInfo
        bitPerfectInfoValue.text = info.bitPerfectInfo
        audioBitInfoValue.text = info.audioBitInfo
    }
}
