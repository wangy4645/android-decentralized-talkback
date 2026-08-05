package com.talkback.core.session

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** PR5-1 UT matrix: attempt ownership without episode completion mutation. */
class RecoveryAttemptOwnerTest {

    @After
    fun tearDown() {
        RecoveryAttemptOwner.resetForTest()
    }

    private fun withSilentOwner(block: () -> Unit) {
        RecoveryAttemptOwner.resetForTest { }
        block()
    }

    private fun record(
        phase: EdgeRecoveryPhase,
        deliveryPhase: RecoveryOfferDeliveryPhase = RecoveryOfferDeliveryPhase.NONE,
        dispatchState: ReattachDeliveryState = ReattachDeliveryState.QUEUED,
        attemptId: Long = 1L
    ): EdgeRecoveryRecord {
        val key = ConferenceEdgeKey("sess-pr51", "M02")
        return EdgeRecoveryRecord(
            key = key,
            phase = phase,
            channelId = "CH-1",
            recoveryAttemptId = attemptId,
            recoveryStartedAtMs = 0L,
            recoveryOfferDeliveryPhase = deliveryPhase,
            recoveryOfferLineageId = "L1",
            reattachDeliveryState = dispatchState
        )
    }

    @Test
    fun caseA_offerDispatched_attemptDispatching() {
        val r = record(
            phase = EdgeRecoveryPhase.REATTACH_ACCEPTED,
            dispatchState = ReattachDeliveryState.TRANSPORT_SENT
        )
        withSilentOwner { RecoveryAttemptOwner.reconcileFromFacts(r, "TEST:A") }
        assertEquals(RecoveryAttemptState.ATTEMPT_DISPATCHING, RecoveryAttemptOwner.resolveState(r))
    }

    @Test
    fun caseB_deliveryPending_attemptWaitingDelivery() {
        val r = record(
            phase = EdgeRecoveryPhase.REATTACH_REQUESTED,
            deliveryPhase = RecoveryOfferDeliveryPhase.PENDING
        )
        withSilentOwner { RecoveryAttemptOwner.reconcileFromFacts(r, "TEST:B") }
        assertEquals(RecoveryAttemptState.ATTEMPT_WAITING_DELIVERY, RecoveryAttemptOwner.resolveState(r))
    }

    @Test
    fun caseC_negotiationActive_attemptNegotiating() {
        val r = record(phase = EdgeRecoveryPhase.ICE_RESTARTING)
        withSilentOwner { RecoveryAttemptOwner.reconcileFromFacts(r, "TEST:C") }
        assertEquals(RecoveryAttemptState.ATTEMPT_NEGOTIATING, RecoveryAttemptOwner.resolveState(r))
    }

    @Test
    fun caseD_ackAccepted_attemptSucceeded() {
        val r = record(
            phase = EdgeRecoveryPhase.REATTACH_REQUESTED,
            deliveryPhase = RecoveryOfferDeliveryPhase.CONFIRMED
        )
        withSilentOwner { RecoveryAttemptOwner.reconcileFromFacts(r, "TEST:D") }
        assertEquals(RecoveryAttemptState.ATTEMPT_SUCCEEDED, RecoveryAttemptOwner.resolveState(r))
        assertEquals(true, r.attemptContext?.attemptTerminal)
    }

    @Test
    fun caseE_timeout_attemptFailed() {
        val r = record(
            phase = EdgeRecoveryPhase.REATTACH_REQUESTED,
            deliveryPhase = RecoveryOfferDeliveryPhase.EXHAUSTED
        )
        withSilentOwner { RecoveryAttemptOwner.reconcileFromFacts(r, "TEST:E") }
        assertEquals(RecoveryAttemptState.ATTEMPT_FAILED, RecoveryAttemptOwner.resolveState(r))
        assertEquals(true, r.attemptContext?.attemptTerminal)
    }

    @Test
    fun caseF_attemptTerminal_doesNotMutateEpisodePhase() {
        val r = record(phase = EdgeRecoveryPhase.ICE_RESTARTING)
        withSilentOwner {
            RecoveryAttemptOwner.reconcileFromFacts(r, "TEST:F-setup")
            RecoveryAttemptOwner.transition(r, RecoveryAttemptState.ATTEMPT_SUCCEEDED, "TEST:F-terminal")
        }
        assertEquals(EdgeRecoveryPhase.ICE_RESTARTING, r.phase)
        assertEquals(RecoveryAttemptState.ATTEMPT_SUCCEEDED, RecoveryAttemptOwner.resolveState(r))
    }

    @Test
    fun caseG_observationReadsAttemptContext() {
        val r = record(
            phase = EdgeRecoveryPhase.REATTACH_REQUESTED,
            deliveryPhase = RecoveryOfferDeliveryPhase.PENDING
        )
        withSilentOwner { RecoveryAttemptOwner.reconcileFromFacts(r, "TEST:G") }
        val obs = CompletionObservationProjection.mapAttemptState(r)
        assertEquals(
            CompletionObservationProjection.AttemptObservationState.ATTEMPT_WAITING_DELIVERY,
            obs
        )
    }

    @Test
    fun newAttemptId_resetsAttemptContext() {
        val r = record(phase = EdgeRecoveryPhase.REATTACH_REQUESTED, attemptId = 1L)
        withSilentOwner {
            RecoveryAttemptOwner.reconcileFromFacts(r, "TEST:attempt1")
            r.recoveryAttemptId = 2L
            RecoveryAttemptOwner.reconcileFromFacts(r, "TEST:attempt2")
        }
        assertEquals(2L, r.attemptContext?.attemptId)
        assertNotEquals(RecoveryAttemptState.ATTEMPT_IDLE, RecoveryAttemptOwner.resolveState(r))
    }
}