package com.talkback.core.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADR-0056 Phase 2-2 — Recovery execution boundary (E2A–E2E). */
class RecoveryExecutionBoundaryContractTest {

    private val actionHarness = RecoveryEdgeActionHarness()
    private val executionHarness = RecoveryExecutionHarness()
    private val members4 = listOf("M01", "M02", "M03", "M04")

    private fun snapshot(
        meshGeneration: Long = 10L,
        anchorEpoch: Long = 100L
    ) = ConferenceTopologyContract.composeAnchorAdmission(
        AnchorAdmissionInput(
            conferenceId = "c-exec",
            hostModuleId = "M01",
            anchorId = "M01",
            members = members4,
            meshGeneration = meshGeneration,
            anchorEpoch = anchorEpoch
        )
    )

    private fun decisionInput(
        snap: ConferenceTopologySnapshot,
        remote: String = "M02",
        action: RecoveryEdgeAction = RecoveryEdgeAction.ICE_RESTART
    ) = RecoveryEdgeDecisionInput(
        context = actionHarness.contextForRemote(
            snapshot = snap,
            remoteModuleId = remote,
            lifecycleState = MediaExecutionLifecycleState.ACTIVE
        ),
        requestedAction = action,
        currentSnapshot = snap
    )

    @Test
    fun e2a_validDecision_executionRequestAccepted() {
        val snap = snapshot()
        val admission = executionHarness.decideAndAdmit(decisionInput(snap))
        assertTrue(admission is RecoveryExecutionAdmission.Accepted)
        val request = (admission as RecoveryExecutionAdmission.Accepted).request
        assertEquals(RecoveryExecutionBoundaryContract.SOLE_EXECUTION_CONSUMER, request.consumer)
        assertEquals(RecoveryEdgeAction.ICE_RESTART, request.action)
    }

    @Test
    fun e2b_duplicateSameEdge_singleFlightRejected() {
        val snap = snapshot()
        val input = decisionInput(snap)
        val first = executionHarness.decideAndAdmit(input)
        assertTrue(first is RecoveryExecutionAdmission.Accepted)
        val second = executionHarness.decideAndAdmit(input)
        assertTrue(second is RecoveryExecutionAdmission.Rejected)
        assertEquals(
            RecoveryExecutionRejectCode.SINGLE_FLIGHT_BUSY,
            (second as RecoveryExecutionAdmission.Rejected).code
        )
    }

    @Test
    fun e2c_staleGeneration_cancelStale() {
        val snap = snapshot(meshGeneration = 10L)
        val allow = RecoveryEdgeProvider.decide(decisionInput(snap)) as RecoveryEdgeDecision.Allow
        val request = RecoveryExecutionRequest(
            consumer = RecoveryExecutionBoundaryContract.SOLE_EXECUTION_CONSUMER,
            mediaEdgeId = TopologyMediaEdgeRef.fromSnapshot(snap, allow.target.mediaEdge).mediaEdgeId,
            action = allow.action,
            decisionGeneration = 9L,
            decisionAnchorEpoch = snap.anchorEpoch,
            target = allow.target
        )
        val stale = RecoveryExecutionBoundaryContract.validateInFlightExecution(
            request,
            snap
        )
        assertTrue(stale is RecoveryExecutionAdmission.Rejected)
        assertEquals(
            RecoveryExecutionRejectCode.CANCEL_STALE,
            (stale as RecoveryExecutionAdmission.Rejected).code
        )

        val staleDecision = RecoveryExecutionBoundaryContract.admitExecutionRequest(
            consumer = RecoveryExecutionBoundaryContract.SOLE_EXECUTION_CONSUMER,
            decision = allow.copy(target = allow.target.copy(meshGeneration = 9L)),
            currentSnapshot = snap
        )
        assertTrue(staleDecision is RecoveryExecutionAdmission.Rejected)
        assertEquals(
            RecoveryExecutionRejectCode.STALE_DECISION,
            (staleDecision as RecoveryExecutionAdmission.Rejected).code
        )
    }

    @Test
    fun e2d_executorResult_factOnlyNotLifecycleTruth() {
        val snap = snapshot()
        val admission = executionHarness.decideAndAdmit(decisionInput(snap))
        val request = (admission as RecoveryExecutionAdmission.Accepted).request
        assertTrue(executionHarness.completeExecution(request, success = true) is RecoveryExecutionFactValidity.Valid)

        val badFact = RecoveryExecutionFact.Outcome(
            mediaEdgeId = request.mediaEdgeId,
            action = request.action,
            kind = RecoveryExecutionOutcomeKind.ACTION_COMPLETED,
            meshGeneration = request.decisionGeneration,
            anchorEpoch = request.decisionAnchorEpoch,
            reason = "edge is now lifecycle active"
        )
        assertTrue(
            RecoveryExecutionBoundaryContract.validateExecutionFact(badFact)
                is RecoveryExecutionFactValidity.Invalid
        )
    }

    @Test
    fun e2e_forbiddenTopologyMutation_rejected() {
        val mutation = RecoveryExecutionBoundaryContract.evaluateTopologyMutationAttempt(
            RecoveryExecutionTopologyMutationAttempt.PUBLISH_INVALIDATED
        )
        assertEquals(
            RecoveryExecutionRejectCode.TOPOLOGY_MUTATION_FORBIDDEN,
            mutation.code
        )
    }

    @Test
    fun unauthorizedConsumer_rejected() {
        val snap = snapshot()
        val decision = RecoveryEdgeProvider.decide(decisionInput(snap))
        val admission = RecoveryExecutionBoundaryContract.admitExecutionRequest(
            consumer = "TalkbackCoordinator",
            decision = decision,
            currentSnapshot = snap
        )
        assertTrue(admission is RecoveryExecutionAdmission.Rejected)
        assertEquals(
            RecoveryExecutionRejectCode.UNAUTHORIZED_CONSUMER,
            (admission as RecoveryExecutionAdmission.Rejected).code
        )
    }

    @Test
    fun failureTaxonomy_distinguishesRejectedFailedDeferredCompleted() {
        assertEquals(
            RecoveryExecutionOutcomeKind.ACTION_REJECTED,
            RecoveryExecutionBoundaryContract.classifyExecutorResult(
                success = false,
                deferred = false,
                rejectedInput = true
            )
        )
        assertEquals(
            RecoveryExecutionOutcomeKind.ACTION_FAILED,
            RecoveryExecutionBoundaryContract.classifyExecutorResult(
                success = false,
                deferred = false,
                rejectedInput = false
            )
        )
        assertEquals(
            RecoveryExecutionOutcomeKind.ACTION_DEFERRED,
            RecoveryExecutionBoundaryContract.classifyExecutorResult(
                success = false,
                deferred = true,
                rejectedInput = false
            )
        )
        assertEquals(
            RecoveryExecutionOutcomeKind.ACTION_COMPLETED,
            RecoveryExecutionBoundaryContract.classifyExecutorResult(
                success = true,
                deferred = false,
                rejectedInput = false
            )
        )
    }
}
