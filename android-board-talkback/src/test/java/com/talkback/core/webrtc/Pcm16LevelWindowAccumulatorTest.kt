package com.talkback.core.webrtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

class Pcm16LevelWindowAccumulatorTest {

    @Test
    fun digitalZero_hasZeroRmsAndNegativeInfinityDbFs() {
        val accumulator = Pcm16LevelWindowAccumulator(windowDurationMs = 500, sampleRateHz = 48_000)
        val buffer = ShortArray(480) { 0 }
        accumulator.onFrame(nowMs = 0, buffer = buffer, offset = 0, interleavedSampleCount = buffer.size)
        val snapshot = accumulator.onFrame(nowMs = 500, buffer = buffer, offset = 0, interleavedSampleCount = 0)!!
        assertEquals(480, snapshot.samples)
        assertEquals(0.0, snapshot.rmsLinear, 1e-9)
        assertTrue(snapshot.rmsDbFs.isInfinite() && snapshot.rmsDbFs < 0)
        assertEquals(0, snapshot.peakLinear)
    }

    @Test
    fun fullScaleSquare_hasUnitRmsAndZeroDbFs() {
        val accumulator = Pcm16LevelWindowAccumulator(windowDurationMs = 100, sampleRateHz = 48_000)
        val buffer = ShortArray(100) { 32_767 }
        accumulator.onFrame(nowMs = 0, buffer = buffer, offset = 0, interleavedSampleCount = buffer.size)
        val snapshot = accumulator.onFrame(nowMs = 100, buffer = buffer, offset = 0, interleavedSampleCount = 0)!!
        assertEquals(32_767, snapshot.peakLinear)
        assertEquals(32_767.0, snapshot.rmsLinear, 1.0)
        assertEquals(0.0, snapshot.peakDbFs, 0.05)
        assertEquals(0.0, snapshot.rmsDbFs, 0.05)
    }

    @Test
    fun sineWave_rmsMatchesAmplitudeOverRootTwo() {
        val amplitude = 10_000
        val samples = ShortArray(480) { index ->
            val phase = 2.0 * PI * index / 48.0
            (amplitude * sin(phase)).toInt().toShort()
        }
        val accumulator = Pcm16LevelWindowAccumulator(windowDurationMs = 10, sampleRateHz = 48_000)
        accumulator.onFrame(nowMs = 0, buffer = samples, offset = 0, interleavedSampleCount = samples.size)
        val snapshot = accumulator.onFrame(nowMs = 10, buffer = samples, offset = 0, interleavedSampleCount = 0)!!
        val expectedRms = amplitude / sqrt(2.0)
        assertEquals(expectedRms, snapshot.rmsLinear, 150.0)
        assertEquals(amplitude, snapshot.peakLinear)
    }

    @Test
    fun lowLevelNoise_isBelowSpeechFixture() {
        val silence = ShortArray(480) { (((it * 17) and 0x7) - 3).toShort() }
        val speech = ShortArray(480) { index ->
            val phase = 2.0 * PI * index / 24.0
            (12_000 * sin(phase)).toInt().toShort()
        }
        val silenceRms = windowRms(silence)
        val speechRms = windowRms(speech)
        assertTrue(speechRms - silenceRms > 20.0)
    }

    @Test
    fun windowReset_doesNotCarryEnergyIntoNextWindow() {
        val accumulator = Pcm16LevelWindowAccumulator(windowDurationMs = 100, sampleRateHz = 48_000)
        val loud = ShortArray(100) { 20_000 }
        accumulator.onFrame(nowMs = 0, buffer = loud, offset = 0, interleavedSampleCount = loud.size)
        val first = accumulator.onFrame(nowMs = 100, buffer = loud, offset = 0, interleavedSampleCount = 0)!!
        assertTrue(first.rmsLinear > 0.0)

        val zero = ShortArray(100) { 0 }
        accumulator.onFrame(nowMs = 100, buffer = zero, offset = 0, interleavedSampleCount = zero.size)
        val second = accumulator.onFrame(nowMs = 200, buffer = zero, offset = 0, interleavedSampleCount = 0)!!
        assertEquals(0.0, second.rmsLinear, 1e-9)
        assertEquals(0, second.peakLinear)
    }

    private fun windowRms(samples: ShortArray): Double {
        val accumulator = Pcm16LevelWindowAccumulator(windowDurationMs = 10, sampleRateHz = 48_000)
        accumulator.onFrame(nowMs = 0, buffer = samples, offset = 0, interleavedSampleCount = samples.size)
        return accumulator.onFrame(nowMs = 10, buffer = samples, offset = 0, interleavedSampleCount = 0)!!.rmsLinear
    }
}
