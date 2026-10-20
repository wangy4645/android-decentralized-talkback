package com.talkback.core.conference.runtime

import com.talkback.core.conference.aqc.PcmLevelMetrics
import kotlin.math.abs
import kotlin.math.min

/**
 * Equal-weight linear sum + PeakProtection (algorithm implementation-defined).
 * C-E2B04-03: every emitted MixedBlock peak abs <= 0.89 FS.
 * MUST NOT mutate Top-K / Source authority.
 */
object EqualWeightMixer {
    /** Aligns with [com.talkback.core.conference.session.integration.D2S2EncodePcmObserver] speech gate. */
    private const val SPEECH_RMS_DBFS = -45.0

    fun mix(participants: List<PcmFrame>): MixedBlock {
        val n = participants.size
        return mixWithDivisor(
            participants,
            divisor = n,
            combineModeLabel = "EQUAL_WEIGHT_AVERAGE",
            activeSpeechCount = n,
        )
    }

    fun mixActiveSpeechEqualWeight(participants: List<PcmFrame>): MixedBlock {
        val active =
            participants.count { frame ->
                val rms = PcmLevelMetrics.rmsDbfs(frame.samples)
                rms.isFinite() && rms > SPEECH_RMS_DBFS
            }
        val divisor = active.coerceAtLeast(1)
        return mixWithDivisor(
            participants,
            divisor = divisor,
            combineModeLabel = "ACTIVE_SPEECH_EQUAL_WEIGHT",
            activeSpeechCount = active,
        )
    }

    private fun mixWithDivisor(
        participants: List<PcmFrame>,
        divisor: Int,
        combineModeLabel: String,
        activeSpeechCount: Int,
    ): MixedBlock {
        require(participants.size <= MediaMixConstants.MIX_MAX_SOURCES) {
            "MixMaxSources=${MediaMixConstants.MIX_MAX_SOURCES}"
        }
        if (participants.isEmpty()) {
            return MixedBlock(shortArrayOf(), mixParticipantCount = 0, peakAbsFs = 0.0)
        }
        val len = participants.minOf { it.samples.size }
        require(len > 0)
        val n = participants.size
        require(divisor >= 1)
        val summed = FloatArray(len)
        for (p in participants) {
            for (i in 0 until len) {
                summed[i] += p.samples[i].toFloat()
            }
        }
        var peak = 0f
        for (i in 0 until len) {
            summed[i] /= divisor.toFloat()
            peak = maxOf(peak, abs(summed[i]))
        }
        val ceiling = (MediaMixConstants.OUTPUT_PEAK_ABS_FS * MediaMixConstants.S16_FULL_SCALE).toFloat()
        val scale =
            if (peak > ceiling && peak > 0f) {
                ceiling / peak
            } else {
                1f
            }
        val preLimitPeakAbsFs =
            if (peak > 0f) {
                (peak / MediaMixConstants.S16_FULL_SCALE).toDouble()
            } else {
                0.0
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
            blockDiagnostics =
                MixBlockDiagnostics(
                    combineMode = combineModeLabel,
                    preLimitPeakAbsFs = min(preLimitPeakAbsFs, 1.0),
                    limiterGain = scale.toDouble(),
                    limiterActive = scale < 0.999f,
                    mixBlendDivisor = divisor,
                    mixActiveSpeechCount = activeSpeechCount,
                ),
        )
    }
}
