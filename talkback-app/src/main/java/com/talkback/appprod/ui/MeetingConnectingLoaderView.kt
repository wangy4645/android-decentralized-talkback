package com.talkback.appprod.ui

import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import com.talkback.appprod.R
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Meeting connecting indicator: rotating thin yellow arc on the outer ring,
 * with a static inner ring of white dots (reference design).
 */
class MeetingConnectingLoaderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = TRACK_STROKE_DP * density
        color = ContextCompat.getColor(context, R.color.tb_divider)
    }

    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = ARC_STROKE_DP * density
        strokeCap = Paint.Cap.ROUND
        color = ContextCompat.getColor(context, R.color.tb_meeting_connecting)
    }

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.tb_white)
    }

    private val arcBounds = RectF()
    private var spinAnimator: ObjectAnimator? = null

    var arcRotationDegrees: Float = 0f
        set(value) {
            field = value
            invalidate()
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = (DEFAULT_SIZE_DP * density).toInt()
        val resolved = resolveSize(size, widthMeasureSpec)
        setMeasuredDimension(resolved, resolved)
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val maxRadius = min(width, height) / 2f - arcPaint.strokeWidth
        val outerRadius = maxRadius - OUTER_INSET_DP * density

        canvas.drawCircle(cx, cy, outerRadius, trackPaint)

        arcBounds.set(
            cx - outerRadius,
            cy - outerRadius,
            cx + outerRadius,
            cy + outerRadius
        )
        canvas.save()
        canvas.rotate(arcRotationDegrees, cx, cy)
        canvas.drawArc(arcBounds, ARC_START_DEGREES, ARC_SWEEP_DEGREES, false, arcPaint)
        canvas.restore()

        val dotRingRadius = outerRadius * DOT_RING_RADIUS_RATIO
        val dotRadius = DOT_RADIUS_DP * density
        for (index in 0 until DOT_COUNT) {
            val angleRadians = Math.toRadians((index * (360.0 / DOT_COUNT)) - 90.0)
            val dotX = cx + cos(angleRadians).toFloat() * dotRingRadius
            val dotY = cy + sin(angleRadians).toFloat() * dotRingRadius
            canvas.drawCircle(dotX, dotY, dotRadius, dotPaint)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startSpin()
    }

    override fun onDetachedFromWindow() {
        stopSpin()
        super.onDetachedFromWindow()
    }

    private fun startSpin() {
        if (spinAnimator?.isRunning == true) return
        spinAnimator = ObjectAnimator.ofFloat(this, "arcRotationDegrees", 0f, 360f).apply {
            duration = SPIN_DURATION_MS
            interpolator = LinearInterpolator()
            repeatCount = ObjectAnimator.INFINITE
            start()
        }
    }

    private fun stopSpin() {
        spinAnimator?.cancel()
        spinAnimator = null
    }

    companion object {
        private const val DEFAULT_SIZE_DP = 168f
        private const val TRACK_STROKE_DP = 1.5f
        private const val ARC_STROKE_DP = 2f
        private const val OUTER_INSET_DP = 2f
        private const val ARC_START_DEGREES = -90f
        private const val ARC_SWEEP_DEGREES = 78f
        private const val DOT_COUNT = 8
        private const val DOT_RADIUS_DP = 3.5f
        /** Inner dot ring radius as a fraction of the outer track radius (~52% gap per reference). */
        private const val DOT_RING_RADIUS_RATIO = 0.52f
        private const val SPIN_DURATION_MS = 1_100L
    }
}
