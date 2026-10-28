package com.talkback.core.conference.session.integration

import com.talkback.core.conference.runtime.OpusCodecConstants
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class M01GatedTxEncodeLevelDeskTest {
    @After
    fun tearDown() {
        M01GatedTxEncodeLevelRuntime.enabled = false
        M01GatedTxEncodeLevelProcessor.clearSession("s1")
        M01GatedTxEncodeLevelProcessor.clearSession("s2")
        M01GatedTxEncodeLevelProcessor.clearSession("s3")
        M01GatedTxEncodeLevelProcessor.clearSession("s4")
    }

    @Test
    fun silence_not_gained_when_disabled() {
        M01GatedTxEncodeLevelRuntime.enabled = false
        val frame = frameAtDbfs(-70.0)
        val out =
            M01GatedTxEncodeLevelProcessor.maybeProcess(
                sessionId = "s1",
                moduleId = "M01",
                pcmForEncode = frame,
            )
        assertFalse(out.gainApplied)
        assertEquals(frame.toList(), out.samples.toList())
    }

    @Test
    fun m02_never_gained_even_when_enabled() {
        M01GatedTxEncodeLevelRuntime.enabled = true
        val frame = frameAtDbfs(-20.0)
        val out =
            M01GatedTxEncodeLevelProcessor.maybeProcess(
                sessionId = "s2",
                moduleId = "M02",
                pcmForEncode = frame,
            )
        assertFalse(out.gainApplied)
    }

    @Test
    fun m01_applies_gain_when_gate_open() {
        M01GatedTxEncodeLevelRuntime.enabled = true
        val frame = frameAtDbfs(-30.0)
        val out =
            M01GatedTxEncodeLevelProcessor.maybeProcess(
                sessionId = "s3",
                moduleId = "M01",
                pcmForEncode = frame,
            )
        assertTrue(out.gainApplied)
        assertTrue(out.gateOpen)
        assertTrue(out.outputRmsDbfs > out.inputRmsDbfs + 4.0)
    }

    @Test
    fun hangover_keeps_gain_briefly_after_drop_below_close() {
        M01GatedTxEncodeLevelRuntime.enabled = true
        val sid = "s4"
        val loud = frameAtDbfs(-30.0)
        val quiet = frameAtDbfs(-70.0)
        val first =
            M01GatedTxEncodeLevelProcessor.maybeProcess(sid, "M01", loud)
        assertTrue(first.gainApplied)
        val second =
            M01GatedTxEncodeLevelProcessor.maybeProcess(sid, "M01", quiet)
        assertTrue(second.gainApplied)
        assertTrue(second.gateOpen)
    }

    private fun frameAtDbfs(targetDbfs: Double): ShortArray {
        val n = OpusCodecConstants.FRAME_SAMPLES_20MS
        val amp = (32767.0 * Math.pow(10.0, targetDbfs / 20.0)).toInt().coerceAtLeast(1)
        return ShortArray(n) { amp.toShort() }
    }
}
