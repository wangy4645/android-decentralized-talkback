package com.talkback.core.session

import com.talkback.core.util.RecoveryNegotiationObservation
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.Executors

/**
 * Gate 3C-C / RNA-5.1 - negotiation intent creation invariants (no ghost intents).
 */
class NegotiationIntentCreationInvariantTest {
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private var canExecute = true
    private var dispatchReady = true
    private val decisionLogs = mutableListOf<String>()
    private val observationLines = mutableListOf<String>()
    private lateinit var controller: ConferenceEdgeRecoveryController

    private val sessionId = "sess-rna51-invariant"
    private val remoteModuleId = "M03"
    private val channelId = "CH-INV"

    @Before
    fun setUp() {
        canExecute = true
        dispatchReady = true
        decisionLogs.clear()
        observationLines.clear()
        RecoveryNegotiationObservation.resetForTest { observationLines.add(it) }
        controller = buildController()
    }

    @After
    fun tearDown() {
        controller.clearAll()
        RecoveryNegotiationObservation.resetForTest(null)
        scheduler.shutdownNow()
    }

    private fun buildController() = ConferenceEdgeRecoveryController(
        localModuleId = "M02",
        debounceMs = 20L,
        iceRestartTimeoutMs = 200L,
        attemptBudgetMs = 5_000L,
        observationWindowMs = 10_000L,
        clock = { System.currentTimeMillis() },
        scheduler = scheduler,
        onLog = { decisionLogs.add(it) },
        onRequestReattach = { _, _, _ -> ReattachDispatchOutcome.SENT },
        onIceRestart = { _, _ -> true },
        canDispatchRecoveryMediaAction = { _, _ -> dispatchReady },
        probeIceRestartGate = { _, _ ->
            if (canExecute) {
                IceRestartGateProbe(executable = true)
            } else {
                IceRestartGateProbe(
                    executable = false,
                    blockReason = IceRestartGateBlockReason.OFFER_AWAITING_ANSWER,
                    signalingState = "HAVE_LOCAL_OFFER",
                    localRole = "OFFERER"
                )
            }
        },
        onNegotiationGateDeferred = { _, _, bind -> bind(1L) }
    )

    private fun eligible() = EdgeRecoveryEligibility(
        lifecycleEstablished = true,
        localJoined = true,
        remoteJoined = true,
        conferenceTerminated = false
    )

    @Test
    fun mediaNotReadyDefer_emitsMediaFactsOnly_noNegotiationIntent() {
        dispatchReady = false
        controller.onRecoveryReattachInboundDeferred(
            sessionId = sessionId,
            channelId = channelId,
            remoteModuleId = remoteModuleId,
            reason = DeferredReason.MEDIA_NOT_READY,
            trigger = "INBOUND_REATTACH"
        )

        assertTrue(decisionLogs.any { it.contains("RECOVERY_MEDIA_ACTION_DEFERRED") })
        assertFalse(observationLines.any { it.startsWith("RECOVERY_NEGOTIATION_INTENT") })
        assertFalse(observationLines.any { it.contains("intentId=NONE") })
    }

    @Test
    fun negotiationSettlingDefer_emitsNegotiationIntentWithCommittedIntentId() {
        canExecute = false
        controller.onIceStateChanged(
            sessionId = sessionId,
            channelId = channelId,
            remoteModuleId = remoteModuleId,
            iceState = "FAILED",
            eligibility = eligible(),
            initiatesReattach = false
        )

        val intentId = controller.pendingIceRestartIntentId(sessionId, remoteModuleId)
        assertNotNull(intentId)

        assertTrue(
            observationLines.any {
                it.startsWith("RECOVERY_NEGOTIATION_INTENT") &&
                    it.contains("intentId=$intentId") &&
                    it.contains("state=DEFERRED")
            }
        )
        assertTrue(
            observationLines.any {
                it.startsWith("RECOVERY_NEGOTIATION_INTENT") &&
                    it.contains("intentId=$intentId") &&
                    it.contains("state=CREATED")
            }
        )
        assertFalse(observationLines.any { it.contains("intentId=NONE") })
    }
}