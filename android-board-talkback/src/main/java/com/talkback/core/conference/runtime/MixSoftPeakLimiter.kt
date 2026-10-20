package com.talkback.core.conference.runtime

import kotlin.math.abs
import kotlin.math.min

/**
 * Block-rate soft peak limiter for multicast mix output (20 ms @ 48 kHz).
 *
 * Attack/release smooth gain reductions across blocks to avoid hard block-scale pumping.
 */
class MixSoftPeakLimiter(
    private val ceilingAbsFs: Double = MediaMixConstants.OUTPUT_PEAK_ABS_FS,
    /** Fraction of block interval to move toward a lower target gain (limiter engaging). */
    private val attackCoef: Float = 0.45f,
    /** Fraction of block interval to recover gain after peaks subside. */
    private val releaseCoef: Float = 0.12f,
) {
    private var smoothedGain: Float = 1f

    fun reset() {
        smoothedGain = 1f
    }

    data class Result(
        val preLimitPeakAbsFs: Double,
        val appliedGain: Float,
        val limiterActive: Boolean,
    )

    fun limitSummedBlock(summed: FloatArray, len: Int): Result {
        var peakLinear = 0f
        for (i in 0 until len) {
            peakLinear = maxOf(peakLinear, abs(summed[i]))
        }
        val ceilingLinear = (ceilingAbsFs * MediaMixConstants.S16_FULL_SCALE).toFloat()
        val peakAbsFs: Double =
            if (peakLinear > 0f) {
                peakLinear.toDouble() / MediaMixConstants.S16_FULL_SCALE.toDouble()
            } else {
                0.0
            }
        val targetGain =
            if (peakLinear > ceilingLinear && peakLinear > 0f) {
                ceilingLinear / peakLinear
            } else {
                1f
            }
        val coef = if (targetGain < smoothedGain) attackCoef else releaseCoef
        smoothedGain += coef * (targetGain - smoothedGain)
        val gain = smoothedGain.coerceIn(0f, 1f)
        val active = gain < 0.999f || targetGain < 0.999f
        for (i in 0 until len) {
            summed[i] *= gain
        }
        return Result(
            preLimitPeakAbsFs = min(peakAbsFs, 1.0),
            appliedGain = gain,
            limiterActive = active,
        )
    }
}
