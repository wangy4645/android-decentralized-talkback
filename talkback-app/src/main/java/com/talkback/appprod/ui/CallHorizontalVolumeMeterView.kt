package com.talkback.appprod.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.talkback.appprod.R
import kotlin.math.roundToInt

/**
 * Broadcast-style LED VU: fixed color zones by bar position (green → yellow → orange → red).
 * Only bars up to the current level are lit; unlit bars stay dim.
 */
class CallHorizontalVolumeMeterView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val segmentCount = 20
    private var level = 0f

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val segmentRect = RectF()
    private val cornerRadius get() = 2f * resources.displayMetrics.density
    private val segmentGap get() = 2.5f * resources.displayMetrics.density

    private val colorInactive = ContextCompat.getColor(context, R.color.tb_volume_meter_inactive)
    private val colorGreenLight = ContextCompat.getColor(context, R.color.tb_volume_meter_light)
    private val colorGreen = ContextCompat.getColor(context, R.color.tb_volume_meter_mid)
    private val colorGreenDeep = ContextCompat.getColor(context, R.color.tb_volume_meter_deep)
    private val colorYellow = ContextCompat.getColor(context, R.color.tb_volume_meter_yellow)
    private val colorOrange = ContextCompat.getColor(context, R.color.tb_volume_meter_orange)
    private val colorRed = ContextCompat.getColor(context, R.color.tb_volume_meter_red)

    fun setLevel(normalized: Float) {
        val next = normalized.coerceIn(0f, 1f)
        if (next == level) return
        level = next
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return

        val litCount = (level * segmentCount).roundToInt().coerceIn(0, segmentCount)
        val segmentWidth = (width - segmentGap * (segmentCount - 1)) / segmentCount

        for (index in 0 until segmentCount) {
            val left = index * (segmentWidth + segmentGap)
            segmentRect.set(left, 0f, left + segmentWidth, height.toFloat())

            if (index < litCount && level > 0.02f) {
                val position = index / (segmentCount - 1).toFloat()
                val color = colorForPosition(position)
                barPaint.color = color

                val isPeak = position >= 0.84f && litCount > 4
                if (isPeak) {
                    glowPaint.color = Color.argb(80, Color.red(color), Color.green(color), Color.blue(color))
                    val inset = -1.5f * resources.displayMetrics.density
                    canvas.drawRoundRect(
                        segmentRect.left + inset,
                        segmentRect.top + inset,
                        segmentRect.right - inset,
                        segmentRect.bottom - inset,
                        cornerRadius * 1.6f,
                        cornerRadius * 1.6f,
                        glowPaint
                    )
                }
                canvas.drawRoundRect(segmentRect, cornerRadius, cornerRadius, barPaint)
            } else {
                barPaint.color = colorInactive
                canvas.drawRoundRect(segmentRect, cornerRadius, cornerRadius, barPaint)
            }
        }
    }

    /** Fixed zone colors by position along the strip (industry LED meter convention). */
    private fun colorForPosition(position: Float): Int {
        return when {
            position < 0.50f -> {
                val t = position / 0.50f
                blend(colorGreenLight, colorGreen, t * 0.7f + 0.3f * t)
            }
            position < 0.54f -> blend(colorGreen, colorGreenDeep, (position - 0.50f) / 0.04f)
            position < 0.72f -> blend(colorGreenDeep, colorYellow, (position - 0.54f) / 0.18f)
            position < 0.86f -> blend(colorYellow, colorOrange, (position - 0.72f) / 0.14f)
            else -> blend(colorOrange, colorRed, (position - 0.86f) / 0.14f)
        }
    }

    private fun blend(from: Int, to: Int, fraction: Float): Int {
        val t = fraction.coerceIn(0f, 1f)
        val a = (Color.alpha(from) + (Color.alpha(to) - Color.alpha(from)) * t).roundToInt()
        val r = (Color.red(from) + (Color.red(to) - Color.red(from)) * t).roundToInt()
        val g = (Color.green(from) + (Color.green(to) - Color.green(from)) * t).roundToInt()
        val b = (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * t).roundToInt()
        return Color.argb(a, r, g, b)
    }
}
