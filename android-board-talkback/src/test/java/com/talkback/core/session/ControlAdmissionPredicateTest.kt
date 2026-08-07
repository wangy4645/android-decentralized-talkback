package com.talkback.core.session

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlAdmissionPredicateTest {

    private fun record(
        phase: EdgeRecoveryPhase = EdgeRecoveryPhase.REATTACH_REQUESTED,
        delivery: ReattachDeliveryState = ReattachDeliveryState.REMOTE_RECEIPT_ACKED,
        glare: Boolean = false,
        rejected: Boolean = false,
        startedAtMs: Long = 0L
    ) = EdgeRecoveryRecord(
        key = ConferenceEdgeKey("sess", "M02"),
        phase = phase,
        channelId = "CH-1",
        recoveryAttemptId = 1L,
        recoveryStartedAtMs = startedAtMs,
        reattachDeliveryState = delivery,
        unresolvedNegotiationOwnerConflict = glare,
        terminalAdmissionRejected = rejected
    )

    @Test
    fun admissionPending_whenReceiptWithoutControlAdmission() {
        assertTrue(ControlAdmissionPredicate.isAdmissionPending(record()))
    }

    @Test
    fun notAdmissionPending_whenControlAdmitted() {
        assertFalse(
            ControlAdmissionPredicate.isAdmissionPending(
                record(phase = EdgeRecoveryPhase.REATTACH_ACCEPTED)
            )
        )
    }

    @Test
    fun timeoutNotEligible_whenGlarePendingWithinBudget() {
        assertFalse(
            ControlAdmissionPredicate.isRecoveryAttemptTimeoutEligible(
                record = record(glare = true, startedAtMs = 100L),
                nowMs = 300L,
                attemptBudgetMs = 500L
            )
        )
    }

    @Test
    fun timeoutEligible_onExplicitReject() {
        assertTrue(
            ControlAdmissionPredicate.isRecoveryAttemptTimeoutEligible(
                record = record(rejected = true),
                nowMs = 50L,
                attemptBudgetMs = 500L
            )
        )
    }

    @Test
    fun e2Suppressed_whenGlareUnresolved() {
        assertTrue(ControlAdmissionPredicate.shouldSuppressE2Shortcut(record(glare = true)))
    }
}
