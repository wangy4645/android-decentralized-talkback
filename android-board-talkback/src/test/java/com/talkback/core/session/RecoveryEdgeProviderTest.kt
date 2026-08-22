package com.talkback.core.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 2-1: RecoveryEdgeProvider is the sole AdmittedRecoveryTarget producer.
 * Does not wire Recovery Controller.
 */
class RecoveryEdgeProviderTest {

    private val members4 = listOf("M01", "M02", "M03", "M04")

    private fun snapshot(
        anchor: String = "M01",
        meshGeneration: Long = 5L,
        anchorEpoch: Long = 10L
    ) = ConferenceTopologyContract.composeAnchorAdmission(
        AnchorAdmissionInput(
            conferenceId = "c1",
            hostModuleId = "M02",
            anchorId = anchor,
            members = members4,
            meshGeneration = meshGeneration,
            anchorEpoch = anchorEpoch
        )
    )

    @Test
    fun project_matchesBindingContract_andIsStateless() {
        val snap = snapshot()
        val a = RecoveryEdgeProvider.project(snap)
        val b = RecoveryEdgeProvider.project(snap)
        val viaContract = ConferenceRecoveryBindingContract.project(snap)
        assertEquals(a, b)
        assertEquals(a, viaContract)
        assertEquals(3, a.targets.size)
        assertTrue(a.targets.all { it.source == RecoveryTargetSource.CURRENT_GENERATION })
    }

    @Test
    fun project_doesNotInferTransition_whenTransitionsEmpty() {
        val s0 = snapshot(anchor = "M01", meshGeneration = 31L, anchorEpoch = 10L)
        val s1 = ConferenceTopologyContract.failoverSnapshot(s0, "M02")
        val projected = RecoveryEdgeProvider.project(s1, previousSnapshot = s0)
        assertFalse(projected.targets.any { it.mediaEdge.anchorModuleId == "M01" })
        assertTrue(projected.targets.all { it.source == RecoveryTargetSource.CURRENT_GENERATION })
    }

    @Test
    fun project_mesh_emitsNoTargets() {
        val mesh = ConferenceTopologyModeTransitionContract.composeMeshSnapshot(
            conferenceId = "c1",
            hostModuleId = "M01",
            members = listOf("M01", "M02", "M03"),
            rosterEpoch = 1L,
            meshGeneration = 2L
        )
        assertTrue(RecoveryEdgeProvider.project(mesh).targets.isEmpty())
    }

    @Test
    fun project_doesNotMutateSnapshot() {
        val snap = snapshot()
        val before = snap.copy(
            members = snap.members.toList(),
            actualMediaEdges = snap.actualMediaEdges.toSet()
        )
        RecoveryEdgeProvider.project(snap)
        assertEquals(before, snap)
    }
}
