package com.talkback.core.conference.aqc

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

object PcmLevelMetrics {
    fun peakAbs(samples: ShortArray): Int {
        var peak = 0
        for (s in samples) {
            peak = maxOf(peak, abs(s.toInt()))
        }
        return peak
    }

    fun clipSampleCount(samples: ShortArray): Int {
        var n = 0
        for (s in samples) {
            if (abs(s.toInt()) >= AqcFrameConstants.CLIP_ABS_THRESHOLD) n++
        }
        return n
    }

    fun rmsDbfs(samples: ShortArray): Double {
        if (samples.isEmpty()) return Double.NEGATIVE_INFINITY
        var sumSq = 0.0
        for (s in samples) {
            val v = s.toDouble() / 32768.0
            sumSq += v * v
        }
        val rms = sqrt(sumSq / samples.size)
        if (rms <= 0.0) return Double.NEGATIVE_INFINITY
        return 20.0 * log10(rms)
    }
}
