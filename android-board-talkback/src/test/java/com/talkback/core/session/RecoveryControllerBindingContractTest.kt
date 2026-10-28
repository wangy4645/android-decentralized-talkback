package com.talkback.core.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADR-0056 Phase 2-3 — Controller binding contract (C2-A–C2-E). */
class RecoveryControllerBindingContractTest {

    private val harness = RecoveryControllerBindingHarness()

    @Test
    fun c2a_decisionAllow_controllerAcceptsRequest() {
        val snap = harness.anchorSnapshot()
        val accepted = harness.acceptAllowDecision(snap)
        assertTrue(accepted is RecoveryControllerBindingResult.Advanced)
        assertEquals(
            RecoveryControllerPhase.DECISION_ACCEPTED,
            (accepted as RecoveryControllerBindingResult.Advanced).phase
        )
        assertNotNull(harness.state.acceptedRequest)
    }

    @Test
    fun c2b_controllerCannotPublishTopology() {
        val forbidden = RecoveryControllerBindingContract.validateForbiddenControllerAction(
            RecoveryControllerForbiddenAction.PUBLISH_TOPOLOGY
        )
        assertEquals(
            RecoveryControllerRejectCode.FORBIDDEN_CONTROLLER_ACTION,
            forbidden.code
        )
        val ice = RecoveryControllerBindingContract.validateIceDirectMediaReady(iceConnected = true)
        assertNotNull(ice)
        assertEquals(
            RecoveryControllerRejectCode.ICE_DIRECT_MUTATION,
            (ice as RecoveryControllerBindingResult.Rejected).code
        )
    }

    @Test
    fun c2c_staleRequest_rejected() {
        val snap = harness.anchorSnapshot(meshGeneration = 12L)
        harness.acceptAllowDecision(snap)
        val staleSnap = snap.copy(meshGeneration = 13L)
        val begin = harness.beginExecution(staleSnap)
        assertTrue(begin is RecoveryControllerBindingResult.Advanced)
        assertEquals(RecoveryControllerPhase.FAILED, (begin as RecoveryControllerBindingResult.Advanced).phase)
        assertEquals(
            RecoveryExecutionOutcomeKind.ACTION_REJECTED,
            begin.fact?.kind
        )
    }

    @Test
    fun c2d_factRoundTrip_completed() {
        val snap = harness.anchorSnapshot()
        harness.acceptAllowDecision(snap)
        harness.beginExecution(snap)
        val request = harness.state.acceptedRequest!!
        val done = harness.completeWithFact(request, success = true)
        assertTrue(done is RecoveryControllerBindingResult.Advanced)
        assertEquals(RecoveryControllerPhase.COMPLETED, (done as RecoveryControllerBindingResult.Advanced).phase)
        assertEquals(1, harness.facts.size)
    }

    @Test
    fun c2e_duplicateExecution_blocked() {
        val snap = harness.anchorSnapshot()
        harness.acceptAllowDecision(snap)
        harness.beginExecution(snap)
        val duplicate = harness.acceptAllowDecision(snap)
        assertTrue(duplicate is RecoveryControllerBindingResult.Rejected)
    }

    @Test
    fun controllerPhaseProgression_minimalStateMachine() {
        val snap = harness.anchorSnapshot()
        assertEquals(RecoveryControllerPhase.IDLE, harness.state.phase)
        harness.acceptAllowDecision(snap)
        assertEquals(RecoveryControllerPhase.DECISION_ACCEPTED, harness.state.phase)
        harness.beginExecution(snap)
        assertEquals(RecoveryControllerPhase.EXECUTION_IN_FLIGHT, harness.state.phase)
        harness.completeWithFact(harness.state.acceptedRequest!!, success = false)
        assertEquals(RecoveryControllerPhase.FAILED, harness.state.phase)
    }
}
