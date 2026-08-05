package com.talkback.core.util

import com.talkback.core.session.ControlReconciliationFact
import com.talkback.core.session.ConferenceEdgeKey
import com.talkback.core.session.EdgeRecoveryPhase
import com.talkback.core.session.EdgeRecoveryRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C0 characterization: control reconciliation fact log contract on main.
 *
 * Assertion discipline (ADR-0022 Appendix E):
 * - **INVARIANT** — permanent contract; removal requires ADR amendment.
 */
class RecoveryControlReconciliationFactTest {

    private fun record(phase: EdgeRecoveryPhase): EdgeRecoveryRecord {
        val key = ConferenceEdgeKey("sess-pr52b", "M02")
        return EdgeRecoveryRecord(
            key = key,
            phase = phase,
            channelId = "CH-1",
            recoveryAttemptId = 7L,
            recoveryStartedAtMs = 0L,
            obligationGeneration = 5L
        )
    }

    /** INVARIANT: emit includes episode, attempt, and sub-fact fields for audit. */
    @Test
    fun invariant_emit_includesEpisodeAndAttemptFields() {
        val lines = mutableListOf<String>()
        RecoveryControlReconciliationFact.resetForTest { lines.add(it) }
        val r = record(EdgeRecoveryPhase.ICE_RESTARTING)
        val fact = ControlReconciliationFact(
            controlHandshakeCompleted = true,
            sessionEpochMatched = false,
            membershipEpochConverged = true,
            computedAtMs = 100L,
            attemptId = 7L,
            obligationGeneration = 5L
        )
        RecoveryControlReconciliationFact.emit(r, fact)
        val line = lines.single()
        assertTrue(line.startsWith("RECOVERY_CONTROL_RECONCILIATION_FACT"))
        assertTrue(line.contains("session=sess-pr52b"))
        assertTrue(line.contains("remote=M02"))
        assertTrue(line.contains("episodeId=5"))
        assertTrue(line.contains("recoveryAttemptId=7"))
        assertTrue(line.contains("controlHandshakeCompleted=true"))
        assertTrue(line.contains("sessionEpochMatched=false"))
        assertTrue(line.contains("membershipEpochConverged=true"))
        assertTrue(line.contains("result=false"))
        assertTrue(line.contains("reason=SESSION_EPOCH_MISMATCH"))
        RecoveryControlReconciliationFact.resetForTest()
    }

    /** INVARIANT: override sink bypasses test sink (production log path preserved). */
    @Test
    fun invariant_emit_overrideSink_bypassesTestSink() {
        val testLines = mutableListOf<String>()
        val overrideLines = mutableListOf<String>()
        RecoveryControlReconciliationFact.resetForTest { testLines.add(it) }
        val r = record(EdgeRecoveryPhase.ICE_RESTARTING)
        val fact = ControlReconciliationFact(
            controlHandshakeCompleted = true,
            sessionEpochMatched = true,
            membershipEpochConverged = true,
            computedAtMs = 100L,
            attemptId = 7L,
            obligationGeneration = 5L
        )
        RecoveryControlReconciliationFact.emit(r, fact, overrideLines::add)
        assertTrue(testLines.isEmpty())
        assertEquals(1, overrideLines.size)
        RecoveryControlReconciliationFact.resetForTest()
    }

    /** INVARIANT: DIGEST_REFRESH audit fields appear when provided (ADR-0036 Fix-D). */
    @Test
    fun invariant_emit_includesDigestRefreshAuditFields() {
        val lines = mutableListOf<String>()
        RecoveryControlReconciliationFact.resetForTest { lines.add(it) }
        val r = record(EdgeRecoveryPhase.ICE_RESTARTING)
        val fact = ControlReconciliationFact(
            controlHandshakeCompleted = true,
            sessionEpochMatched = true,
            membershipEpochConverged = true,
            computedAtMs = 100L,
            attemptId = 7L,
            obligationGeneration = 5L
        )
        RecoveryControlReconciliationFact.emit(
            r,
            fact,
            digestAudit = RecoveryControlReconciliationFact.DigestRefreshAudit(
                oldDigestEpoch = 3L,
                oldDigestHash = -925203082,
                newDigestEpoch = 1L,
                newDigestHash = -528664596
            )
        )
        val line = lines.single()
        assertTrue(line.contains("oldDigestEpoch=3"))
        assertTrue(line.contains("oldDigestHash=-925203082"))
        assertTrue(line.contains("newDigestEpoch=1"))
        assertTrue(line.contains("newDigestHash=-528664596"))
        assertTrue(line.contains("membershipEpochConverged=true"))
        RecoveryControlReconciliationFact.resetForTest()
    }
}
