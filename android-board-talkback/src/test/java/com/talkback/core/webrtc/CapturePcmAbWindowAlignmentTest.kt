package com.talkback.core.webrtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CapturePcmAbWindowAlignmentTest {
    @Test
    fun inAndOutAccumulators_closeWindowsOnSameTimeline() {
        val inAcc = Pcm16LevelWindowAccumulator(windowDurationMs = 100, sampleRateHz = 48_000)
        val outAcc = Pcm16LevelWindowAccumulator(windowDurationMs = 100, sampleRateHz = 48_000)
        val frame = ShortArray(480) { 8_000 }

        inAcc.onFrame(nowMs = 0, buffer = frame, offset = 0, interleavedSampleCount = frame.size)
        outAcc.onFrame(nowMs = 0, buffer = frame, offset = 0, interleavedSampleCount = frame.size)

        val inSnap = inAcc.onFrame(nowMs = 100, buffer = frame, offset = 0, interleavedSampleCount = 0)
        val outSnap = outAcc.onFrame(nowMs = 100, buffer = frame, offset = 0, interleavedSampleCount = 0)

        requireNotNull(inSnap)
        requireNotNull(outSnap)
        assertEquals(100L, inSnap.windowEndMs)
        assertEquals(100L, outSnap.windowEndMs)
        assertTrue(inSnap.rmsLinear > 0.0)
        assertEquals(inSnap.rmsLinear, outSnap.rmsLinear, 1e-9)
    }
}
