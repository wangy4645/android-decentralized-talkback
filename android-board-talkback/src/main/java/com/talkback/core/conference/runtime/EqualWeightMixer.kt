package com.talkback.core.conference.runtime

import kotlin.math.abs
import kotlin.math.min

/**
 * Equal-weight linear sum + PeakProtection (algorithm implementation-defined).
 * C-E2B04-03: every emitted MixedBlock peak abs <= 0.89 FS.
 * MUST NOT mutate Top-K / Source authority.
 */
object EqualWeightMixer {
    fun mix(participants: List<PcmFrame>): MixedBlock {
        require(participants.size <= MediaMixConstants.MIX_MAX_SOURCES) {
            "MixMaxSources=${MediaMixConstants.MIX_MAX_SOURCES}"
        }
        if (participants.isEmpty()) {
            return MixedBlock(shortArrayOf(), mixParticipantCount = 0, peakAbsFs = 0.0)
        }
        val len = participants.minOf { it.samples.size }
        require(len > 0)
        val n = participants.size
        val summed = FloatArray(len)
        for (p in participants) {
            for (i in 0 until len) {
                summed[i] += p.samples[i].toFloat()
            }
        }
        // Equal-weight: divide by N, then peak-protect to 0.89 FS if needed.
        var peak = 0f
        for (i in 0 until len) {
            summed[i] /= n.toFloat()
            peak = maxOf(peak, abs(summed[i]))
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
            outPeak = maxOf(outPeak, abs(v).toDouble() / MediaMixConstants.S16_FULL_SCALE)
        }
        // Hard clamp invariant (floating error guard).
        val maxAllowed = MediaMixConstants.OUTPUT_PEAK_ABS_FS
        if (outPeak > maxAllowed) {
            val fix = (maxAllowed / outPeak).toFloat()
            outPeak = 0.0
            for (i in 0 until len) {
                val v = (out[i] * fix).toInt().coerceIn(-32768, 32767)
                out[i] = v.toShort()
                outPeak = maxOf(outPeak, abs(v).toDouble() / MediaMixConstants.S16_FULL_SCALE)
            }
        }
        return MixedBlock(
            samples = out,
            mixParticipantCount = n,
            peakAbsFs = min(outPeak, MediaMixConstants.OUTPUT_PEAK_ABS_FS),
        )
    }
}
