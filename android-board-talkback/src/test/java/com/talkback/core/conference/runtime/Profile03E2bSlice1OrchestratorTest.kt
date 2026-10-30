package com.talkback.core.conference.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * ADR-0058 E2b Slice 1 — R01/R02 on unified [ConferenceMediaExecutionOrchestrator].
 */
class Profile03E2bSlice1OrchestratorTest {
    @Test
    fun r01_twoAdmittedSources_unifiedChain_decodeMix_peak() {
        val orch = orchestratorWithLoudPcm()
        installV1(orch, "S1", level = 20)
        installV1(orch, "S2", level = 30)

        val base = 1_000L
        val slot = 0L
        admitInTime(orch, "S1", slot, base, base + 1)
        admitInTime(orch, "S2", slot, base, base + 2)

        val result = orch.executeTopKSlotMixCycle(nowMs = base + 5, slot = slot, slotMediaTimeMs = base)
        assertEquals(setOf("S1", "S2"), result.topKIdentities)
        assertEquals(result.topKIdentities, result.decodeInvocationIdentities)
        assertEquals(result.topKIdentities, result.mixParticipantIdentities)
        assertEquals(2, result.mixedBlock.mixParticipantCount)
        assertTrue(result.mixedBlock.peakAbsFs <= MediaMixConstants.OUTPUT_PEAK_ABS_FS + 1e-9)
        for (s in result.mixedBlock.samples) {
            val fs = abs(s.toInt()).toDouble() / MediaMixConstants.S16_FULL_SCALE
            assertTrue(fs <= MediaMixConstants.OUTPUT_PEAK_ABS_FS + 1e-9)
        }
        assertEquals(ExecutionFenceState.OPEN, orch.selection.registry.get("S1")!!.fence)
        assertEquals(ExecutionFenceState.OPEN, orch.selection.registry.get("S2")!!.fence)
        assertTrue(orch.playout(result.mixedBlock, base + 6))
    }

    @Test
    fun r02_sixV1_top4DecodeMix_excludedRemainAdmitted() {
        val orch = orchestratorWithLoudPcm()
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
            installV1(orch, id, level)
        }

        val base = 2_000L
        val slot = 0L
        for (id in levels.keys) {
            admitInTime(orch, id, slot, base, base + 1)
        }

        val result = orch.executeTopKSlotMixCycle(nowMs = base + 5, slot = slot, slotMediaTimeMs = base)
        val expectedTopK = setOf("S1", "S2", "S3", "S4")
        assertEquals(expectedTopK, result.topKIdentities)
        assertEquals(expectedTopK, result.decodeInvocationIdentities)
        assertEquals(expectedTopK, result.mixParticipantIdentities)
        assertEquals(4, result.mixedBlock.mixParticipantCount)
        assertTrue(result.mixedBlock.mixParticipantCount <= MediaMixConstants.MIX_MAX_SOURCES)

        assertNull(orch.attemptDecode("S5", 1L, slot, base + 5))
        assertNull(orch.attemptDecode("S6", 1L, slot, base + 5))

        for (id in levels.keys) {
            val inst = orch.selection.registry.get(id)!!
            assertEquals(ExecutionFenceState.OPEN, inst.fence)
            assertTrue(inst.isExecutable)
        }
    }

    @Test
    fun slice1_sharesSingleSelectionWithAuthorityAndPipeline() {
        val orch = ConferenceMediaExecutionOrchestrator()
        assertTrue(orch.authority.selection === orch.selection)
        assertTrue(orch.pipeline.selection === orch.selection)
        assertTrue(orch.decodeMix.selection === orch.selection)
    }

    private fun orchestratorWithLoudPcm(): ConferenceMediaExecutionOrchestrator {
        val orch = ConferenceMediaExecutionOrchestrator()
        val loud = ShortArray(160) { 30_000 }
        orch.setDecodeSeam(
            OpusDecodeSeam { _, _, _, _ ->
                PcmFrame(loud.copyOf(), usableForMix = true)
            },
        )
        return orch
    }

    private fun installV1(orch: ConferenceMediaExecutionOrchestrator, id: String, level: Int) {
        orch.install(AdmittedMediaSource(id, 1L))
        assertTrue(
            orch.observeVoice(
                VoiceLevelObservation(id, 1L, voiceActive = true, audioLevel = level),
            ),
        )
    }

    private fun admitInTime(
        orch: ConferenceMediaExecutionOrchestrator,
        id: String,
        slot: Long,
        mediaTimeMs: Long,
        arrivalMs: Long,
    ) {
        assertEquals(
            FrameAdmitDisposition.QUEUED,
            orch.admitFrame(
                AdmittedMediaFrame(
                    sourceIdentity = id,
                    incarnationId = 1L,
                    mediaSlot = slot,
                    mediaTimeMs = mediaTimeMs,
                    arrivalMs = arrivalMs,
                ),
                arrivalMs,
            ),
        )
    }
}
