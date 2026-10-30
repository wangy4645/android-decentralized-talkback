package com.talkback.core.webrtc

import kotlin.math.ln
import kotlin.math.sqrt

data class Pcm16LevelWindowSnapshot(
    val samples: Int,
    val rmsLinear: Double,
    val rmsDbFs: Double,
    val peakLinear: Int,
    val peakDbFs: Double,
    val windowEndMs: Long = 0L,
)

/**
 * Fixed-duration PCM16 level window (RMS + peak). Resets after each completed window.
 */
class Pcm16LevelWindowAccumulator(
    private val windowDurationMs: Long,
    private val sampleRateHz: Int,
) {
    private val fullScale = 32768.0

    private var windowStartedAtMs: Long? = null
    private var sumSquares = 0.0
    private var sampleCount = 0
    private var peakAbs = 0

    fun ingestInterleavedPcm16(buffer: ShortArray, offset: Int, sampleCountToIngest: Int) {
        if (sampleCountToIngest <= 0) return
        val end = offset + sampleCountToIngest
        require(end <= buffer.size) { "PCM ingest range out of bounds" }
        for (index in offset until end) {
            val sample = buffer[index].toInt()
            val abs = kotlin.math.abs(sample)
            if (abs > peakAbs) peakAbs = abs
            sumSquares += sample.toDouble() * sample.toDouble()
            sampleCount++
        }
    }

    fun onFrame(nowMs: Long, buffer: ShortArray, offset: Int, interleavedSampleCount: Int): Pcm16LevelWindowSnapshot? {
        if (windowStartedAtMs == null) {
            windowStartedAtMs = nowMs
        }
        ingestInterleavedPcm16(buffer, offset, interleavedSampleCount)
        val startedAt = windowStartedAtMs ?: return null
        if (nowMs - startedAt < windowDurationMs) {
            return null
        }
        val snapshot = snapshot(nowMs)
        resetWindow(nowMs)
        return snapshot
    }

    fun snapshot(nowMs: Long = windowStartedAtMs ?: 0L): Pcm16LevelWindowSnapshot {
        val rms = if (sampleCount == 0) 0.0 else sqrt(sumSquares / sampleCount)
        return Pcm16LevelWindowSnapshot(
            samples = sampleCount,
            rmsLinear = rms,
            rmsDbFs = linearToDbFs(rms),
            peakLinear = peakAbs,
            peakDbFs = linearToDbFs(peakAbs.toDouble()),
            windowEndMs = nowMs,
        )
    }

    fun resetWindow(nowMs: Long) {
        windowStartedAtMs = nowMs
        sumSquares = 0.0
        sampleCount = 0
        peakAbs = 0
    }

    fun expectedSamplesPerWindow(channels: Int): Int {
        require(channels > 0)
        return ((sampleRateHz * windowDurationMs) / 1000L).toInt() * channels
    }

    companion object {
        fun linearToDbFs(linear: Double): Double {
            if (linear <= 0.0) return Double.NEGATIVE_INFINITY
            return 20.0 * (ln(linear / 32768.0) / LN10)
        }

        private val LN10 = ln(10.0)
    }
}
