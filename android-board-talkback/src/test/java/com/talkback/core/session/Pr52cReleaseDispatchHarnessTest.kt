package com.talkback.core.session

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Harness: PR52C RELEASE must be readiness-aware and must not poison the host.
 *
 * Field defect (2026-08-03): unconditional isRecoveryDispatchReady() nested into
 * Coordinator sync and deadlocked when no HELD(DISPATCH) intent existed.
 */
class Pr52cReleaseDispatchHarnessTest {
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private var nowMs = 0L
    private var iceRestartCalls = 0
    private var canExecute = true
    private var dispatchReady = true
    private val decisionLogs = mutableListOf<String>()
    private val admissionSeqCounter = AtomicLong(0L)
    private val capabilityObservation = NegotiationCapabilityObservation()
    private val dispatchReadyCalls = AtomicInteger(0)
    private var blockDispatchReady: CountDownLatch? = null
    private lateinit var controller: ConferenceEdgeRecoveryController

    private val sessionId = "sess-pr52c-release-harness"
    private val remoteModuleId = "M03"

    @Before
    fun setUp() {
        nowMs = 0L
        iceRestartCalls = 0
        canExecute = true
        dispatchReady = true
        decisionLogs.clear()
        admissionSeqCounter.set(0L)
        dispatchReadyCalls.set(0)
        blockDispatchReady = null
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
        canDispatchRecoveryMediaAction = { _, _ ->
            dispatchReadyCalls.incrementAndGet()
            blockDispatchReady?.await(5, TimeUnit.SECONDS)
            dispatchReady
        },
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

    @Test(timeout = 1_000)
    fun release_withNoHeldIntent_emitsNoopWithoutDispatchReady() {
        // If readiness were probed, canDispatch would block forever -> test timeout.
        blockDispatchReady = CountDownLatch(1)

        val ok = controller.debugReleaseDispatchReadiness(sessionId, remoteModuleId)

        assertTrue(ok)
        assertTrue(
            decisionLogs.any {
                it.contains("DEBUG_RELEASE_NOOP") &&
                    it.contains("reason=no_held_dispatch_intent")
            }
        )
        assertFalse(decisionLogs.any { it.contains("DEBUG_RELEASE_DISPATCH_READINESS_OBSERVED") })
        assertFalse(decisionLogs.any { it.contains("DEFERRED_INTENT_DRAIN_RETRY") })
        assertEquals(0, dispatchReadyCalls.get())
        assertEquals(0, iceRestartCalls)
    }

    @Test(timeout = 2_000)
    fun release_withHeldDispatch_triggersDrain() {
        val intentId = admitDeferredNegotiationIntent()
        canExecute = true
        dispatchReady = false
        val admissionSeq = admissionSeqCounter.get()
        controller.drainPendingIceRestart(
            sessionId,
            remoteModuleId,
            capabilityEventObservationSeq = admissionSeq + 1
        )
        assertTrue(decisionLogs.any { it.contains("hold=DISPATCH") && it.contains("intentId=$intentId") })

        dispatchReady = true
        decisionLogs.clear()
        iceRestartCalls = 0
        dispatchReadyCalls.set(0)

        val ok = controller.debugReleaseDispatchReadiness(sessionId, remoteModuleId)

        assertTrue(ok)
        assertFalse(decisionLogs.any { it.contains("DEBUG_RELEASE_NOOP") })
        assertTrue(decisionLogs.any { it.contains("DEBUG_RELEASE_DISPATCH_READINESS_OBSERVED") })
        assertTrue(
            decisionLogs.any {
                it.contains("DEFERRED_INTENT_DRAIN_RETRY") &&
                    it.contains(Pr52cDebugInjection.DEBUG_RELEASE_SEAM)
            }
        )
        assertTrue(decisionLogs.any { it.contains("DEFERRED_INTENT_EXECUTED") && it.contains("intentId=$intentId") })
        assertEquals(1, iceRestartCalls)
        assertTrue(dispatchReadyCalls.get() >= 1)
    }

    @Test(timeout = 1_000)
    fun release_noopThenNoop_remainsResponsive_noCoordinatorPoison() {
        // Two sequential empty RELEASE commands must both complete as NOOP.
        // Regression for field poison: first RELEASE hung forever and blocked later debug.
        assertTrue(controller.debugReleaseDispatchReadiness(sessionId, remoteModuleId))
        assertTrue(controller.debugReleaseDispatchReadiness(sessionId, remoteModuleId))

        val noops = decisionLogs.count { it.contains("DEBUG_RELEASE_NOOP") }
        assertEquals(2, noops)
        assertEquals(0, dispatchReadyCalls.get())
    }
}