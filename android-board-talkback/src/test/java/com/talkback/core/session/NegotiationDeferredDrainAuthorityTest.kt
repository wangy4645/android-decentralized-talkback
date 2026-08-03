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
 * ADR-0022 Appendix D / INV-NEG-018..022 — Negotiation Deferred Drain Authority.
 * Lineage-first UTs (not happy-path only).
 */
class NegotiationDeferredDrainAuthorityTest {
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private var nowMs = 0L
    private var iceRestartCalls = 0
    private var canExecute = true
    private var iceConnected = false
    private val decisionLogs = mutableListOf<String>()
    private val admissionSeqCounter = AtomicLong(0L)
    private val capabilityObservation = NegotiationCapabilityObservation()
    private lateinit var controller: ConferenceEdgeRecoveryController

    private val sessionId = "sess-drain-auth"
    private val remoteModuleId = "M03"

    @Before
    fun setUp() {
        nowMs = 0L
        iceRestartCalls = 0
        canExecute = true
        iceConnected = false
        decisionLogs.clear()
        admissionSeqCounter.set(0L)
        capabilityObservation.clearAll()
        controller = buildController()
    }

    @After
    fun tearDown() {
        controller.clearAll()
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
        probeIceRestartGate = { _, _ ->
            if (canExecute) IceRestartGateProbe(executable = true)
            else IceRestartGateProbe(
                executable = false,
                blockReason = IceRestartGateBlockReason.OFFER_AWAITING_ANSWER,
                signalingState = "HAVE_LOCAL_OFFER",
                localRole = "OFFERER"
            )
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

    /** Host ICE_RESTART_ONLY path that hits negotiation defer. */
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
        assertEquals(0, iceRestartCalls)
        val intentId = controller.pendingIceRestartIntentId(sessionId, remoteModuleId)
        assertNotNull(intentId)
        assertTrue(decisionLogs.any { it.contains("DEFERRED_INTENT_CREATED") && it.contains("intentId=$intentId") })
        return intentId!!
    }

    @Test
    fun defer_whileCapabilityFalse_keepsDeferred() {
        admitDeferredNegotiationIntent()
        decisionLogs.clear()
        controller.drainPendingIceRestart(sessionId, remoteModuleId)

        assertEquals(0, iceRestartCalls)
        assertNotNull(controller.pendingIceRestartIntentId(sessionId, remoteModuleId))
        assertTrue(
            decisionLogs.any {
                it.contains("DEFERRED_INTENT_HELD") && it.contains("hold=NEGOTIATION")
            }
        )
    }

    @Test
    fun defer_thenRisingEvent_executes() {
        val intentId = admitDeferredNegotiationIntent()
        val admissionSeq = admissionSeqCounter.get()
        canExecute = true
        decisionLogs.clear()

        controller.drainPendingIceRestart(
            sessionId,
            remoteModuleId,
            capabilityEventObservationSeq = admissionSeq + 1
        )

        assertEquals(1, iceRestartCalls)
        assertNull(controller.pendingIceRestartIntentId(sessionId, remoteModuleId))
        assertTrue(decisionLogs.any { it.contains("DEFERRED_INTENT_EXECUTED") && it.contains("intentId=$intentId") })
        assertTrue(controller.edgeObligationOpen(sessionId, remoteModuleId))
    }

    @Test
    fun r1Defer_supersede_r2_lateR1Event_doesNotExecuteR1() {
        val r1 = admitDeferredNegotiationIntent()
        val r1AdmissionSeq = admissionSeqCounter.get()

        // Inbound ACCEPTED supersedes host-owned deferred attempt (lineage cut).
        canExecute = false
        controller.onRecoveryReattachAccepted(
            sessionId = sessionId,
            remoteModuleId = remoteModuleId,
            recoveryReason = RecoveryReason.NETWORK_RECOVERY,
            source = RecoverySource.ICE_MONITOR
        )
        assertTrue(
            decisionLogs.any {
                it.contains("DEFERRED_INTENT_SUPERSEDED") && it.contains("oldIntent=$r1")
            }
        )
        assertTrue(
            decisionLogs.any {
                it.contains("terminal=STALE_DISCARD") &&
                    it.contains("intentId=$r1") &&
                    it.contains("SUPERSEDED")
            }
        )

        val r2 = controller.pendingIceRestartIntentId(sessionId, remoteModuleId)
        assertNotNull(r2)
        assertTrue(r2 != r1)
        val r2AdmissionSeq = admissionSeqCounter.get()
        assertTrue(r2AdmissionSeq > r1AdmissionSeq)

        decisionLogs.clear()
        iceRestartCalls = 0
        canExecute = true
        // Late R1-era capability event (seq ≤ R1 admission, and ≤ R2 admission).
        controller.drainPendingIceRestart(
            sessionId,
            remoteModuleId,
            capabilityEventObservationSeq = r1AdmissionSeq
        )
        assertEquals(0, iceRestartCalls)
        assertEquals(r2, controller.pendingIceRestartIntentId(sessionId, remoteModuleId))
        assertTrue(
            decisionLogs.any {
                it.contains("DEFERRED_INTENT_REJECTED") &&
                    it.contains("intentId=$r2") &&
                    it.contains("reason=stale_capability_event")
            }
        )
        assertFalse(decisionLogs.any { it.contains("intentId=$r1") && it.contains("EXECUTED") })
    }

    @Test
    fun r2Active_capabilityEvent_drainsOnlyR2() {
        val r1 = admitDeferredNegotiationIntent()
        canExecute = false
        controller.onRecoveryReattachAccepted(
            sessionId = sessionId,
            remoteModuleId = remoteModuleId,
            recoveryReason = RecoveryReason.NETWORK_RECOVERY,
            source = RecoverySource.ICE_MONITOR
        )
        val r2 = controller.pendingIceRestartIntentId(sessionId, remoteModuleId)!!
        assertTrue(r2 != r1)
        val r2AdmissionSeq = admissionSeqCounter.get()

        canExecute = true
        decisionLogs.clear()
        iceRestartCalls = 0
        controller.drainPendingIceRestart(
            sessionId,
            remoteModuleId,
            capabilityEventObservationSeq = r2AdmissionSeq + 1
        )

        assertEquals(1, iceRestartCalls)
        assertTrue(decisionLogs.any { it.contains("DEFERRED_INTENT_EXECUTED") && it.contains("intentId=$r2") })
        assertFalse(decisionLogs.any { it.contains("intentId=$r1") && it.contains("EXECUTED") })
        assertNull(controller.pendingIceRestartIntentId(sessionId, remoteModuleId))
    }

    @Test
    fun executed_withoutRestartResolvedEvidence_doesNotRecover() {
        admitDeferredNegotiationIntent()
        val admissionSeq = admissionSeqCounter.get()
        canExecute = true
        controller.drainPendingIceRestart(
            sessionId,
            remoteModuleId,
            capabilityEventObservationSeq = admissionSeq + 1
        )
        assertEquals(1, iceRestartCalls)
        assertTrue(controller.edgeObligationOpen(sessionId, remoteModuleId))
        assertNull(controller.obligationCloseReason(sessionId, remoteModuleId))

        // Pre-dispatch ICE fact must not close (INV-NEG-021 / INV-REC-031).
        iceConnected = true
        nowMs += 10L
        decisionLogs.clear()
        controller.applyMarkRecoveredForTest(sessionId, remoteModuleId, evidence = "ICE_CONNECTED")
        assertTrue(controller.edgeObligationOpen(sessionId, remoteModuleId))
        assertNull(controller.obligationCloseReason(sessionId, remoteModuleId))
    }

    @Test
    fun staleObservationTrue_deferBaseline_thenRising_executes() {
        // INV-NEG-015 / checklist: stale previous=true must not block post-baseline rising.
        assertTrue(
            capabilityObservation.observeRecompute(sessionId, remoteModuleId, executable = true).risingEdge
        )
        val intentId = admitDeferredNegotiationIntent()
        assertEquals(false, capabilityObservation.lastObserved(sessionId, remoteModuleId))
        val admissionSeq = admissionSeqCounter.get()

        canExecute = true
        val rising = capabilityObservation.observeRecompute(sessionId, remoteModuleId, executable = true)
        assertTrue(rising.risingEdge)
        assertTrue(rising.observationSeq > admissionSeq)
        decisionLogs.clear()
        iceRestartCalls = 0
        controller.drainPendingIceRestart(
            sessionId,
            remoteModuleId,
            capabilityEventObservationSeq = rising.observationSeq
        )
        assertEquals(1, iceRestartCalls)
        assertTrue(decisionLogs.any { it.contains("DEFERRED_INTENT_EXECUTED") && it.contains("intentId=$intentId") })
    }

    @Test
    fun mediaRestoredWhileDeferred_doesNotAutoExecute() {
        admitDeferredNegotiationIntent()
        iceConnected = true
        nowMs += 50L
        decisionLogs.clear()
        iceRestartCalls = 0
        controller.onIceStateChanged(
            sessionId = sessionId,
            channelId = "CH-01",
            remoteModuleId = remoteModuleId,
            iceState = "CONNECTED",
            eligibility = eligible(),
            initiatesReattach = false
        )

        assertEquals(0, iceRestartCalls)
        assertNotNull(controller.pendingIceRestartIntentId(sessionId, remoteModuleId))
        assertFalse(decisionLogs.any { it.contains("DEFERRED_INTENT_EXECUTED") })
        assertFalse(decisionLogs.any { it.contains("NEGOTIATION_CAN_EXECUTE") })
        assertTrue(controller.edgeObligationOpen(sessionId, remoteModuleId))
    }
}
