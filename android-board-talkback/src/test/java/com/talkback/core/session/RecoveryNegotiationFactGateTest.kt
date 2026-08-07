package com.talkback.core.session

import com.talkback.core.util.MediaRecoveryCausalTrace
import com.talkback.core.util.RecoveryNegotiationObservation
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** RNA-6 / PR-RNA6-A: controller gate — terminalCount == factCount for new closes. */
class RecoveryNegotiationFactGateTest {

    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private var nowMs = 0L
    private var canExecute = false
    private val decisionLogs = mutableListOf<String>()
    private val observationLines = mutableListOf<String>()
    private lateinit var controller: ConferenceEdgeRecoveryController

    private val sessionId = "sess-rna6-gate"
    private val remoteModuleId = "M01"
    private val channelId = "CH-RNA6"

    @Before
    fun setUp() {
        nowMs = 0L
        canExecute = false
        decisionLogs.clear()
        observationLines.clear()
        RecoveryNegotiationObservation.resetForTest { observationLines.add(it) }
        MediaRecoveryCausalTrace.resetForTest { }
        controller = buildController(localModuleId = "M03")
    }

    @After
    fun tearDown() {
        controller.clearAll()
        scheduler.shutdownNow()
        RecoveryNegotiationObservation.resetForTest(null)
        MediaRecoveryCausalTrace.resetForTest(null)
    }

    private fun buildController(localModuleId: String) = ConferenceEdgeRecoveryController(
        localModuleId = localModuleId,
        debounceMs = 20L,
        iceRestartTimeoutMs = 200L,
        attemptBudgetMs = 500L,
        observationWindowMs = 10_000L,
        clock = { nowMs },
        scheduler = scheduler,
        onLog = { decisionLogs.add(it) },
        onRequestReattach = { _, _, _ -> ReattachDispatchOutcome.SENT },
        onIceRestart = { _, _ -> true },
        probeIceRestartGate = { _, _ ->
            if (canExecute) IceRestartGateProbe(executable = true)
            else IceRestartGateProbe(
                executable = false,
                blockReason = IceRestartGateBlockReason.ANSWERER_SETTLING,
                signalingState = "STABLE",
                localRole = "ANSWERER"
            )
        },
        onNegotiationGateDeferred = { _, _, bind -> bind(1L) }
    )

    private fun eligible() = EdgeRecoveryEligibility(
        lifecycleEstablished = true,
        localJoined = true,
        remoteJoined = true,
        conferenceTerminated = false
    )

    private fun bootstrapOwner() {
        controller.onIceStateChanged(
            sessionId = sessionId,
            channelId = channelId,
            remoteModuleId = remoteModuleId,
            iceState = "FAILED",
            eligibility = eligible(),
            initiatesReattach = false
        )
        assertNotNull(controller.negotiationOwnerModuleId(sessionId, remoteModuleId))
    }

    private fun deferIntent(): String {
        bootstrapOwner()
        val intentId = controller.pendingIceRestartIntentId(sessionId, remoteModuleId)
        assertNotNull(intentId)
        return intentId!!
    }

    private fun advanceScheduled(ms: Long) {
        nowMs += ms
        Thread.sleep(ms + 50L)
    }

    private fun terminalCount(intentId: String) =
        observationLines.count {
            it.contains("RECOVERY_NEGOTIATION_INTENT_TERMINAL") && it.contains("intentId=$intentId")
        }

    private fun factCount(intentId: String) =
        observationLines.count {
            it.startsWith("NEGOTIATION_RECOVERY_FACT") && it.contains("intentId=$intentId")
        }

    @Test
    fun gate6_budgetExpired_terminalAndFactPaired() {
        val intentId = deferIntent()
        observationLines.clear()
        decisionLogs.clear()
        advanceScheduled(350L)

        assertEquals(1, terminalCount(intentId))
        assertEquals(1, factCount(intentId))
        assertTrue(observationLines.any { it.contains("terminalState=EXPIRED") })
        assertTrue(observationLines.any { it.contains("closeSource=NEGOTIATION_BUDGET") })
        assertFalse(decisionLogs.any { it.contains("RECOVERY_EDGE_RECOVERED") })
    }

    @Test
    fun gate6_glareTerminal_singleFact_noDuplicateOnDrain() {
        val intentId = deferIntent()
        controller.onNegotiationGlareAcceptRemote(sessionId, remoteModuleId, "GLARE_ACCEPT_REMOTE")
        assertEquals(1, terminalCount(intentId))
        assertEquals(1, factCount(intentId))

        val record = edgeRecord() ?: return
        record.obligationClosedAtMs = nowMs
        record.obligationCloseReason = ObligationCloseReason.MEMBERSHIP_LEFT
        decisionLogs.clear()
        controller.drainPendingIceRestart(sessionId, remoteModuleId)

        assertEquals(1, terminalCount(intentId))
        assertEquals(1, factCount(intentId))
        assertTrue(decisionLogs.any { it.contains("NEGOTIATION_INTENT_CLOSE_SKIPPED") })
    }

    private fun edgeRecord(): EdgeRecoveryRecord? {
        val field = ConferenceEdgeRecoveryController::class.java.getDeclaredField("edges")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val edges = field.get(controller) as ConcurrentHashMap<ConferenceEdgeKey, EdgeRecoveryRecord>
        return edges[ConferenceEdgeKey(sessionId, remoteModuleId)]
    }
}
