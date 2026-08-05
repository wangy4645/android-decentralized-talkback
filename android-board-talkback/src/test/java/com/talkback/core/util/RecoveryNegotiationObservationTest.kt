package com.talkback.core.util

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RecoveryNegotiationObservationTest {

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

    @Test
    fun ownerResolved_emitsContractFields() {
        RecoveryNegotiationObservation.emitOwnerResolved(
            sessionId = "sess-1",
            edgeModuleId = "M01",
            episodeId = 42L,
            localModuleId = "M03",
            existingTransactionOwnerModuleId = "M03",
            recoveryCoordinatorOwnerModuleId = null,
            trigger = "DEFER_ADMISSION"
        )
        val line = lines.single()
        assertTrue(line.startsWith("RECOVERY_NEGOTIATION_OWNER_RESOLVED"))
        assertTrue(line.contains("sessionId=sess-1"))
        assertTrue(line.contains("edge=M01"))
        assertTrue(line.contains("episodeId=42"))
        assertTrue(line.contains("selectedOwner=M03"))
        assertTrue(line.contains("rule=existing_owner"))
        assertTrue(line.contains("trigger=DEFER_ADMISSION"))
    }

    @Test
    fun shadowResolveOwner_usesModuleTiebreakerWhenNoOwners() {
        val resolution = RecoveryNegotiationObservation.shadowResolveOwner(
            localModuleId = "M01",
            remoteModuleId = "M03",
            existingTransactionOwnerModuleId = null,
            recoveryCoordinatorOwnerModuleId = null
        )
        assertEquals("M03", resolution.selectedOwner)
        assertEquals(RecoveryNegotiationObservation.OwnerRule.module_tiebreaker, resolution.rule)
    }

    @Test
    fun intent_emitsLifecycleStateAndShadowEpoch() {
        RecoveryNegotiationObservation.emitIntent(
            sessionId = "sess-1",
            edgeModuleId = "M01",
            intentId = "R2",
            episodeId = 7L,
            ownerModuleId = "M03",
            reason = "NEGOTIATION_SETTLING",
            state = RecoveryNegotiationObservation.IntentState.DEFERRED
        )
        val line = lines.single()
        assertTrue(line.startsWith("RECOVERY_NEGOTIATION_INTENT"))
        assertTrue(line.contains("intentId=R2"))
        assertTrue(line.contains("negotiationEpoch=0"))
        assertTrue(line.contains("epochSource=SHADOW_UNWIRED"))
        assertTrue(line.contains("state=DEFERRED"))
    }

    @Test
    fun glareDecision_emitsExplicitLegacyDuplicateDrop() {
        RecoveryNegotiationObservation.emitGlareDecision(
            sessionId = "sess-1",
            edgeModuleId = "M01",
            episodeId = 9L,
            localModuleId = "M03",
            localSignalingState = "HAVE_LOCAL_OFFER",
            localDescType = "OFFER",
            remoteDescType = "OFFER",
            localOwner = "M03",
            remoteOwner = "M01",
            decision = RecoveryNegotiationObservation.GlareDecision.DROP_DUPLICATE_LEGACY,
            reason = "ICE_CONNECTED_DUPLICATE",
            glareDetected = true
        )
        val line = lines.single()
        assertTrue(line.startsWith("RECOVERY_GLARE_DECISION"))
        assertTrue(line.contains("glareDetected=true"))
        assertTrue(line.contains("decision=DROP_DUPLICATE_LEGACY"))
        assertTrue(line.contains("localState=HAVE_LOCAL_OFFER"))
    }

    @Test
    fun intentTerminal_emitsTerminalStateAndReason() {
        RecoveryNegotiationObservation.emitIntentTerminal(
            sessionId = "sess-1",
            edgeModuleId = "M01",
            intentId = "R2",
            terminalState = "MISSING",
            reason = "ATTEMPT_TIMEOUT"
        )
        val line = lines.single()
        assertTrue(line.startsWith("RECOVERY_NEGOTIATION_INTENT_TERMINAL"))
        assertTrue(line.contains("terminalState=MISSING"))
        assertTrue(line.contains("reason=ATTEMPT_TIMEOUT"))
    }
}