package com.talkback.core.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADR-0056 Phase 3-0 — Recovery executor adapter (X3A–X3E). */
class RecoveryExecutorAdapterContractTest {

    private val harness = RecoveryExecutorAdapterHarness()
    private val executor = FakeRecoveryExecutor()

    private fun requestFromSnapshot(
        snap: ConferenceTopologySnapshot,
        remote: String = "M02"
    ): RecoveryExecutionRequest {
        val decision = RecoveryEdgeProvider.decide(
            RecoveryEdgeDecisionInput(
                context = RecoveryEdgeActionHarness().contextForRemote(
                    snapshot = snap,
                    remoteModuleId = remote,
                    lifecycleState = MediaExecutionLifecycleState.ACTIVE
                ),
                requestedAction = RecoveryEdgeAction.ICE_RESTART,
                currentSnapshot = snap
            )
        ) as RecoveryEdgeDecision.Allow
        val admission = RecoveryExecutionBoundaryContract.admitExecutionRequest(
            consumer = RecoveryExecutionBoundaryContract.SOLE_EXECUTION_CONSUMER,
            decision = decision,
            currentSnapshot = snap
        ) as RecoveryExecutionAdmission.Accepted
        return admission.request
    }

    @Test
    fun x3a_requestAdapterFact_closedLoop() {
        val snap = harness.anchorSnapshot()
        val request = harness.acceptAndBegin(snap)!!
        val result = harness.executeThroughAdapter(snap, request, inFlightForDuplicateCheck = null)
        assertTrue(result is RecoveryExecutorAdapterResult.FactEmitted)
        val fact = (result as RecoveryExecutorAdapterResult.FactEmitted).fact
        assertEquals(RecoveryExecutionFactSource.EXECUTOR, fact.factSource)
        assertEquals(RecoveryExecutionOutcomeKind.ACTION_COMPLETED, fact.kind)
        assertTrue(harness.fakeExecutor().invokeCount == 1)
    }

    @Test
    fun x3b_staleRequest_notExecuted() {
        val snap = harness.anchorSnapshot(meshGeneration = 10L)
        val request = requestFromSnapshot(snap)
        val staleSnap = snap.copy(meshGeneration = 11L)
        val result = ConferenceRecoveryExecutorAdapter.execute(
            request = request,
            currentSnapshot = staleSnap,
            executor = executor,
            inFlightRequest = null
        )
        assertTrue(result is RecoveryExecutorAdapterResult.FactEmitted)
        val emitted = result as RecoveryExecutorAdapterResult.FactEmitted
        assertFalse(emitted.executorInvoked)
        assertEquals(RecoveryExecutionOutcomeKind.ACTION_REJECTED, emitted.fact.kind)
    }

    @Test
    fun x3c_duplicate_noSecondExecution() {
        val snap = harness.anchorSnapshot()
        val request = harness.acceptAndBegin(snap)!!
        harness.executeThroughAdapter(snap, request, inFlightForDuplicateCheck = null)
        val duplicate = ConferenceRecoveryExecutorAdapter.execute(
            request = request,
            currentSnapshot = snap,
            executor = harness.fakeExecutor(),
            inFlightRequest = request
        )
        assertTrue(duplicate is RecoveryExecutorAdapterResult.FactEmitted)
        val fact = (duplicate as RecoveryExecutorAdapterResult.FactEmitted).fact
        assertEquals(RecoveryExecutionOutcomeKind.ACTION_REJECTED, fact.kind)
        assertEquals(ConferenceRecoveryExecutorAdapter.DUPLICATE_IN_FLIGHT, fact.reason)
        assertFalse(duplicate.executorInvoked)
        assertEquals(1, harness.fakeExecutor().invokeCount)
    }

    @Test
    fun x3d_factDoesNotCarryTopologyAuthority() {
        val snap = harness.anchorSnapshot()
        val request = requestFromSnapshot(snap)
        executor.program(RecoveryExecutionOutcomeKind.ACTION_COMPLETED)
        val result = ConferenceRecoveryExecutorAdapter.execute(
            request = request,
            currentSnapshot = snap,
            executor = executor
        ) as RecoveryExecutorAdapterResult.FactEmitted
        assertEquals(RecoveryExecutionFactSource.EXECUTOR, result.fact.factSource)
        assertTrue(
            RecoveryExecutionBoundaryContract.validateExecutionFact(result.fact)
                is RecoveryExecutionFactValidity.Valid
        )
        val bad = result.fact.copy(reason = "edge is now topology active")
        assertTrue(
            RecoveryExecutionBoundaryContract.validateExecutionFact(bad)
                is RecoveryExecutionFactValidity.Invalid
        )
    }

    @Test
    fun x3e_executorFailure_isExecutionFactNotTopologyFailure() {
        val snap = harness.anchorSnapshot()
        val request = requestFromSnapshot(snap)
        executor.program(
            RecoveryExecutionOutcomeKind.ACTION_FAILED,
            reason = "controlled executor failure"
        )
        val result = ConferenceRecoveryExecutorAdapter.execute(
            request = request,
            currentSnapshot = snap,
            executor = executor
        ) as RecoveryExecutorAdapterResult.FactEmitted
        assertTrue(ConferenceRecoveryExecutorAdapter.isExecutionFailureOnly(result.fact))
        assertEquals(RecoveryExecutionOutcomeKind.ACTION_FAILED, result.fact.kind)
        assertEquals(RecoveryExecutionFactSource.EXECUTOR, result.fact.factSource)
    }
}
