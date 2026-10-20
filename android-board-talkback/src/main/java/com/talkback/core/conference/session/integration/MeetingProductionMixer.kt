package com.talkback.core.conference.session.integration

import com.talkback.core.conference.runtime.EqualWeightMixer
import com.talkback.core.conference.runtime.MediaMixConstants
import com.talkback.core.conference.runtime.MixBlockDiagnostics
import com.talkback.core.conference.runtime.MixSoftPeakLimiter
import com.talkback.core.conference.runtime.MixedBlock
import com.talkback.core.conference.runtime.PcmFrame
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.min

/**
 * Production multicast mix entry — dispatches average vs sum+soft-limit per
 * [BrightScreenMeetingMixPolicy.productionMixCombineMode].
 */
object MeetingProductionMixer {
    private val limiters = ConcurrentHashMap<String, MixSoftPeakLimiter>()

    fun clearSession(sessionKey: String) {
        limiters.remove(sessionKey)
    }

    fun mix(
        sessionKey: String,
        participants: List<PcmFrame>,
    ): MixedBlock {
        return when (BrightScreenMeetingMixPolicy.productionMixCombineMode) {
            BrightScreenMeetingMixPolicy.MixCombineMode.EQUAL_WEIGHT_AVERAGE ->
                MixOutputMakeupFieldExperiment.applyIfActive(EqualWeightMixer.mix(participants))
            BrightScreenMeetingMixPolicy.MixCombineMode.ACTIVE_SPEECH_EQUAL_WEIGHT ->
                MixOutputMakeupFieldExperiment.applyIfActive(
                    EqualWeightMixer.mixActiveSpeechEqualWeight(participants),
                )
            BrightScreenMeetingMixPolicy.MixCombineMode.LINEAR_SUM_SOFT_LIMIT ->
                mixLinearSumSoftLimit(sessionKey, participants)
        }
    }

    private fun mixLinearSumSoftLimit(
        sessionKey: String,
        participants: List<PcmFrame>,
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
        val summed = FloatArray(len)
        for (p in participants) {
            for (i in 0 until len) {
                summed[i] += p.samples[i].toFloat()
            }
        }
        val limiter = limiters.getOrPut(sessionKey) { MixSoftPeakLimiter() }
        val limitResult = limiter.limitSummedBlock(summed, len)
        val out = ShortArray(len)
        var outPeak = 0.0
        for (i in 0 until len) {
            val v = summed[i].toInt().coerceIn(-32_768, 32_767)
            out[i] = v.toShort()
            outPeak = maxOf(outPeak, abs(v).toDouble() / MediaMixConstants.S16_FULL_SCALE)
        }
        val maxAllowed = MediaMixConstants.OUTPUT_PEAK_ABS_FS
        if (outPeak > maxAllowed) {
            val fix = (maxAllowed / outPeak).toFloat()
            outPeak = 0.0
            for (i in 0 until len) {
                val v = (out[i] * fix).toInt().coerceIn(-32_768, 32_767)
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
                    combineMode = BrightScreenMeetingMixPolicy.MixCombineMode.LINEAR_SUM_SOFT_LIMIT.name,
                    preLimitPeakAbsFs = limitResult.preLimitPeakAbsFs,
                    limiterGain = limitResult.appliedGain.toDouble(),
                    limiterActive = limitResult.limiterActive,
                    mixBlendDivisor = 1,
                    mixActiveSpeechCount = n,
                ),
        )
    }
}
