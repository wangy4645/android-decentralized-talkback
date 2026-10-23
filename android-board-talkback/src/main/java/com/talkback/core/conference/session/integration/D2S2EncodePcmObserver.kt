package com.talkback.core.conference.session.integration

import com.talkback.core.conference.aqc.PcmLevelMetrics
import com.talkback.core.conference.runtime.OpusCodecConstants
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.sqrt

/**
 * D2 S2: per-frame encode-authority PCM stats, flushed once per wall second (observe only).
 */
object D2S2EncodePcmObserver {
    private const val SPEECH_RMS_DBFS = -45.0

    private data class SecondAcc(
        val epochSecond: Long,
        var frameCount: Int = 0,
        var speechFrameCount: Int = 0,
        var sumSquares: Double = 0.0,
        var maxRmsDbfs: Double = Double.NEGATIVE_INFINITY,
        val speechRmsDbfs: MutableList<Double> = ArrayList(),
    )

    private val accBySession = ConcurrentHashMap<String, SecondAcc>()

    fun onEncodePcmFrame(
        sessionId: String,
        moduleId: String,
        pcmForEncode: ShortArray,
    ) {
        if (!D2SendChainRuntime.captureChainObserveEnabled) return
        if (pcmForEncode.size != OpusCodecConstants.FRAME_SAMPLES_20MS) return
        val sec = System.currentTimeMillis() / 1000L
        val existing = accBySession[sessionId]
        if (existing != null && existing.epochSecond != sec) {
            emit(sessionId, moduleId, existing)
            accBySession.remove(sessionId)
        }
        val acc =
            accBySession.compute(sessionId) { _, cur ->
                if (cur == null || cur.epochSecond != sec) SecondAcc(sec) else cur
            } ?: return
        val rms = PcmLevelMetrics.rmsDbfs(pcmForEncode)
        acc.frameCount++
        if (rms.isFinite()) {
            for (s in pcmForEncode) {
                acc.sumSquares += (s * s).toDouble()
            }
            if (rms > acc.maxRmsDbfs) acc.maxRmsDbfs = rms
            if (rms > SPEECH_RMS_DBFS) {
                acc.speechFrameCount++
                acc.speechRmsDbfs.add(rms)
            }
        }
    }

    fun clearSession(sessionId: String) {
        accBySession.remove(sessionId)
    }

    private fun emit(
        sessionId: String,
        moduleId: String,
        acc: SecondAcc,
    ) {
        val p90 =
            if (acc.speechRmsDbfs.isEmpty()) {
                Double.NaN
            } else {
                val sorted = acc.speechRmsDbfs.sorted()
                sorted[((sorted.size - 1) * 0.9).toInt().coerceIn(0, sorted.lastIndex)]
            }
        val sampleCount = acc.frameCount * OpusCodecConstants.FRAME_SAMPLES_20MS
        val meanRmsDbfs =
            if (sampleCount > 0 && acc.sumSquares > 0) {
                val meanSq = acc.sumSquares / sampleCount
                20.0 * kotlin.math.log10(sqrt(meanSq) / 32767.0)
            } else {
                Double.NaN
            }
        Profile01ShadowRuntimeObservability.logD2S2EncodeSecond(
            sessionId = sessionId,
            moduleId = moduleId,
            epochSecond = acc.epochSecond,
            frameCount = acc.frameCount,
            speechFrameCount = acc.speechFrameCount,
            maxRmsDbfs = acc.maxRmsDbfs,
            p90SpeechRmsDbfs = p90,
            meanRmsDbfs = meanRmsDbfs,
            txPath = D2TxPathTelemetry.lastForwardedPath,
        )
    }
}
