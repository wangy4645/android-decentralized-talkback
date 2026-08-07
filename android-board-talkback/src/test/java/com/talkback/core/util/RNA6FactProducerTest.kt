package com.talkback.core.util

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RNA6FactProducerTest {

    private val lines = mutableListOf<String>()

    @Before
    fun setUp() {
        lines.clear()
        RecoveryNegotiationObservation.resetForTest { lines.add(it) }
    }

    @After
    fun tearDown() {
        RecoveryNegotiationObservation.resetForTest(null)
    }

    private fun ctx(intentId: String? = "R1") = RecoveryNegotiationObservation.EdgeObservationContext(
        sessionId = "sess-1",
        edgeModuleId = "M01",
        episodeId = 7L,
        obligationGen = 1L,
        intentId = intentId,
        mediaActionOwnerLabel = "HOST_RESTART",
        deferredReason = "NEGOTIATION_SETTLING",
        existingTransactionOwnerModuleId = "M03",
        recoveryCoordinatorOwnerModuleId = "M03"
    )

    @Test
    fun t1_expired_terminal_emitsFact() {
        val emitted = RecoveryNegotiationObservation.emitNegotiationRecoveryFactFromContext(
            ctx = ctx(),
            intentId = "R1",
            terminalState = "EXPIRED",
            terminalReason = "NEGOTIATION_BUDGET_EXHAUSTED",
            closeSource = "NEGOTIATION_BUDGET",
            ownerModuleId = "M03",
            ownerResolved = true,
            mediaReady = false,
            emittedAtMs = 1000L
        )
        assertTrue(emitted)
        assertEquals(1, lines.size)
        val line = lines.single()
        assertTrue(line.startsWith("NEGOTIATION_RECOVERY_FACT"))
        assertTrue(line.contains("intentId=R1"))
        assertTrue(line.contains("terminalState=EXPIRED"))
        assertTrue(line.contains("closeSource=NEGOTIATION_BUDGET"))
        assertTrue(line.contains("blockedReason=BUDGET_EXHAUSTED"))
        assertTrue(line.contains("transactionClosed=true"))
        assertTrue(line.contains("recoveryAttemptId=7"))
        assertTrue(line.contains("obligationGeneration=1"))
        assertTrue(line.contains("emittedAtMs=1000"))
    }

    @Test
    fun t2_superseded_fact_carriesMediaActionSupersedeSource() {
        RecoveryNegotiationObservation.emitNegotiationRecoveryFactFromContext(
            ctx = ctx("R2"),
            intentId = "R2",
            terminalState = "SUPERSEDED",
            terminalReason = "SUPERSEDED",
            closeSource = "MEDIA_ACTION_SUPERSEDE",
            ownerModuleId = "M03",
            ownerResolved = true,
            mediaReady = true,
            emittedAtMs = 2000L
        )
        val line = lines.single()
        assertTrue(line.contains("terminalState=SUPERSEDED"))
        assertTrue(line.contains("closeSource=MEDIA_ACTION_SUPERSEDE"))
        assertTrue(line.contains("blockedReason=SUPERSEDED"))
    }

    @Test
    fun t3_blockedByGlare_emitsFact() {
        RecoveryNegotiationObservation.emitNegotiationRecoveryFactFromContext(
            ctx = ctx("R3"),
            intentId = "R3",
            terminalState = "BLOCKED_BY_GLARE",
            terminalReason = "GLARE_ACCEPT_REMOTE",
            closeSource = "NEGOTIATION_GLARE",
            ownerModuleId = "M03",
            ownerResolved = true,
            mediaReady = false,
            emittedAtMs = 3000L
        )
        val line = lines.single()
        assertTrue(line.contains("terminalState=BLOCKED_BY_GLARE"))
        assertTrue(line.contains("blockedReason=GLARE"))
    }

    @Test
    fun t4_duplicateClose_oneFactOnly() {
        val first = RecoveryNegotiationObservation.emitNegotiationRecoveryFactFromContext(
            ctx = ctx(),
            intentId = "R1",
            terminalState = "EXPIRED",
            terminalReason = "NEGOTIATION_BUDGET_EXHAUSTED",
            closeSource = "NEGOTIATION_BUDGET",
            ownerModuleId = "M03",
            ownerResolved = true,
            mediaReady = false,
            emittedAtMs = 1000L
        )
        val second = RecoveryNegotiationObservation.emitNegotiationRecoveryFactFromContext(
            ctx = ctx(),
            intentId = "R1",
            terminalState = "EXPIRED",
            terminalReason = "NEGOTIATION_BUDGET_EXHAUSTED",
            closeSource = "NEGOTIATION_BUDGET",
            ownerModuleId = "M03",
            ownerResolved = true,
            mediaReady = false,
            emittedAtMs = 1001L
        )
        assertTrue(first)
        assertFalse(second)
        assertEquals(1, lines.count { it.startsWith("NEGOTIATION_RECOVERY_FACT") })
    }

    @Test
    fun t5_missingIntent_noFact() {
        val emitted = RecoveryNegotiationObservation.emitNegotiationRecoveryFactFromContext(
            ctx = ctx(intentId = null),
            intentId = "NONE",
            terminalState = "EXPIRED",
            terminalReason = "OBLIGATION_CLOSE",
            closeSource = "OBLIGATION_CLOSE",
            ownerModuleId = "M03",
            ownerResolved = true,
            mediaReady = false,
            emittedAtMs = 1000L
        )
        assertFalse(emitted)
        assertTrue(lines.isEmpty())
    }
}
