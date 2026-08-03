package com.talkback.core.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C0 characterization: Q6-2 control reconciliation predicate on main.
 *
 * Assertion discipline (ADR-0022 Appendix E):
 * - **INVARIANT** — permanent contract; removal requires ADR amendment.
 * - **CURRENT_BEHAVIOR** — pins injected/unwired seams; change only via numbered resolution (§E.18).
 *
 * Note: tests inject [membershipEpochConverged] explicitly. Production default-open
 * `queryMembershipEpochConverged = { _, _ -> true }` is not characterized here — see §E.18.
 */
class ControlReconciliationEvaluatorTest {

    private fun record(
        phase: EdgeRecoveryPhase,
        attemptId: Long = 1L,
        obligationGeneration: Long = 1L,
        outboundAttempt: Long? = null,
        outboundGen: Long? = null
    ): EdgeRecoveryRecord {
        val key = ConferenceEdgeKey("sess-pr52b", "M02")
        return EdgeRecoveryRecord(
            key = key,
            phase = phase,
            channelId = "CH-1",
            recoveryAttemptId = attemptId,
            recoveryStartedAtMs = 0L,
            obligationGeneration = obligationGeneration,
            outboundDispatchAttemptId = outboundAttempt,
            outboundDispatchObligationGeneration = outboundGen
        )
    }

    /** INVARIANT: control handshake not started blocks reconciliation result. */
    @Test
    fun invariant_handshakeFalse_blocksResult() {
        val r = record(phase = EdgeRecoveryPhase.REATTACH_REQUESTED)
        val fact = ControlReconciliationEvaluator.evaluate(r, membershipEpochConverged = true)
        assertFalse(fact.controlHandshakeCompleted)
        assertFalse(fact.result)
        assertEquals("CONTROL_HANDSHAKE_PENDING", fact.mismatchReason())
    }

    /** INVARIANT: all sub-facts true (with explicit membership input) → result true. */
    @Test
    fun invariant_allSubFactsTrue_resultTrue() {
        val r = record(phase = EdgeRecoveryPhase.ICE_RESTARTING)
        val fact = ControlReconciliationEvaluator.evaluate(r, membershipEpochConverged = true)
        assertTrue(fact.controlHandshakeCompleted)
        assertTrue(fact.sessionEpochMatched)
        assertTrue(fact.membershipEpochConverged)
        assertTrue(fact.result)
    }

    /** INVARIANT: outbound dispatch epoch mismatch blocks sessionEpochMatched. */
    @Test
    fun invariant_sessionEpochMismatch_blocksResult() {
        val r = record(
            phase = EdgeRecoveryPhase.ICE_RESTARTING,
            attemptId = 2L,
            outboundAttempt = 1L,
            outboundGen = 1L
        )
        val fact = ControlReconciliationEvaluator.evaluate(r, membershipEpochConverged = true)
        assertTrue(fact.controlHandshakeCompleted)
        assertFalse(fact.sessionEpochMatched)
        assertFalse(fact.result)
        assertEquals("SESSION_EPOCH_MISMATCH", fact.mismatchReason())
    }

    /** INVARIANT: membership epoch not converged blocks result when explicitly false. */
    @Test
    fun invariant_membershipEpochMismatch_blocksResult() {
        val r = record(phase = EdgeRecoveryPhase.ICE_RESTARTING)
        val fact = ControlReconciliationEvaluator.evaluate(r, membershipEpochConverged = false)
        assertTrue(fact.controlHandshakeCompleted)
        assertTrue(fact.sessionEpochMatched)
        assertFalse(fact.membershipEpochConverged)
        assertFalse(fact.result)
        assertEquals("MEMBERSHIP_EPOCH_MISMATCH", fact.mismatchReason())
    }

    /** INVARIANT: projection consumes stored fact; phase transition refreshes reconciliation. */
    @Test
    fun invariant_projectionConsumesStoredFact_notHandshakeProxy() {
        val r = record(phase = EdgeRecoveryPhase.REATTACH_REQUESTED)
        r.controlReconciliationFact = ControlReconciliationEvaluator.evaluate(r, membershipEpochConverged = true)
        assertFalse(CompletionObservationProjection.controlReconciliationCompleted(r))
        r.phase = EdgeRecoveryPhase.ICE_RESTARTING
        r.controlReconciliationFact = ControlReconciliationEvaluator.evaluate(r, membershipEpochConverged = true)
        assertTrue(CompletionObservationProjection.controlReconciliationCompleted(r))
    }

    /** INVARIANT: superseded outbound dispatch cleared → sessionEpochMatched recovers. */
    @Test
    fun invariant_supersededOutbound_cleared_sessionEpochMatches() {
        val r = record(
            phase = EdgeRecoveryPhase.ICE_RESTARTING,
            attemptId = 2L,
            outboundAttempt = 1L,
            outboundGen = 1L
        )
        val staleFact = ControlReconciliationEvaluator.evaluate(r, membershipEpochConverged = true)
        assertFalse(staleFact.sessionEpochMatched)
        r.outboundDispatchAttemptId = null
        r.outboundDispatchObligationGeneration = null
        val fact = ControlReconciliationEvaluator.evaluate(r, membershipEpochConverged = true)
        assertTrue(fact.sessionEpochMatched)
        assertTrue(fact.result)
    }
}
