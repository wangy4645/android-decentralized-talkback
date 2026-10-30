package com.talkback.core.conference.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * E2b-06 — P03 Q8 Health boundary conformance.
 */
class Profile03E2b06HealthBoundaryHarnessTest {
    @Test
    fun r05_crossCheck_singleLate_doesNotIndependentlyFlipHealth() {
        val rt = HealthBoundaryRuntime()
        rt.install(AdmittedMediaSource("S1", 1L))
        val before = rt.authorityUnchangedSnapshot()

        rt.noteLateForPlayout(atMs = 100L)
        assertEquals(1, rt.activeEvidence(100L).size)
        assertTrue(rt.projectionHints(100L).isEmpty())
        assertTrue(rt.inferredHealthLabelsWithoutMapping(100L).isEmpty())
        assertFalse(containsForbiddenInferences(rt.projectionHints(100L)))

        assertEquals(before, rt.authorityUnchangedSnapshot())
        assertEquals(ExecutionFenceState.OPEN, rt.selection.registry.get("S1")!!.fence)
    }

    @Test
    fun r07_crossCheck_plcExhaustion_doesNotIndependentlyFlipHealth() {
        val rt = HealthBoundaryRuntime()
        rt.install(AdmittedMediaSource("S1", 1L))
        val before = rt.authorityUnchangedSnapshot()

        rt.notePlcExhaustion(atMs = 200L)
        assertTrue(rt.projectionHints(200L).isEmpty())
        assertTrue(rt.inferredHealthLabelsWithoutMapping(200L).isEmpty())
        assertEquals(before, rt.authorityUnchangedSnapshot())
        assertEquals(ExecutionFenceState.OPEN, rt.selection.registry.get("S1")!!.fence)
    }

    @Test
    fun silenceV0EmptyTopK_doesNotEstablishNoRemoteSource() {
        val rt = HealthBoundaryRuntime()
        rt.install(AdmittedMediaSource("S1", 1L))
        rt.observeVoice(VoiceLevelObservation("S1", 1L, voiceActive = true, audioLevel = 20))
        rt.selectTopK(0L)
        assertEquals(1, rt.selection.currentTopK().members.size)

        rt.observeVoice(VoiceLevelObservation("S1", 1L, voiceActive = false, audioLevel = 20))
        rt.selectTopK(10L)
        assertTrue(rt.selection.currentTopK().members.isEmpty())

        // No Health / NO_REMOTE_SOURCE inference from silence / V=0 / empty Top-K
        assertTrue(rt.projectionHints(10L).isEmpty())
        assertFalse(
            rt.inferredHealthLabelsWithoutMapping(10L)
                .contains(ForbiddenP03HealthInferences.NO_REMOTE_SOURCE),
        )
        assertEquals(ExecutionFenceState.OPEN, rt.selection.registry.get("S1")!!.fence)
        assertTrue(rt.selection.registry.get("S1") != null)
    }

    @Test
    fun cE2b06_03_aggregationWindow_notAuthorityOrHealthThreshold() {
        val rt = HealthBoundaryRuntime()
        rt.noteEvidence(RuntimeDegradationKind.AUDIOTRACK_UNDERRUN, "u", atMs = 0L)
        assertEquals(1, rt.activeEvidence(500L).size)
        // Past window: evidence expires from aggregation view
        assertEquals(0, rt.activeEvidence(MediaHealthBoundaryConstants.HEALTH_AGGREGATION_WINDOW_MS + 1).size)

        // Expiration must not fence / revoke / invent Health
        rt.install(AdmittedMediaSource("S1", 1L))
        assertEquals(
            0,
            rt.activeEvidence(MediaHealthBoundaryConstants.HEALTH_AGGREGATION_WINDOW_MS + 1).size,
        )
        assertEquals(ExecutionFenceState.OPEN, rt.selection.registry.get("S1")!!.fence)
        assertTrue(rt.projectionHints(2_000L).isEmpty())
        assertEquals(MediaHealthBoundaryConstants.HEALTH_AGGREGATION_WINDOW_MS, 1_000L)
    }

