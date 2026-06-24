package com.talkback.appprod.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.talkback.appprod.R
import kotlin.math.roundToInt

/**
 * Vertical segmented VU-style level meter (reference meeting speaker UI).
 * Level is driven by WebRTC audioLevel stats, not a decorative animation.
 */
class MeetingVolumeMeterView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val segmentCount = 16
    private var level = 0f

    private val activePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.tb_success)
        style = Paint.Style.FILL
    }
    private val inactivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.tb_volume_meter_inactive)
        style = Paint.Style.FILL
    }

    private val segmentRect = RectF()
    private val cornerRadius get() = 1.5f * resources.displayMetrics.density
    private val segmentGap get() = 2f * resources.displayMetrics.density

    fun setLevel(normalized: Float) {
        val next = normalized.coerceIn(0f, 1f)
        if (next == level) return
        level = next
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return

        val totalGap = segmentGap * (segmentCount - 1)
        val segmentHeight = (height - totalGap) / segmentCount
        val activeSegments = (level * segmentCount).roundToInt()

        for (index in 0 until segmentCount) {
            val fromBottom = segmentCount - 1 - index
            val top = fromBottom * (segmentHeight + segmentGap)
            segmentRect.set(0f, top, width.toFloat(), top + segmentHeight)
            val paint = if (index < activeSegments) activePaint else inactivePaint
            canvas.drawRoundRect(segmentRect, cornerRadius, cornerRadius, paint)
        }
    }
}
