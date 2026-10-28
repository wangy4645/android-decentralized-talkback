package com.talkback.core.conference.session.integration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Test

/** B-9: canonical PCM RMS + wire V logged from the same 20 ms buffer. */
class Profile01TxCanonicalVoiceObservationDeskTest {
    @After
    fun tearDown() {
        Profile01TxCanonicalVoiceObservation.resetForTest()
    }

    @Test
    fun silencePcm_rmsIsInf() {
        val pcm = ShortArray(960)
        assertEquals("-inf", Profile01TxCanonicalVoiceObservation.rmsDbfs(pcm))
    }

    @Test
    fun loudPcm_rmsAboveMinus20() {
        val pcm = ShortArray(960) { 20000 }
        val rms = Profile01TxCanonicalVoiceObservation.rmsDbfs(pcm).toDouble()
        assertTrue(rms > -20.0)
    }

    @Test
    fun observe_emitsSameBufferFields() {
        val lines = mutableListOf<String>()
        Profile01TxCanonicalVoiceObservation.testLogSink = { lines += it }
        val pcm = ShortArray(960) { 8000 }
        Profile01TxCanonicalVoiceObservation.observe(
            sessionId = "s",
            moduleId = "M01",
            pcm960 = pcm,
            voiceOctet = 0x80 or 20,
            vadHang = true,
        )
        assertEquals(1, lines.size)
        val line = lines.single()
        assertTrue(line.contains("phase=TX_CANONICAL_VOICE"))
        assertTrue(line.contains("V=1"))
        assertTrue(line.contains("vadHang=1"))
        assertTrue(line.contains("audioLevel=20"))
        assertTrue(line.contains("rmsDbfs="))
    }
}
