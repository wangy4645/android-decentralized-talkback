package com.talkback.core.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADR-0056 Phase 4-1 — Anchor topology simulation (A4A–A4F). */
class AnchorTopologySimulationContractTest {

    private val conferenceId = "conf-anchor-sim"

    @Test
    fun a4a_normalAnchorTransition_meshToAnchorThenFailover() {
        val harness = AnchorTransitionHarness()
        val meshToAnchor = harness.seedMeshThenAnchor(conferenceId = conferenceId, anchorId = "M01")
        assertNotNull(meshToAnchor.nextSnapshot)
        assertEquals(ConferenceTopologyMode.ANCHOR, meshToAnchor.nextSnapshot!!.topologyMode)
        assertEquals("M01", meshToAnchor.nextSnapshot!!.anchorId)
        assertTrue(meshToAnchor.newEdgeIds.isNotEmpty())

        val failover = harness.failover(conferenceId = conferenceId, newAnchorId = "M02")
        assertNotNull(failover.nextSnapshot)
        assertEquals("M02", failover.nextSnapshot!!.anchorId)
        assertNotNull(failover.authorizedTransition)
        assertTrue(failover.lifecycleEvents.any { it is TopologyMediaEdgeLifecycleEvent.Invalidated })
        assertTrue(failover.lifecycleEvents.any { it is TopologyMediaEdgeLifecycleEvent.Admitted })
    }

    @Test
    fun a4b_epochMonotonicity() {
        val harness = AnchorTransitionHarness()
        harness.seedAnchor(conferenceId = conferenceId, anchorEpoch = 100L, meshGeneration = 8L)
        val outcome = harness.failover(conferenceId = conferenceId, newAnchorId = "M02")
        val previous = outcome.previousSnapshot!!
        val next = outcome.nextSnapshot!!
        assertTrue(next.anchorEpoch > previous.anchorEpoch)
        assertTrue(next.meshGeneration > previous.meshGeneration)
        assertNull(AnchorTopologySimulationContract.validateEpochMonotonicity(previous, next))
    }

    @Test
    fun a4c_staleAnchorRejection() {
        val harness = AnchorTransitionHarness()
        val before = harness.seedAnchor(conferenceId = conferenceId, anchorEpoch = 105L)
        val stale = harness.simulator.simulateStaleFailover(
            conferenceId = conferenceId,
            staleClaimEpoch = 103L,
            newAnchorId = "M03"
        )
        assertEquals(AnchorSimulationRejectCode.STALE_ANCHOR_CLAIM, stale.rejected)
        assertEquals(before, harness.currentSnapshot(conferenceId))
    }

    @Test
    fun a4d_mediaEdgeIdRegeneration() {
        val harness = AnchorTransitionHarness()
        harness.seedAnchor(conferenceId = conferenceId)
        val outcome = harness.failover(conferenceId = conferenceId, newAnchorId = "M02")
        val previous = outcome.previousSnapshot!!
        val next = outcome.nextSnapshot!!
        val (previousIds, nextIds) = AnchorTopologySimulationContract.diffMediaEdgeIds(previous, next)
        assertTrue(AnchorTopologySimulationContract.edgeIdsMustRegenerate(previousIds, nextIds))
        assertTrue(outcome.invalidatedEdgeIds.isNotEmpty())
        assertTrue(outcome.newEdgeIds.isNotEmpty())
    }

    @Test
    fun a4e_splitBrainRejection() {
        val resolution = AnchorTopologySimulationContract.resolveSplitBrain(
            authorityEpoch = 105L,
            claims = listOf(
                AnchorSplitBrainClaim("M01", anchorEpoch = 105L),
                AnchorSplitBrainClaim("M02", anchorEpoch = 104L)
            )
        )
        assertTrue(resolution is AnchorSplitBrainResolution.Winner)
        assertEquals("M01", (resolution as AnchorSplitBrainResolution.Winner).moduleId)

        val staleOnly = AnchorTopologySimulationContract.resolveSplitBrain(
            authorityEpoch = 105L,
            claims = listOf(
                AnchorSplitBrainClaim("M01", anchorEpoch = 103L),
                AnchorSplitBrainClaim("M02", anchorEpoch = 104L)
            )
        )
        assertTrue(staleOnly is AnchorSplitBrainResolution.Rejected)
    }

    @Test
    fun a4f_recoveryFactIsolation() {
        val harness = AnchorTransitionHarness()
        harness.seedAnchor(conferenceId = conferenceId)
        val outcome = harness.failover(conferenceId = conferenceId, newAnchorId = "M02")
        val oldEdge = outcome.previousSnapshot!!.actualMediaEdges.first()
        val newEdge = outcome.nextSnapshot!!.actualMediaEdges.first()
        val oldRef = TopologyMediaEdgeRef.fromSnapshot(outcome.previousSnapshot!!, oldEdge)
        val newRef = TopologyMediaEdgeRef.fromSnapshot(outcome.nextSnapshot!!, newEdge)
        val oldFact = RecoveryExecutionFact.Outcome(
            mediaEdgeId = oldRef.mediaEdgeId,
            action = RecoveryEdgeAction.ICE_RESTART,
            kind = RecoveryExecutionOutcomeKind.ACTION_FAILED,
            meshGeneration = oldRef.meshGeneration,
            anchorEpoch = oldRef.anchorEpoch,
            reason = "M03 ACTION_FAILED"
        )
        assertTrue(AnchorTopologySimulationContract.recoveryFactAppliesToEdge(oldFact, oldRef))
        assertFalse(AnchorTopologySimulationContract.recoveryFactAppliesToEdge(oldFact, newRef))
        assertTrue(
            AnchorTopologySimulationContract.validateTransitionRecoveryAction(
                AnchorTransitionRecoveryAction.INVALIDATE_OLD_EDGE
            ) is RecoveryAuthorityBoundaryResult.Permitted
        )
        assertTrue(
            AnchorTopologySimulationContract.validateTransitionRecoveryAction(
                AnchorTransitionRecoveryAction.ASSERT_RECOVERY_SUCCESS
            ) is RecoveryAuthorityBoundaryResult.Forbidden
        )
    }

    @Test
    fun anchorSelection_fromObservationsNotIntuition() {
        val rejected = AnchorTopologySimulationContract.validateForbiddenSelectionInput(
            AnchorSimulationForbiddenInput.COORDINATOR_INTUITION
        )
        assertEquals(AnchorSimulationRejectCode.FORBIDDEN_INPUT, rejected.code)
        val evaluation = AnchorTopologySimulationContract.evaluateAnchorSelection(
            AnchorTransitionHarness().selectionObservations(conferenceId, "M02", networkScore = 90L)
        )
        assertTrue(evaluation is AnchorSelectionEvaluation.CandidateReady)
        assertEquals("M02", (evaluation as AnchorSelectionEvaluation.CandidateReady).candidateAnchorId)
    }
}
