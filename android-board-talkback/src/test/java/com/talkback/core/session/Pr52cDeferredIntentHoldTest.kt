package com.talkback.core.session

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import com.talkback.core.util.RecoveryNegotiationObservation
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * PR5-2c-C — HELD(dispatch) + dispatch-readiness retry (ADR-0022 E.14.8).
 */
class Pr52cDeferredIntentHoldTest {
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private var nowMs = 0L
    private var iceRestartCalls = 0
    private var canExecute = true
    private var dispatchReady = true
    private var iceConnected = false
    private val decisionLogs = mutableListOf<String>()
    private val observationLines = mutableListOf<String>()
    private val admissionSeqCounter = AtomicLong(0L)
    private val capabilityObservation = NegotiationCapabilityObservation()
    private lateinit var controller: ConferenceEdgeRecoveryController

    private val sessionId = "sess-pr52c-hold"
    private val remoteModuleId = "M03"

    @Before
    fun setUp() {
        nowMs = 0L
        iceRestartCalls = 0
        canExecute = true
        dispatchReady = true
        iceConnected = false
        decisionLogs.clear()
        observationLines.clear()
        admissionSeqCounter.set(0L)
        capabilityObservation.clearAll()
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
        clock = { nowMs },
        scheduler = scheduler,
        onLog = { decisionLogs.add(it) },
        onRequestReattach = { _, _, _ -> ReattachDispatchOutcome.SENT },
        onIceRestart = { _, _ ->
            iceRestartCalls++
            true
        },
        isIceConnected = { _, _ -> iceConnected },
        canDispatchRecoveryMediaAction = { _, _ -> dispatchReady },
        evaluateRecoveryAdmission = { _, _ ->
            if (dispatchReady) {
                defaultRecoveryAdmissionProjection()
            } else {
                PeerSignalingReachabilityProjection(
                    confidence = PeerSignalingReachabilityConfidence.MEDIUM,
                    decision = AdmissionDecisionProjection.WAITING_STALE,
                    reason = AdmissionConfidenceReason.INBOUND_STALE_FOR_RECOVERY_DISPATCH,
                    lastInboundAgeMs = 60_000L
                )
            }
        },
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
        onNegotiationGateDeferred = { sid, rid, bindAdmissionSeq ->
            val seq = capabilityObservation.establishDeferredBaseline(sid, rid)
            admissionSeqCounter.set(seq)
            bindAdmissionSeq(seq)
        }
    )

    private fun eligible() = EdgeRecoveryEligibility(
        lifecycleEstablished = true,
        localJoined = true,
        remoteJoined = true,
        conferenceTerminated = false
    )

    private fun admitDeferredNegotiationIntent(): String {
        canExecute = false
        controller.onIceStateChanged(
            sessionId = sessionId,
            channelId = "CH-01",
            remoteModuleId = remoteModuleId,
            iceState = "FAILED",
            eligibility = eligible(),
            initiatesReattach = false
        )
        val intentId = controller.pendingIceRestartIntentId(sessionId, remoteModuleId)
        assertNotNull(intentId)
        return intentId!!
    }

    @Test
    fun probeFalse_dispatchTrue_heldNegotiation() {
        val intentId = admitDeferredNegotiationIntent()
        dispatchReady = true
        decisionLogs.clear()
        controller.drainPendingIceRestart(sessionId, remoteModuleId)

        assertEquals(0, iceRestartCalls)
        assertNotNull(controller.pendingIceRestartIntentId(sessionId, remoteModuleId))
        assertTrue(
            decisionLogs.any {
                it.contains("DEFERRED_INTENT_HELD") &&
                    it.contains("hold=NEGOTIATION") &&
                    it.contains("intentId=$intentId")
            }
        )
        assertFalse(decisionLogs.any { it.contains("hold=DISPATCH") })
    }

    @Test
    fun probeTrue_dispatchFalse_heldDispatch() {
        val intentId = admitDeferredNegotiationIntent()
        canExecute = true
        dispatchReady = false
        val admissionSeq = admissionSeqCounter.get()
        decisionLogs.clear()

        controller.drainPendingIceRestart(
            sessionId,
            remoteModuleId,
            capabilityEventObservationSeq = admissionSeq + 1
        )

        assertEquals(0, iceRestartCalls)
        assertNotNull(controller.pendingIceRestartIntentId(sessionId, remoteModuleId))
        assertTrue(
            decisionLogs.any {
                it.contains("DEFERRED_INTENT_HELD") &&
                    it.contains("hold=DISPATCH") &&
                    it.contains("intentId=$intentId")
            }
        )
    }

