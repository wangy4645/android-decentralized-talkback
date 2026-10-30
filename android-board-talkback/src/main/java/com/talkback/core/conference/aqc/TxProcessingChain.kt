package com.talkback.core.conference.aqc

import kotlin.math.abs

/**
 * AQC send path: peak limiter on the authoritative 20 ms frame before encode.
 */
object TxProcessingChain {
    enum class TapPoint {
        PRE_APM,
        POST_APM,
    }

    data class TxMetrics(
        val tapPoint: TapPoint,
        val inputPeakAbs: Int,
        val outputPeakAbs: Int,
        val inputClipSamples: Int,
        val outputClipSamples: Int,
        val inputRmsDbfs: Double,
        val outputRmsDbfs: Double,
        val limiterScale: Float,
        val limiterApplied: Boolean,
    )

    data class ProcessedTxFrame(
        val samples: ShortArray,
        val metrics: TxMetrics,
    )

    fun process20ms(
        captureFrame: ShortArray,
        tapPoint: TapPoint = TapPoint.POST_APM,
    ): ProcessedTxFrame {
        require(captureFrame.size == AqcFrameConstants.FRAME_SAMPLES_20MS)
        val inputPeak = PcmLevelMetrics.peakAbs(captureFrame)
        val ceiling =
            (AqcFrameConstants.OUTPUT_PEAK_ABS_FS * 32767.0).toFloat()
        val scale =
            if (inputPeak > ceiling && inputPeak > 0) {
                ceiling / inputPeak.toFloat()
            } else {
                1f
            }
        val out =
            if (scale == 1f) {
                captureFrame.copyOf()
            } else {
                ShortArray(captureFrame.size) { i ->
                    (captureFrame[i] * scale).toInt().coerceIn(-32768, 32767).toShort()
                }
            }
        val limiterApplied = scale < 1f
        val metrics =
            TxMetrics(
                tapPoint = tapPoint,
                inputPeakAbs = inputPeak,
                outputPeakAbs = PcmLevelMetrics.peakAbs(out),
                inputClipSamples = PcmLevelMetrics.clipSampleCount(captureFrame),
                outputClipSamples = PcmLevelMetrics.clipSampleCount(out),
                inputRmsDbfs = PcmLevelMetrics.rmsDbfs(captureFrame),
                outputRmsDbfs = PcmLevelMetrics.rmsDbfs(out),
                limiterScale = scale,
                limiterApplied = limiterApplied,
            )
        return ProcessedTxFrame(out, metrics)
    }
}
