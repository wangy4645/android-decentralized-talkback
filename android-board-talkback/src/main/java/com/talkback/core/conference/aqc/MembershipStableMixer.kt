package com.talkback.core.conference.aqc

import com.talkback.core.conference.runtime.MediaMixConstants
import com.talkback.core.conference.runtime.MixedBlock
import com.talkback.core.conference.runtime.PcmFrame
import kotlin.math.abs
import kotlin.math.max

/**
 * Mix with a stable membership divisor (Top-K seat count), not the number of frames decoded this tick.
 */
object MembershipStableMixer {
    fun mix20ms(
        sourceFrames: List<PcmFrame>,
        membershipDivisor: Int,
    ): MixedBlock {
        require(membershipDivisor >= 1)
        if (sourceFrames.isEmpty()) {
            return MixedBlock(shortArrayOf(), mixParticipantCount = 0, peakAbsFs = 0.0)
        }
        val len = sourceFrames.minOf { it.samples.size }
        require(len > 0)
        val divisor = max(1, membershipDivisor).coerceAtMost(MediaMixConstants.MIX_MAX_SOURCES)
        val summed = FloatArray(len)
        for (p in sourceFrames) {
            for (i in 0 until len) {
                summed[i] += p.samples[i].toFloat()
            }
        }
        var peak = 0f
        for (i in 0 until len) {
            summed[i] /= divisor.toFloat()
            peak = max(peak, abs(summed[i]))
        }
        val ceiling = (MediaMixConstants.OUTPUT_PEAK_ABS_FS * MediaMixConstants.S16_FULL_SCALE).toFloat()
        val scale =
            if (peak > ceiling && peak > 0f) {
                ceiling / peak
            } else {
                1f
            }
        val out = ShortArray(len)
        var outPeak = 0.0
        for (i in 0 until len) {
            val v = (summed[i] * scale).toInt().coerceIn(-32768, 32767)
            out[i] = v.toShort()
            outPeak = max(outPeak, abs(v).toDouble() / MediaMixConstants.S16_FULL_SCALE)
        }
        return MixedBlock(
            samples = out,
            mixParticipantCount = sourceFrames.size,
            peakAbsFs = outPeak,
        )
    }
}
