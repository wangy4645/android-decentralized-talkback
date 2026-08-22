package com.talkback.core.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Gate 3 / Gate 4 capacity fixtures K1–K8. Contract only.
 */
class ConferenceCapacityGatePhaseKFixturesTest {

    private fun members(n: Int): List<String> = (1..n).map { i -> "M%02d".format(i) }

    private fun policyInput(n: Int) = AnchorAdmissionDecisionInput(
        conferenceId = "c1",
        members = members(n),
        hostModuleId = "M01",
        candidateAnchorId = "M01",
        threshold = ConferenceCapacityGateContract.ANCHOR_THRESHOLD
    )

    private fun anchorSnapshot(n: Int) = ConferenceTopologyContract.composeAnchorAdmission(
        AnchorAdmissionInput(
            conferenceId = "c1",
            hostModuleId = "M01",
            anchorId = "M01",
            members = members(n),
            meshGeneration = 5L,
            anchorEpoch = 10L
        )
    )

    @Test
    fun k1_n4_meshRejected_anchorRequired() {
        val decision = ConferenceAnchorAdmissionPolicy.decide(policyInput(4))
        assertTrue(decision is AnchorAdmissionDecision.Anchor)
        val mesh = ConferenceTopologyModeTransitionContract.composeMeshSnapshot(
            conferenceId = "c1",
            hostModuleId = "M01",
            members = members(4),
            rosterEpoch = 1L,
            meshGeneration = 1L
        )
        val eval = ConferenceCapacityGateContract.evaluate(mesh)
        assertTrue(eval is ConferenceCapacityEvaluation.Rejected)
    }

    @Test
    fun k2_n3_meshValid_emptyEdgeSet() {
        assertEquals(
            AnchorAdmissionDecision.Mesh,
            ConferenceAnchorAdmissionPolicy.decide(policyInput(3))
        )
        val mesh = ConferenceTopologyModeTransitionContract.composeMeshSnapshot(
            conferenceId = "c1",
            hostModuleId = "M01",
            members = members(3),
            rosterEpoch = 1L,
            meshGeneration = 1L
        )
        assertTrue(mesh.actualMediaEdges.isEmpty())
        assertEquals(
            ConferenceCapacityEvaluation.MeshAdmitted,
            ConferenceCapacityGateContract.evaluate(mesh)
        )
    }

    @Test
    fun k3_n8_starEdgeCountIsNMinus1() {
        val snapshot = anchorSnapshot(8)
        assertEquals(7, snapshot.actualMediaEdges.size)
        assertEquals(7, ConferenceCapacityGateContract.expectedStarEdgeCount(8))
        val eval = ConferenceCapacityGateContract.evaluate(snapshot)
        assertEquals(ConferenceCapacityEvaluation.AnchorAdmitted(7), eval)
        assertFalse(snapshot.actualMediaEdges.size == 8 * 7)
    }

    @Test
    fun k4_n8_recoveryTargetsSubsetStar_on() {
        val snapshot = anchorSnapshot(8)
        val targets = RecoveryEdgeProvider.project(snapshot).targets
        assertEquals(7, targets.size)
        assertTrue(targets.all { it.mediaEdge in snapshot.actualMediaEdges })
        assertFalse(targets.any { it.mediaEdge == MediaEdge("M02", "M03") })
    }

    @Test
    fun k5_n10_starEdgeCountIsNMinus1() {
        val snapshot = anchorSnapshot(10)
        assertEquals(9, snapshot.actualMediaEdges.size)
        assertEquals(
            ConferenceCapacityEvaluation.AnchorAdmitted(9),
            ConferenceCapacityGateContract.evaluate(snapshot)
        )
    }

    @Test
    fun k6_n8_meshSnapshotRejected() {
        val mesh = ConferenceTopologyModeTransitionContract.composeMeshSnapshot(
            conferenceId = "c1",
            hostModuleId = "M01",
            members = members(8),
            rosterEpoch = 1L,
            meshGeneration = 99L
        )
        val eval = ConferenceCapacityGateContract.evaluate(mesh)
        assertTrue(eval is ConferenceCapacityEvaluation.Rejected)
        assertTrue(mesh.actualMediaEdges.isEmpty())
    }

    @Test
    fun k7_n8_healthMediaUsable_inflightDoesNotMutate() {
        val snapshot = anchorSnapshot(8)
        val before = snapshot.actualMediaEdges.toSet()
        val projection = ConferenceHealthProjectionContract.project(
            ConferenceHealthProjectionInput(
                snapshot = snapshot,
                mediaObservations = setOf(
                    MediaEdgeUsabilityObservation(MediaEdge("M01", "M02"), usable = true)
                ),
                recovery = RecoveryProgressFact(inFlight = true)
            )
        )
        assertTrue(projection.mediaUsable)
        assertTrue(projection.recoveryInFlight)
        assertEquals(before, snapshot.actualMediaEdges)
        assertEquals(
            ConferenceCapacityEvaluation.AnchorAdmitted(7),
            ConferenceCapacityGateContract.evaluate(snapshot)
        )
    }

    @Test
    fun k8_rosterGrowth_noPeerPairFromRecoveryOrHealth() {
        val n4 = anchorSnapshot(4)
        val n8 = anchorSnapshot(8)
        assertEquals(3, n4.actualMediaEdges.size)
        assertEquals(7, n8.actualMediaEdges.size)
        val grownTargets = RecoveryEdgeProvider.project(n8).targets
        assertTrue(grownTargets.all { it.mediaEdge.anchorModuleId == "M01" })
        assertFalse(grownTargets.any { it.mediaEdge == MediaEdge("M02", "M03") })
        val before = n8.actualMediaEdges.toSet()
        ConferenceHealthProjectionContract.project(
            ConferenceHealthProjectionInput(snapshot = n8)
        )
        assertEquals(before, n8.actualMediaEdges)
        assertEquals(
            ConferenceCapacityEvaluation.AnchorAdmitted(7),
            ConferenceCapacityGateContract.evaluate(n8)
        )
    }
}
