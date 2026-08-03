package com.talkback.core.session

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * Joint harness wiring: Pr52cDebugInjection.blockDispatch must force
 * canDispatchRecoveryMediaAction=false on the field-shaped probe path.
 *
 * Does not change production readiness when injection is inactive.
 */
class Pr52cBlockReadinessWiringTest {
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private var nowMs = 0L
    private var iceRestartCalls = 0
    private var canExecute = true
    private var productionDispatchReady = true
    private val decisionLogs = mutableListOf<String>()
    private val admissionSeqCounter = AtomicLong(0L)
    private val capabilityObservation = NegotiationCapabilityObservation()
    private lateinit var controller: ConferenceEdgeRecoveryController

    private val sessionId = "sess-pr52c-block-wiring"
    private val channelId = "CH-01"
    private val remoteModuleId = "M03"

    @Before
    fun setUp() {
        nowMs = 0L
        iceRestartCalls = 0
        canExecute = true
        productionDispatchReady = true
        decisionLogs.clear()
        admissionSeqCounter.set(0L)
        capabilityObservation.clearAll()
        Pr52cDebugInjection.clearEdge(sessionId, remoteModuleId)
        controller = buildController()
    }

    @After
    fun tearDown() {
        controller.clearAll()
        Pr52cDebugInjection.clearEdge(sessionId, remoteModuleId)
        scheduler.shutdownNow()
    }

    /**
     * Mirrors TalkbackCoordinator field wiring after this harness PR:
     * debug BLOCK short-circuits readiness; otherwise production probe.
     */
    private fun fieldShapedCanDispatch(sid: String, rid: String): Boolean {
        if (Pr52cDebugInjection.isDispatchBlocked(sid, rid)) return false
        return productionDispatchReady
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
        isIceConnected = { _, _ -> false },
        canDispatchRecoveryMediaAction = { sid, rid -> fieldShapedCanDispatch(sid, rid) },
        evaluateRecoveryAdmission = { _, _ ->
            if (fieldShapedCanDispatch(sessionId, remoteModuleId)) {
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
            channelId = channelId,
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
    fun blockActive_forcesHeldDispatch_onNegDrain() {
        val intentId = admitDeferredNegotiationIntent()
        canExecute = true
        productionDispatchReady = true
        Pr52cDebugInjection.blockDispatch(sessionId, remoteModuleId)
        assertFalse(fieldShapedCanDispatch(sessionId, remoteModuleId))

        val admissionSeq = admissionSeqCounter.get()
        decisionLogs.clear()
        controller.drainPendingIceRestart(
            sessionId,
            remoteModuleId,
            capabilityEventObservationSeq = admissionSeq + 1
        )

        assertEquals(0, iceRestartCalls)
        assertTrue(
            decisionLogs.any {
                it.contains("DEFERRED_INTENT_HELD") &&
                    it.contains("hold=DISPATCH") &&
                    it.contains("intentId=$intentId")
            }
        )
    }

    @Test
    fun blockInactive_preservesProductionDispatchReady() {
        admitDeferredNegotiationIntent()
        canExecute = true
        productionDispatchReady = true
        assertFalse(Pr52cDebugInjection.isDispatchBlocked(sessionId, remoteModuleId))
        assertTrue(fieldShapedCanDispatch(sessionId, remoteModuleId))

        val admissionSeq = admissionSeqCounter.get()
        decisionLogs.clear()
        iceRestartCalls = 0
        controller.drainPendingIceRestart(
            sessionId,
            remoteModuleId,
            capabilityEventObservationSeq = admissionSeq + 1
        )

        assertEquals(1, iceRestartCalls)
        assertFalse(decisionLogs.any { it.contains("hold=DISPATCH") })
        assertTrue(decisionLogs.any { it.contains("DEFERRED_INTENT_EXECUTED") })
    }

    @Test
    fun releaseClearsBlock_thenDrainExecutes_andEmptyReleaseNoops() {
        val intentId = admitDeferredNegotiationIntent()
        canExecute = true
        productionDispatchReady = true
        Pr52cDebugInjection.blockDispatch(sessionId, remoteModuleId)
        val admissionSeq = admissionSeqCounter.get()
        controller.drainPendingIceRestart(
            sessionId,
            remoteModuleId,
            capabilityEventObservationSeq = admissionSeq + 1
        )
        assertTrue(decisionLogs.any { it.contains("hold=DISPATCH") })

        decisionLogs.clear()
        iceRestartCalls = 0
        assertTrue(controller.debugReleaseDispatchReadiness(sessionId, remoteModuleId))
        assertFalse(Pr52cDebugInjection.isDispatchBlocked(sessionId, remoteModuleId))
        assertFalse(decisionLogs.any { it.contains("DEBUG_RELEASE_NOOP") })
        assertTrue(decisionLogs.any { it.contains("DEFERRED_INTENT_EXECUTED") && it.contains("intentId=$intentId") })
        assertEquals(1, iceRestartCalls)
        assertNull(controller.pendingIceRestartIntentId(sessionId, remoteModuleId))

        // RELEASE without HELD remains NOOP (regression for #112)
        decisionLogs.clear()
        assertTrue(controller.debugReleaseDispatchReadiness(sessionId, remoteModuleId))
        assertTrue(decisionLogs.any { it.contains("DEBUG_RELEASE_NOOP") })
    }
}