    @Test
    fun evidenceClear_doesNotMeanAuthorityOrHealthRepair() {
        val rt = HealthBoundaryRuntime()
        rt.install(AdmittedMediaSource("S1", 1L))
        assertTrue(rt.hardFence("S1", 1L))
        val fenced = rt.authorityUnchangedSnapshot()

        rt.noteEvidence(RuntimeDegradationKind.AUDIOTRACK_DEVICE_BUSY, "busy", 50L)
        assertEquals(1, rt.activeEvidence(50L).size)
        rt.clearEvidenceMatchingDetail("busy")
        assertEquals(0, rt.activeEvidence(50L).size)

        // Fence / authority unchanged — clear ≠ repair
        assertEquals(fenced, rt.authorityUnchangedSnapshot())
        assertEquals(ExecutionFenceState.HARD_FENCED, rt.selection.registry.get("S1")!!.fence)
    }

    @Test
    fun cE2b06_01_noMapping_remainsEvidenceOnly() {
        val rt = HealthBoundaryRuntime()
        rt.noteEvidence(RuntimeDegradationKind.JITTER_CAP_EXHAUSTED, "cap", 0L)
        assertEquals(1, rt.activeEvidence(0L).size)
        assertTrue(rt.projectionSeam.mappings().isEmpty())
        assertTrue(rt.projectionHints(0L).isEmpty())
        assertFalse(containsForbiddenInferences(rt.projectionHints(0L)))
    }

    @Test
    fun q8_positiveSmoke_frozenMappingEmitsHint_p03DoesNotOwnHealth() {
        val rt = HealthBoundaryRuntime()
        rt.projectionSeam.setMappings(
            listOf(
                FrozenQ12Mapping(
                    evidenceKind = RuntimeDegradationKind.AUDIOTRACK_UNDERRUN,
                    projectionHint = "Q12_MAPPED_HINT_TRANSPORT_DEGRADE",
                ),
            ),
        )
        rt.noteEvidence(RuntimeDegradationKind.AUDIOTRACK_UNDERRUN, "u", 10L)
        val hints = rt.projectionHints(10L)
        assertEquals(listOf("Q12_MAPPED_HINT_TRANSPORT_DEGRADE"), hints)
        // P03 still does not own Health semantics / invent enums
        assertTrue(rt.inferredHealthLabelsWithoutMapping(10L).isEmpty())
        assertFalse(containsForbiddenInferences(hints))
    }

    @Test
    fun cE2b06_02_projectionDoesNotFeedTopKOrFence() {
        val rt = HealthBoundaryRuntime()
        rt.install(AdmittedMediaSource("S1", 1L))
        rt.observeVoice(VoiceLevelObservation("S1", 1L, true, 20))
        rt.selectTopK(0L)
        rt.projectionSeam.setMappings(
            listOf(
                FrozenQ12Mapping(
                    RuntimeDegradationKind.REBIND_DISRUPTION,
                    "Q12_MAPPED_HINT",
                ),
            ),
        )
        rt.noteEvidence(RuntimeDegradationKind.REBIND_DISRUPTION, "r", 0L)
        assertEquals(listOf("Q12_MAPPED_HINT"), rt.projectionHints(0L))

        // Top-K / fence unchanged by projection
        assertEquals(1, rt.selection.currentTopK().members.size)
        assertTrue(rt.selection.isDecodeEligible("S1", 1L))
        assertEquals(ExecutionFenceState.OPEN, rt.selection.registry.get("S1")!!.fence)
    }

    private fun containsForbiddenInferences(hints: List<String>): Boolean {
        val forbidden =
            setOf(
                ForbiddenP03HealthInferences.IMPAIRED,
                ForbiddenP03HealthInferences.UNAVAILABLE,
                ForbiddenP03HealthInferences.PARTIAL,
                ForbiddenP03HealthInferences.SOURCE_CONFLICT,
                ForbiddenP03HealthInferences.NO_REMOTE_SOURCE,
            )
        return hints.any { it in forbidden }
    }
}
