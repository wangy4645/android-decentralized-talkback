package com.talkback.appprod.ui

import android.content.res.Resources
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.DimenRes
import com.talkback.appprod.R
import kotlin.math.roundToInt

/**
 * Scales fixed-dp PTT visuals from a 360dp-wide design baseline so narrow phones
 * keep proportions without clipping. Wide screens stay at 1.0 (no upscaling).
 */
object TalkResponsiveScale {
    private const val SCREEN_PADDING_DP = 32f
    private const val DESIGN_SCREEN_WIDTH_DP = 360f
    private const val MIN_SCALE = 0.76f
    private const val MAX_SCALE = 1f

    private val radarRingDimens = intArrayOf(
        R.dimen.ptt_radar_ring_6,
        R.dimen.ptt_radar_ring_5,
        R.dimen.ptt_radar_ring_4,
        R.dimen.ptt_radar_ring_3,
        R.dimen.ptt_radar_ring_2,
        R.dimen.ptt_radar_ring_1
    )

    fun scaleFactor(contentWidthDp: Float): Float {
        val designContent = DESIGN_SCREEN_WIDTH_DP - SCREEN_PADDING_DP
        val available = contentWidthDp - SCREEN_PADDING_DP
        return (available / designContent).coerceIn(MIN_SCALE, MAX_SCALE)
    }

    fun apply(root: View, scale: Float, resources: Resources) {
        fun px(@DimenRes dimenId: Int): Int =
            (resources.getDimension(dimenId) * scale).roundToInt()

        root.findViewById<ViewGroup>(R.id.layoutPttArea)?.let { area ->
            area.layoutParams = area.layoutParams.apply {
                height = px(R.dimen.ptt_area_height)
            }
            area.findViewById<ViewGroup>(R.id.layoutPttRadarRings)?.let { rings ->
                for (i in 0 until rings.childCount.coerceAtMost(radarRingDimens.size)) {
                    val ring = rings.getChildAt(i)
                    val size = px(radarRingDimens[i])
                    ring.layoutParams = ring.layoutParams.apply {
                        width = size
                        height = size
                    }
                }
            }
            val rippleSize = px(R.dimen.ptt_button_size)
            listOf(R.id.pttTransmitRipple1, R.id.pttTransmitRipple2).forEach { rippleId ->
                area.findViewById<View>(rippleId)?.let { ripple ->
                    ripple.layoutParams = ripple.layoutParams.apply {
                        width = rippleSize
                        height = rippleSize
                    }
                }
            }
        }

        root.findViewById<ViewGroup>(R.id.layoutPttCenter)?.let { center ->
            center.findViewById<FrameLayout>(R.id.btnPtt)?.let { btn ->
                val size = px(R.dimen.ptt_button_size)
                btn.layoutParams = btn.layoutParams.apply {
                    width = size
                    height = size
                }
            }
        }

        listOf(R.id.btnModePtt, R.id.btnModeMeeting).forEach { tabId ->
            root.findViewById<View>(tabId)?.let { tab ->
                tab.layoutParams = tab.layoutParams.apply {
                    width = px(R.dimen.mode_pill_tab_width)
                    height = px(R.dimen.mode_pill_tab_height)
                }
            }
        }

        root.findViewById<View>(R.id.barPttLockSlide)?.let { bar ->
            bar.layoutParams = bar.layoutParams.apply {
                width = px(R.dimen.ptt_lock_bar_width)
                height = px(R.dimen.ptt_lock_bar_height)
            }
        }

        root.findViewById<View>(R.id.viewPttLockThumb)?.let { thumb ->
            val size = px(R.dimen.ptt_lock_thumb_size)
            thumb.layoutParams = thumb.layoutParams.apply {
                width = size
                height = size
            }
        }

        val mic = root.findViewById<ImageView>(R.id.imgPttMic)
        val label = root.findViewById<TextView>(R.id.txtPttLabel)
        val hint = root.findViewById<TextView>(R.id.txtPttHint)
        if (mic != null && label != null && hint != null) {
            applyPttContent(mic, label, hint, scale, resources)
            hint.maxWidth = px(R.dimen.ptt_hint_max_width)
        }
    }

    private fun applyPttContent(
        mic: ImageView,
        label: TextView,
        hint: TextView,
        scale: Float,
        resources: Resources
    ) {
        val micPx = (resources.getDimension(R.dimen.ptt_mic_icon_size) * scale).roundToInt()
        mic.layoutParams = mic.layoutParams.apply {
            width = micPx
            height = micPx
        }
        label.setTextSize(
            TypedValue.COMPLEX_UNIT_PX,
            resources.getDimension(R.dimen.ptt_label_text_size) * scale
        )
        hint.setTextSize(
            TypedValue.COMPLEX_UNIT_PX,
            resources.getDimension(R.dimen.ptt_hint_text_size) * scale
        )
    }
}
