package com.talkback.appprod.ui

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator

/**
 * Two staggered outward ripples while local PTT is transmitting (TALK state).
 * Idle / holding-without-floor / remote speakers: caller keeps this stopped.
 */
class PttTransmitRippleAnimator(
    private val ripple1: View,
    private val ripple2: View,
) {
    private var active = false
    private var runningSet: AnimatorSet? = null

    fun setActive(transmitting: Boolean) {
        if (transmitting == active) return
        active = transmitting
        if (transmitting) start() else stop()
    }

    fun stop() {
        active = false
        runningSet?.cancel()
        runningSet = null
        ripple1.animate().cancel()
        ripple2.animate().cancel()
        ripple1.visibility = View.GONE
        ripple2.visibility = View.GONE
        resetView(ripple1)
        resetView(ripple2)
    }

    private fun start() {
        runningSet?.cancel()
        ripple1.visibility = View.VISIBLE
        ripple2.visibility = View.VISIBLE
        resetView(ripple1)
        resetView(ripple2)
        ripple1.alpha = START_ALPHA
        ripple2.alpha = START_ALPHA
        val run = {
            preparePivot(ripple1)
            preparePivot(ripple2)
            runningSet = AnimatorSet().apply {
                playTogether(
                    buildRippleAnimator(ripple1, startDelayMs = 0L),
                    buildRippleAnimator(ripple2, startDelayMs = STAGGER_MS),
                )
                start()
            }
        }
        if (ripple1.width > 0) {
            run()
        } else {
            ripple1.post(run)
        }
    }

    private fun preparePivot(view: View) {
        view.pivotX = view.width / 2f
        view.pivotY = view.height / 2f
    }

    private fun buildRippleAnimator(view: View, startDelayMs: Long): AnimatorSet {
        val scaleX = ObjectAnimator.ofFloat(view, View.SCALE_X, 1f, END_SCALE).apply {
            duration = DURATION_MS
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = DecelerateInterpolator(1.6f)
            this.startDelay = startDelayMs
        }
        val scaleY = ObjectAnimator.ofFloat(view, View.SCALE_Y, 1f, END_SCALE).apply {
            duration = DURATION_MS
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = DecelerateInterpolator(1.6f)
            this.startDelay = startDelayMs
        }
        val alpha = ObjectAnimator.ofFloat(view, View.ALPHA, START_ALPHA, 0f).apply {
            duration = DURATION_MS
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = AccelerateInterpolator(1.3f)
            this.startDelay = startDelayMs
        }
        return AnimatorSet().apply {
            playTogether(scaleX, scaleY, alpha)
        }
    }

    private fun resetView(view: View) {
        view.scaleX = 1f
        view.scaleY = 1f
        view.alpha = 0f
    }

    companion object {
        private const val DURATION_MS = 2200L
        private const val STAGGER_MS = 1100L
        private const val START_ALPHA = 0.5f
        /** 176dp button → ~238dp ring, stays inside static radar rings */
        private const val END_SCALE = 1.35f
    }
}
