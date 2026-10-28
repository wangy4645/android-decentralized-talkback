package com.talkback.core.conference.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B-1 RX vacant-seat continuity. Wire V is not rewritten; RTP-only never admits.
 */
class Profile01RxSeatContinuityDeskTest {
    @Test
    fun v0Incumbent_withFreshRtp_keepsVacantSeat() {
        val rt = ConferenceMediaSelectionRuntime()
        install(rt, "M03")
        observe(rt, "M03", v = true, level = 40, nowMs = 1_000L)
        assertEquals("M03", rt.selectTopK(1_000L).members.single().sourceIdentity)

        observe(rt, "M03", v = false, level = 40, nowMs = 1_080L)
        val top = rt.selectTopK(1_080L)
        assertEquals(listOf("M03"), top.members.map { it.sourceIdentity })
        assertTrue(rt.isDecodeEligible("M03", 1L))
        assertFalse(rt.voiceStore.get("M03")!!.voiceActive)
    }

    @Test
    fun v0NeverVoiceActive_withRtp_doesNotEnter() {
        val rt = ConferenceMediaSelectionRuntime()
        install(rt, "M03")
        observe(rt, "M03", v = false, level = 40, nowMs = 1_000L)
        assertTrue(rt.selectTopK(1_000L).members.isEmpty())
        assertFalse(rt.isDecodeEligible("M03", 1L))
    }

    @Test
    fun v0Incumbent_afterContinuityWindow_drops() {
        val rt = ConferenceMediaSelectionRuntime()
        install(rt, "M03")
        observe(rt, "M03", v = true, level = 40, nowMs = 1_000L)
        rt.selectTopK(1_000L)
        observe(rt, "M03", v = false, level = 40, nowMs = 1_000L + MediaRuntimeConstants.TOP_K_SEAT_CONTINUITY_MS + 1L)
        val t = 1_000L + MediaRuntimeConstants.TOP_K_SEAT_CONTINUITY_MS + 1L
        assertTrue(rt.selectTopK(t).members.isEmpty())
    }

    @Test
    fun v0Incumbent_staleRtp_dropsEvenInsideContinuity() {
        val rt = ConferenceMediaSelectionRuntime()
        install(rt, "M03")
        observe(rt, "M03", v = true, level = 40, nowMs = 1_000L)
        rt.selectTopK(1_000L)
        observe(rt, "M03", v = false, level = 40, nowMs = 1_000L)
        val t = 1_000L + MediaRuntimeConstants.TOP_K_SEAT_RTP_STALE_MS + 1L
        assertTrue(rt.selectTopK(t).members.isEmpty())
    }

    @Test
    fun v0Incumbent_doesNotDisplaceSustainedV1WhenTopKFull() {
        val rt = ConferenceMediaSelectionRuntime()
        listOf("S1", "S2", "S3", "S4", "S5").forEach { install(rt, it) }
        observe(rt, "S1", v = true, level = 10, nowMs = 1_000L)
        observe(rt, "S2", v = true, level = 20, nowMs = 1_000L)
        observe(rt, "S3", v = true, level = 30, nowMs = 1_000L)
        observe(rt, "S4", v = true, level = 40, nowMs = 1_000L)
        observe(rt, "S5", v = true, level = 90, nowMs = 1_000L)
        rt.selectTopK(1_000L)
        val t = 1_000L + MediaRuntimeConstants.TOP_K_CHALLENGER_ADMIT_MS
        observe(rt, "S1", v = false, level = 10, nowMs = t)
        observe(rt, "S5", v = true, level = 5, nowMs = t)
        val top = rt.selectTopK(t)
        assertFalse(top.members.any { it.sourceIdentity == "S1" })
        assertTrue(top.members.any { it.sourceIdentity == "S5" })
        assertEquals(4, top.members.size)
    }

    @Test
    fun localSource_notRetainedByContinuity() {
        val rt = ConferenceMediaSelectionRuntime()
        rt.localModuleIdForTopKExclusion = "M01"
        install(rt, "M01")
        install(rt, "M03")
        observe(rt, "M01", v = true, level = 10, nowMs = 1_000L)
        observe(rt, "M03", v = true, level = 40, nowMs = 1_000L)
        rt.selectTopK(1_000L)
        observe(rt, "M01", v = false, level = 10, nowMs = 1_080L)
        observe(rt, "M03", v = false, level = 40, nowMs = 1_080L)
        val top = rt.selectTopK(1_080L)
        assertFalse(top.members.any { it.sourceIdentity == "M01" })
        assertEquals(listOf("M03"), top.members.map { it.sourceIdentity })
    }

    private fun install(
        rt: ConferenceMediaSelectionRuntime,
        id: String,
    ) {
        rt.install(AdmittedMediaSource(sourceIdentity = id, incarnationId = 1L))
    }

    private fun observe(
        rt: ConferenceMediaSelectionRuntime,
        id: String,
        v: Boolean,
        level: Int,
        nowMs: Long,
    ) {
        assertTrue(
            rt.observeVoice(
                VoiceLevelObservation(
                    sourceIdentity = id,
                    incarnationId = 1L,
                    voiceActive = v,
                    audioLevel = level,
                ),
                nowMs,
            ),
        )
    }
}
