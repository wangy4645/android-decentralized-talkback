package com.talkback.core.conference.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** B-8: V-edge challengers wait; wire V / K / ranking / B-1 windows unchanged. */
class Profile01B8ChallengerAdmitDeskTest {
    @Test
    fun emptyTopK_firstV1_entersImmediately() {
        val rt = ConferenceMediaSelectionRuntime()
        install(rt, "M03")
        observe(rt, "M03", v = true, level = 40, nowMs = 1_000L)
        assertEquals("M03", rt.selectTopK(1_000L).members.single().sourceIdentity)
        assertTrue(rt.voiceStore.get("M03")!!.voiceActive)
    }

    @Test
    fun briefChallengerV1_doesNotEnterWhileIncumbentSeated() {
        val rt = ConferenceMediaSelectionRuntime()
        install(rt, "M01")
        install(rt, "M02")
        observe(rt, "M01", v = true, level = 20, nowMs = 1_000L)
        rt.selectTopK(1_000L)
        observe(rt, "M01", v = true, level = 20, nowMs = 1_020L)
        observe(rt, "M02", v = true, level = 5, nowMs = 1_020L)
        val top = rt.selectTopK(1_020L)
        assertEquals(listOf("M01"), top.members.map { it.sourceIdentity })
    }

    @Test
    fun sustainedChallengerV1_entersAfterAdmitWindow() {
        val rt = ConferenceMediaSelectionRuntime()
        install(rt, "M01")
        install(rt, "M02")
        observe(rt, "M01", v = true, level = 20, nowMs = 1_000L)
        rt.selectTopK(1_000L)
        observe(rt, "M02", v = true, level = 5, nowMs = 1_000L)
        val t = 1_000L + MediaRuntimeConstants.TOP_K_CHALLENGER_ADMIT_MS
        observe(rt, "M01", v = true, level = 20, nowMs = t)
        observe(rt, "M02", v = true, level = 5, nowMs = t)
        val top = rt.selectTopK(t)
        assertTrue(top.members.map { it.sourceIdentity }.containsAll(listOf("M01", "M02")))
    }

    @Test
    fun v0FlickerChallenger_doesNotStealContinuitySeat() {
        val rt = ConferenceMediaSelectionRuntime()
        install(rt, "M04")
        install(rt, "M02")
        observe(rt, "M04", v = true, level = 20, nowMs = 1_000L)
        rt.selectTopK(1_000L)
        observe(rt, "M04", v = false, level = 20, nowMs = 1_050L)
        observe(rt, "M02", v = true, level = 5, nowMs = 1_050L)
        val top = rt.selectTopK(1_050L)
        assertEquals(listOf("M04"), top.members.map { it.sourceIdentity })
        assertFalse(rt.voiceStore.get("M04")!!.voiceActive)
        assertTrue(rt.voiceStore.get("M02")!!.voiceActive)
        assertFalse(top.members.any { it.sourceIdentity == "M02" })
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
