package com.talkback.core.conference.session.integration

import com.talkback.core.conference.aqc.PcmLevelMetrics
import com.talkback.core.conference.aqc.TxProcessingChain
import com.talkback.core.conference.runtime.OpusCodecConstants

/**
 * D2 S1→S2 seam: identity by default; optional conference-only peak headroom (C3, not AGC).
 */
data class D2SendChainFrameResult(
    val samples: ShortArray,
    val inputRmsDbfs: Double,
    val outputRmsDbfs: Double,
    val headroomApplied: Boolean,
    val limiterScale: Float,
)

object D2SendChainProcessor {
    fun processS1ToS2(pcmS1: ShortArray): D2SendChainFrameResult {
        require(pcmS1.size == OpusCodecConstants.FRAME_SAMPLES_20MS)
        val inputRms = PcmLevelMetrics.rmsDbfs(pcmS1)
        if (!D2SendChainRuntime.sendHeadroomEnabled) {
            return D2SendChainFrameResult(
                samples = pcmS1,
                inputRmsDbfs = inputRms,
                outputRmsDbfs = inputRms,
                headroomApplied = false,
                limiterScale = 1f,
            )
        }
        val processed = TxProcessingChain.process20ms(pcmS1, TxProcessingChain.TapPoint.POST_APM)
        return D2SendChainFrameResult(
            samples = processed.samples,
            inputRmsDbfs = processed.metrics.inputRmsDbfs,
            outputRmsDbfs = processed.metrics.outputRmsDbfs,
            headroomApplied = processed.metrics.limiterApplied,
            limiterScale = processed.metrics.limiterScale,
        )
    }
}
