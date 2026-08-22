package com.talkback.core.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 2-0 contract fixtures R1–R8 (recovery binding).
 * Exit: R1–R8 PASS → 2-0 contract PASS → Recovery Controller still FROZEN.
 */
class ConferenceRecoveryBindingPhase20FixturesTest {

    private val members4 = listOf("M01", "M02", "M03", "M04")

    private fun anchorSnapshot(
        anchor: String = "M01",
        host: String = "M01",
        anchorEpoch: Long = 10L,
        meshGeneration: Long = 31L
    ) = ConferenceTopologyContract.composeAnchorAdmission(
        AnchorAdmissionInput(
            conferenceId = "c1",
            hostModuleId = host,
            anchorId = anchor,
            members = members4,
            rosterEpoch = 1L,
            anchorEpoch = anchorEpoch,
            meshGeneration = meshGeneration
        )
    )

    @Test
    fun r1_currentGenerationAdmittedEdge_isRecoveryTarget() {
        val snapshot = anchorSnapshot(meshGeneration = 5L, anchorEpoch = 100L)
        val projected = ConferenceRecoveryBindingContract.project(snapshot)
        assertEquals(snapshot.actualMediaEdges.size, projected.targets.size)
        assertEquals(5L, projected.meshGeneration)
        snapshot.actualMediaEdges.forEach { edge ->
            assertTrue(ConferenceRecoveryBindingContract.isEligible(edge, snapshot))
        }
        assertTrue(projected.targets.all { it.source == RecoveryTargetSource.CURRENT_GENERATION })
    }

    @Test
    fun r2_rosterPairAbsentFromActualMediaEdgeSet_notRecoverable() {
        val snapshot = anchorSnapshot()
        assertTrue("M02" in snapshot.members && "M03" in snapshot.members)
        val rosterPair = MediaEdge("M02", "M03")
        assertFalse(rosterPair in snapshot.actualMediaEdges)
        assertFalse(ConferenceRecoveryBindingContract.isEligible(rosterPair, snapshot))
    }

    @Test
    fun r3_iceConnectedNotAdmitted_notRecoverable() {
        val snapshot = anchorSnapshot()
        val obs = IceEdgeObservation("M02", "M03", iceConnected = true)
        assertTrue(obs.iceConnected)
        assertFalse(ConferenceTopologyContract.isAdmittedByTopology(obs, snapshot))
        assertFalse(ConferenceRecoveryBindingContract.iceObservationIsRecoverable(obs, snapshot))
    }

    @Test
    fun r4_admittedEdgeReconnecting_stillRecoveryTarget() {
        val snapshot = anchorSnapshot()
        val edge = MediaEdge("M01", "M02")
        val obs = IceEdgeObservation(
            localModuleId = "M01",
            remoteModuleId = "M02",
            iceConnected = false,
            iceReconnecting = true
        )
        assertTrue(ConferenceTopologyContract.edgeStillAdmitted(edge, snapshot))
        assertTrue(ConferenceRecoveryBindingContract.isEligible(edge, snapshot))
        assertTrue(ConferenceRecoveryBindingContract.iceObservationIsRecoverable(obs, snapshot))
        assertEquals(31L, snapshot.meshGeneration)
    }

    @Test
    fun r5_generationChange_withoutTransition_invalidatesOldTargets() {
        val s0 = anchorSnapshot(meshGeneration = 31L)
        val s1 = s0.copy(meshGeneration = 32L)
        val oldTarget = RecoveryEdgeProvider.project(s0).targets.first {
            it.mediaEdge == MediaEdge("M01", "M02")
        }
        assertFalse(
            ConferenceRecoveryBindingContract.isEligibleTarget(oldTarget, s1)
        )
        assertTrue(
            ConferenceRecoveryBindingContract.isEligible(MediaEdge("M01", "M02"), s1)
        )
        val projected = ConferenceRecoveryBindingContract.project(s1)
        assertTrue(projected.targets.all { it.meshGeneration == 32L })
    }

    @Test
    fun r6_anchorFailover_oldAnchorEdges_notRecoveryTargets() {
        val s0 = anchorSnapshot(anchor = "M01", anchorEpoch = 10L, meshGeneration = 31L)
        val s1 = ConferenceTopologyContract.failoverSnapshot(s0, "M02")
        assertFalse(ConferenceRecoveryBindingContract.isEligible(MediaEdge("M01", "M03"), s1))
        assertTrue(ConferenceRecoveryBindingContract.isEligible(MediaEdge("M02", "M01"), s1))
        val projected = ConferenceRecoveryBindingContract.project(s1)
        assertTrue(projected.targets.all { it.mediaEdge.anchorModuleId == "M02" })
    }

    @Test
    fun r7_explicitAuthorizedTransition_allowsListedHandoffEdge() {
        val s0 = anchorSnapshot(anchor = "M01", anchorEpoch = 10L, meshGeneration = 31L)
        val s1 = ConferenceTopologyContract.failoverSnapshot(s0, "M02")
        val listed = MediaEdge("M01", "M02")
        val notListed = MediaEdge("M01", "M03")
        val transition = AuthorizedTransition(
            fromMeshGeneration = 31L,
            toMeshGeneration = 32L,
            fromAnchorEpoch = 10L,
            toAnchorEpoch = 11L,
            affectedEdges = setOf(listed)
        )
        assertTrue(
            ConferenceRecoveryBindingContract.validateAuthorizedTransition(transition, s1, s0)
                is TransitionValidity.Valid
        )
        val projected = ConferenceRecoveryBindingContract.project(
            snapshot = s1,
            previousSnapshot = s0,
            authorizedTransitions = setOf(transition)
        )
        assertTrue(ConferenceRecoveryBindingContract.isEligible(listed, s1, s0, setOf(transition)))
        assertFalse(ConferenceRecoveryBindingContract.isEligible(notListed, s1, s0, setOf(transition)))
        assertTrue(
            projected.targets.any {
                it.mediaEdge == listed && it.source == RecoveryTargetSource.AUTHORIZED_TRANSITION
            }
        )
        s1.actualMediaEdges.forEach { edge ->
            assertTrue(ConferenceRecoveryBindingContract.isEligible(edge, s1, s0, setOf(transition)))
        }
        val wildcard = transition.copy(affectedEdges = emptySet())
        assertTrue(
            ConferenceRecoveryBindingContract.validateAuthorizedTransition(wildcard, s1, s0)
                is TransitionValidity.Invalid
        )
    }

    @Test
    fun r8_provider_doesNotMutateTopologySnapshot() {
        val snapshot = anchorSnapshot(host = "M02")
        val before = snapshot.copy(
            members = snapshot.members.toList(),
            actualMediaEdges = snapshot.actualMediaEdges.toSet()
        )
        ConferenceRecoveryBindingContract.project(snapshot)
        assertEquals(before, snapshot)
        assertEquals(before.actualMediaEdges, snapshot.actualMediaEdges)
        val mesh = ConferenceTopologyModeTransitionContract.composeMeshSnapshot(
            conferenceId = "c1",
            hostModuleId = "M01",
            members = listOf("M01", "M02", "M03"),
            rosterEpoch = 1L,
            meshGeneration = 2L
        )
        assertTrue(ConferenceRecoveryBindingContract.project(mesh).targets.isEmpty())
    }
}