    @Test
    fun heldDispatch_dispatchSeam_retry_executes() {
        val intentId = admitDeferredNegotiationIntent()
        canExecute = true
        dispatchReady = false
        val admissionSeq = admissionSeqCounter.get()
        controller.drainPendingIceRestart(
            sessionId,
            remoteModuleId,
            capabilityEventObservationSeq = admissionSeq + 1
        )
        assertTrue(decisionLogs.any { it.contains("hold=DISPATCH") })

        dispatchReady = true
        decisionLogs.clear()
        iceRestartCalls = 0
        controller.retryHeldDeferredIntentDrain(sessionId, remoteModuleId, "ROUTE_CONVERGED")

        assertEquals(1, iceRestartCalls)
        assertNull(controller.pendingIceRestartIntentId(sessionId, remoteModuleId))
        assertTrue(decisionLogs.any { it.contains("DEFERRED_INTENT_DRAIN_RETRY") })
        assertTrue(decisionLogs.any { it.contains("DEFERRED_INTENT_EXECUTED") && it.contains("intentId=$intentId") })
        assertTrue(decisionLogs.any { it.contains("DEFERRED_INTENT_DRAIN_ATTEMPT") && it.contains("trigger=DISPATCH_READINESS_RETRY") })
    }

    @Test
    fun retry_probeFalse_heldNegotiation() {
        admitDeferredNegotiationIntent()
        canExecute = true
        dispatchReady = false
        val admissionSeq = admissionSeqCounter.get()
        controller.drainPendingIceRestart(
            sessionId,
            remoteModuleId,
            capabilityEventObservationSeq = admissionSeq + 1
        )

        canExecute = false
        dispatchReady = true
        decisionLogs.clear()
        controller.retryHeldDeferredIntentDrain(sessionId, remoteModuleId, "ROUTE_CONVERGED")

        assertEquals(0, iceRestartCalls)
        assertTrue(
            decisionLogs.any {
                it.contains("DEFERRED_INTENT_HELD") &&
                    it.contains("hold=NEGOTIATION")
            }
        )
    }

    @Test
    fun heldDispatch_supersede_retry_doesNotExecuteOldIntent() {
        val r1 = admitDeferredNegotiationIntent()
        canExecute = true
        dispatchReady = false
        val r1AdmissionSeq = admissionSeqCounter.get()
        controller.drainPendingIceRestart(
            sessionId,
            remoteModuleId,
            capabilityEventObservationSeq = r1AdmissionSeq + 1
        )

        canExecute = false
        controller.onRecoveryReattachAccepted(
            sessionId = sessionId,
            remoteModuleId = remoteModuleId,
            recoveryReason = RecoveryReason.NETWORK_RECOVERY,
            source = RecoverySource.ICE_MONITOR
        )
        assertTrue(decisionLogs.any { it.contains("DEFERRED_INTENT_SUPERSEDED") && it.contains("oldIntent=$r1") })
        assertTrue(
            decisionLogs.any {
                it.contains("NEGOTIATION_INTENT_CLOSE_REQUEST") &&
                    it.contains("intentId=$r1") &&
                    it.contains("source=MEDIA_ACTION_SUPERSEDE")
            }
        )
        assertTrue(
            observationLines.any {
                it.contains("RECOVERY_NEGOTIATION_INTENT_TERMINAL") &&
                    it.contains("intentId=$r1") &&
                    it.contains("terminalState=SUPERSEDED") &&
                    it.contains("reason=SUPERSEDED")
            }
        )

        val r2 = controller.pendingIceRestartIntentId(sessionId, remoteModuleId)
        assertNotNull(r2)
        assertTrue(r2 != r1)

        dispatchReady = true
        decisionLogs.clear()
        iceRestartCalls = 0
        controller.retryHeldDeferredIntentDrain(sessionId, remoteModuleId, "ROUTE_CONVERGED")

        assertEquals(0, iceRestartCalls)
        assertFalse(decisionLogs.any { it.contains("intentId=$r1") && it.contains("EXECUTED") })
    }
}