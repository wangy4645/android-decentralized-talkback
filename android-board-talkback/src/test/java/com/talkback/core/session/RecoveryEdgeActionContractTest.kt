package com.talkback.core.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADR-0056 Phase 2-0b — Recovery action contract (R2A–R2E). */
class RecoveryEdgeActionContractTest {

    private val harness = RecoveryEdgeActionHarness()

    @Test
    fun r2a_edgeDegraded_permittedActionWithoutTopologyMutation() {
        val snapshot = harness.anchorSnapshot()
        val context = harness.contextForRemote(
            snapshot = snapshot,
            remoteModuleId = "M02",
            lifecycleState = MediaExecutionLifecycleState.ACTIVE
        )
        val evaluation = RecoveryEdgeActionContract.evaluateAction(
            context = context,
            action = RecoveryEdgeAction.REEVALUATE,
            currentSnapshot = snapshot
        )
        assertTrue(evaluation is RecoveryEdgeActionEvaluation.Accepted)
        assertTrue((evaluation as RecoveryEdgeActionEvaluation.Accepted).intentOnly)
        assertFalse(RecoveryEdgeActionContract.producesTopologyMutation(evaluation))
    }

    @Test
    fun r2b_forbiddenAction_rejected() {
        val evaluation = RecoveryEdgeActionContract.evaluateForbiddenAction(
            RecoveryEdgeAction.SELECT_ANCHOR
        )
        assertTrue(evaluation is RecoveryEdgeActionEvaluation.Rejected)
        assertEquals(
            RecoveryEdgeActionRejectCode.REJECT_INVALID_RECOVERY_ACTION,
            (evaluation as RecoveryEdgeActionEvaluation.Rejected).code
        )
        assertEquals(
            RecoveryEdgeActionContract.REJECT_INVALID_RECOVERY_ACTION,
            evaluation.reason
        )
    }

    @Test
    fun r2c_ntiProtection_rejectsMarkActiveWhenUnsettled() {
        val evaluation = RecoveryEdgeActionContract.validateOutcomePublication(
            settlement = NegotiationSettlementTruth.NOT_SETTLED,
            commitPhase = CoordinatorCommitPhase.NOT_COMMITTED,
            claim = RecoveryOutcomeClaim.MEDIA_READY
        )
        assertTrue(evaluation is RecoveryEdgeActionEvaluation.Rejected)
        assertEquals(
            RecoveryEdgeActionRejectCode.REJECT_NTI_VIOLATION,
            (evaluation as RecoveryEdgeActionEvaluation.Rejected).code
        )
    }

    @Test
    fun r2d_lifecycleSeparation_iceDownAllowsRecoveryNotInvalidation() {
        val snapshot = harness.anchorSnapshot()
        val context = harness.contextForRemote(
            snapshot = snapshot,
            remoteModuleId = "M03",
            lifecycleState = MediaExecutionLifecycleState.ACTIVE
        )
        val recovery = RecoveryEdgeActionContract.evaluateAction(
            context = context,
            action = RecoveryEdgeAction.RETRY_NEGOTIATION,
            currentSnapshot = snapshot
        )
        assertTrue(recovery is RecoveryEdgeActionEvaluation.Accepted)

        val topologyAttempt = RecoveryEdgeActionContract.evaluateTopologyLifecycleAttempt(
            RecoveryTopologyLifecycleAttempt.EDGE_INVALIDATED
        )
        assertTrue(topologyAttempt is RecoveryEdgeActionEvaluation.Rejected)
        assertEquals(
            RecoveryEdgeActionRejectCode.REJECT_TOPOLOGY_MUTATION,
            (topologyAttempt as RecoveryEdgeActionEvaluation.Rejected).code
        )
    }

    @Test
    fun r2e_authorizedTransition_validAcceptedStaleRejected() {
        val previous = harness.anchorSnapshot(meshGeneration = 5L, anchorEpoch = 10L)
        val current = ConferenceTopologyContract.failoverSnapshot(previous, "M02")
        val transition = AuthorizedTransition(
            fromMeshGeneration = 5L,
            toMeshGeneration = 6L,
            fromAnchorEpoch = 10L,
            toAnchorEpoch = 11L,
            affectedEdges = previous.actualMediaEdges
        )
        val validContext = harness.contextForRemote(
            snapshot = current,
            remoteModuleId = "M01",
            authorizedTransition = transition
        )
        assertTrue(
            RecoveryEdgeActionContract.validateAuthorizedTransitionContext(
                validContext,
                current,
                previous
            ) is RecoveryEdgeActionEvaluation.Accepted
        )

        val staleContext = validContext.copy(
            mediaEdgeRef = validContext.mediaEdgeRef.copy(meshGeneration = 4L)
        )
        val stale = RecoveryEdgeActionContract.validateAuthorizedTransitionContext(
            staleContext,
            current,
            previous
        )
        assertTrue(stale is RecoveryEdgeActionEvaluation.Rejected)
        assertEquals(
            RecoveryEdgeActionRejectCode.REJECT_STALE_RECOVERY_CONTEXT,
            (stale as RecoveryEdgeActionEvaluation.Rejected).code
        )
    }

    @Test
    fun recoveryFact_doesNotAssertTopologyTruth() {
        val snapshot = harness.anchorSnapshot()
        val context = harness.contextForRemote(snapshot, "M02")
        val ok = RecoveryEdgeActionContract.validateFactSemantics(
            RecoveryEdgeFact.Succeeded(context, RecoveryEdgeAction.ICE_RESTART)
        )
        assertTrue(ok is RecoveryEdgeActionEvaluation.Accepted)

        val bad = RecoveryEdgeActionContract.validateFactSemantics(
            RecoveryEdgeFact.Failed(context, "edge is now topology active")
        )
        assertTrue(bad is RecoveryEdgeActionEvaluation.Rejected)
    }
}
