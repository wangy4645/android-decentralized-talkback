package com.talkback.core.conference.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C-IG-02 harness — E2b-02 subsets of R01 / R02 / R03 / R09.
 * No Opus / mix / AudioTrack / native teardown.
 */
class Profile03E2b02AdmissionTopKHarnessTest {
    @Test
    fun packetArrival_doesNotCreateAdmittedMediaSource() {
        val rt = ConferenceMediaSelectionRuntime()
        val accepted =
            rt.observeVoice(
                VoiceLevelObservation(
                    sourceIdentity = "S-unknown",
                    incarnationId = 1L,
                    voiceActive = true,
                    audioLevel = 10,
                ),
            )
        assertFalse(accepted)
        assertTrue(rt.registry.installedSnapshot().isEmpty())
        rt.selectTopK(0L)
        assertTrue(rt.currentTopK().members.isEmpty())
        assertFalse(rt.isDecodeEligible("S-unknown", 1L))
    }

    @Test
    fun r01_twoAdmittedSources_bothDecodeEligible() {
        val rt = ConferenceMediaSelectionRuntime()
        install(rt, "S1", 1L)
        install(rt, "S2", 1L)
        observe(rt, "S1", 1L, v = true, level = 40)
        observe(rt, "S2", 1L, v = true, level = 50)
        val top = rt.selectTopK(0L)
        assertEquals(2, top.members.size)
        assertTrue(rt.isDecodeEligible("S1", 1L))
        assertTrue(rt.isDecodeEligible("S2", 1L))
        assertEquals(setOf("S1", "S2"), rt.decodeEligibleIdentities())
    }

    @Test
    fun r02_sixV1_exactlyTop4_exclusionNotFence() {
        val rt = ConferenceMediaSelectionRuntime()
        // lower audioLevel = louder
        val levels =
            mapOf(
                "S1" to 10,
                "S2" to 20,
                "S3" to 30,
                "S4" to 40,
                "S5" to 50,
                "S6" to 60,
            )
        for ((id, level) in levels) {
            install(rt, id, 1L)
            observe(rt, id, 1L, v = true, level = level)
        }
        val top = rt.selectTopK(0L)
        assertEquals(4, top.members.size)
        assertEquals(setOf("S1", "S2", "S3", "S4"), top.members.map { it.sourceIdentity }.toSet())
        assertEquals(setOf("S1", "S2", "S3", "S4"), rt.decodeEligibleIdentities())
        assertFalse(rt.isDecodeEligible("S5", 1L))
        assertFalse(rt.isDecodeEligible("S6", 1L))

        // exclusion ≠ HARD FENCE / revoke
        for (id in levels.keys) {
            val inst = rt.registry.get(id)!!
            assertEquals(ExecutionFenceState.OPEN, inst.fence)
            assertTrue(inst.isExecutable)
        }
    }

    @Test
    fun r03_v0YieldsSeat_whileHoldWouldStillRun() {
        val rt = ConferenceMediaSelectionRuntime()
        install(rt, "S_hold", 1L)
        install(rt, "A", 1L)
        install(rt, "B", 1L)
        install(rt, "C", 1L)
        install(rt, "S_challenger", 1L)

        observe(rt, "S_hold", 1L, v = true, level = 25)
        observe(rt, "A", 1L, v = true, level = 30)
        observe(rt, "B", 1L, v = true, level = 40)
        observe(rt, "C", 1L, v = true, level = 50)
        // Challenger quieter — not in Top-K yet
        observe(rt, "S_challenger", 1L, v = true, level = 60)

        val t0 = 1_000L
        var top = rt.selectTopK(t0)
        assertTrue(top.members.any { it.sourceIdentity == "S_hold" })
        assertFalse(top.members.any { it.sourceIdentity == "S_challenger" })

        // Within HoldMs: S_hold -> V=0; challenger becomes loud V=1
        observe(rt, "S_hold", 1L, v = false, level = 25)
        observe(rt, "S_challenger", 1L, v = true, level = 10)

        val tHoldNotExpired = t0 + 50L // HoldMs=200 still running for former seat
        top = rt.selectTopK(tHoldNotExpired)
        assertFalse(
            "V=0 must not retain Top-K via Hold",
            top.members.any { it.sourceIdentity == "S_hold" },
        )
        assertTrue(
            "V=1 challenger may enter without waiting full Hold",
            top.members.any { it.sourceIdentity == "S_challenger" },
        )
        assertTrue(rt.isDecodeEligible("S_challenger", 1L))
        assertFalse(rt.isDecodeEligible("S_hold", 1L))
        // V=0 is not HARD FENCE
        assertEquals(ExecutionFenceState.OPEN, rt.registry.get("S_hold")!!.fence)
    }

    @Test
    fun r09_hardFence_blocksDecode_latePacketsDoNotResurrect() {
        val rt = ConferenceMediaSelectionRuntime()
        install(rt, "S1", 7L)
        observe(rt, "S1", 7L, v = true, level = 20)
        rt.selectTopK(0L)
        assertTrue(rt.isDecodeEligible("S1", 7L))

        assertTrue(rt.hardFence("S1", 7L))
        // Logical fence immediate — no native teardown wait
        assertEquals(ExecutionFenceState.HARD_FENCED, rt.registry.get("S1")!!.fence)

        // Late / on-time packets for fenced incarnation (voice observe + select)
        assertTrue(rt.observeVoice(VoiceLevelObservation("S1", 7L, voiceActive = true, audioLevel = 5)))
        val top = rt.selectTopK(10L)
        assertFalse(top.members.any { it.sourceIdentity == "S1" })
        assertFalse(rt.isDecodeEligible("S1", 7L))

        // Authority row still present (fence ≠ destroy Source binding in this seam)
        assertTrue(rt.registry.get("S1") != null)
    }

    @Test
    fun cE2b02_01_voiceAloneWithoutTopK_notEligible() {
        val rt = ConferenceMediaSelectionRuntime()
        install(rt, "loud", 1L)
        install(rt, "a", 1L)
        install(rt, "b", 1L)
        install(rt, "c", 1L)
        install(rt, "quiet", 1L)
        observe(rt, "loud", 1L, v = true, level = 10)
        observe(rt, "a", 1L, v = true, level = 20)
        observe(rt, "b", 1L, v = true, level = 30)
        observe(rt, "c", 1L, v = true, level = 40)
        observe(rt, "quiet", 1L, v = true, level = 90)
        rt.selectTopK(0L)
        assertFalse(rt.isDecodeEligible("quiet", 1L))
    }

    private fun install(rt: ConferenceMediaSelectionRuntime, id: String, incarnation: Long) {
        rt.install(AdmittedMediaSource(sourceIdentity = id, incarnationId = incarnation))
    }

    private fun observe(
        rt: ConferenceMediaSelectionRuntime,
        id: String,
        incarnation: Long,
        v: Boolean,
        level: Int,
    ) {
        assertTrue(
            rt.observeVoice(
                VoiceLevelObservation(
                    sourceIdentity = id,
                    incarnationId = incarnation,
                    voiceActive = v,
                    audioLevel = level,
                ),
            ),
        )
    }
}
