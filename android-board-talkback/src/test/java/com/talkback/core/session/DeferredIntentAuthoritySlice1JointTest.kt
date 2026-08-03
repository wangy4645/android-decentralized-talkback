package com.talkback.core.session

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.Executors

/**
 * ADR-0022 §E.16.1 Slice-1 Phase-2 deterministic Joint — authority only (not C dispatch / J-B).
 *
 * R16: CREATED → HELD(DISPATCH) → SUPERSEDED via EDGE_STARTED; late events ignored for execution.
 * R17: fresh ownership / capability / evidence (no inheritance from R16).
 */
class DeferredIntentAuthoritySlice1JointTest {
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private var iceRestartCalls = 0
    private val decisionLogs = mutableListOf<String>()
    private lateinit var controller: ConferenceEdgeRecoveryController

    private val sessionId = "sess-jx-s1-joint"
    private val channelId = "CH-01"
    private val remoteModuleId = "M03"
    private val admissionSeq = 3L

    @Before
    fun setUp() {
        iceRestartCalls = 0
        decisionLogs.clear()
        Pr52cDebugInjection.clearEdge(sessionId, remoteModuleId)
        controller = ConferenceEdgeRecoveryController(
            localModuleId = "M02",
            debounceMs = 20L,
            iceRestartTimeoutMs = 200L,
            attemptBudgetMs = 5_000L,
            observationWindowMs = 10_000L,
            clock = { 0L },
            scheduler = scheduler,
            onLog = { decisionLogs.add(it) },
            onRequestReattach = { _, _, _ -> ReattachDispatchOutcome.SENT },
            onIceRestart = { _, _ ->
                iceRestartCalls++
                true
            },
            isIceConnected = { _, _ -> false },
            canDispatchRecoveryMediaAction = { sid, rid ->
                !Pr52cDebugInjection.isDispatchBlocked(sid, rid)
            },
            evaluateRecoveryAdmission = { _, _ ->
                if (Pr52cDebugInjection.isDispatchBlocked(sessionId, remoteModuleId)) {
                    PeerSignalingReachabilityProjection(
                        confidence = PeerSignalingReachabilityConfidence.MEDIUM,
                        decision = AdmissionDecisionProjection.WAITING_STALE,
                        reason = AdmissionConfidenceReason.INBOUND_STALE_FOR_RECOVERY_DISPATCH,
                        lastInboundAgeMs = 60_000L
                    )
                } else {
                    defaultRecoveryAdmissionProjection()
                }
            },
            probeIceRestartGate = { _, _ -> IceRestartGateProbe(executable = true) }
        )
    }

    @After
    fun tearDown() {
        controller.clearAll()
        scheduler.shutdownNow()
        Pr52cDebugInjection.clearEdge(sessionId, remoteModuleId)
    }

    private fun eligible() = EdgeRecoveryEligibility(
        lifecycleEstablished = true,
        localJoined = true,
        remoteJoined = true,
        conferenceTerminated = false
    )

    private fun createHeldDispatchR16(): String {
        val intentId = controller.debugCreateDeferredNegotiationIntent(
            sessionId, channelId, remoteModuleId, admissionSeq
        )
        assertNotNull(intentId)
        Pr52cDebugInjection.blockDispatch(sessionId, remoteModuleId)
        decisionLogs.clear()
        controller.drainPendingIceRestart(sessionId, remoteModuleId, admissionSeq + 1)
        assertTrue(
            decisionLogs.any {
                it.contains("DEFERRED_INTENT_HELD") &&
                    it.contains("hold=DISPATCH") &&
                    it.contains("intentId=$intentId")
            }
        )
        assertEquals(0, iceRestartCalls)
        return intentId!!
    }

    private fun triggerEdgeStartedSupersede() {
        controller.onIceStateChanged(
            sessionId = sessionId,
            channelId = channelId,
            remoteModuleId = remoteModuleId,
            iceState = "DISCONNECTED",
            eligibility = eligible(),
            initiatesReattach = false
        )
        Thread.sleep(80L)
    }

    @Test
    fun r16_createdHeldSuperseded_lateEventsIgnored() {
        val r16 = createHeldDispatchR16()
        decisionLogs.clear()
        triggerEdgeStartedSupersede()

        assertTrue(decisionLogs.any { it.contains("RECOVERY_EDGE_STARTED") })
        assertTrue(
            decisionLogs.any {
                it.contains("DEFERRED_INTENT_SUPERSEDED") &&
                    it.contains("oldIntent=$r16") &&
                    it.contains("oldState=HELD_DISPATCH") &&
                    it.contains("authority=DeferredIntentAuthority")
            }
        )
        assertTrue(
            decisionLogs.any {
                it.contains("DEFERRED_INTENT_RELEASED") &&
                    it.contains("intentId=$r16") &&
                    it.contains("terminal=SUPERSEDED") &&
                    it.contains("authority=DeferredIntentAuthority")
            }
        )
        assertTrue(
            decisionLogs.any {
                it.contains("FENCE_RELEASED") &&
                    it.contains("intentId=$r16") &&
                    it.contains("reason=SUPERSEDE")
            }
        )
        assertTrue(
            decisionLogs.any {
                it.contains("DEFERRED_INTENT_VALIDATION_FENCE_CLEARED") &&
                    it.contains("intentId=$r16") &&
                    it.contains("reason=RELEASED_BY_SUPERSEDE")
            }
        )
        assertNull(controller.pendingIceRestartIntentId(sessionId, remoteModuleId))
        assertNull(Pr52cDebugInjection.fencedIntentId(sessionId, remoteModuleId))

        // Late negotiation / drain signals must not resurrect R16 execution.
        decisionLogs.clear()
        iceRestartCalls = 0
        Pr52cDebugInjection.releaseDispatch(sessionId, remoteModuleId)
        controller.drainPendingIceRestart(sessionId, remoteModuleId, admissionSeq + 2)
        controller.retryHeldDeferredIntentDrain(sessionId, remoteModuleId, "ROUTE_CONVERGED")

        assertEquals(0, iceRestartCalls)
        assertFalse(
            decisionLogs.any {
                it.contains("DEFERRED_INTENT_EXECUTED") && it.contains("intentId=$r16")
            }
        )
        assertFalse(
            decisionLogs.any {
                it.contains("DEFERRED_INTENT_DRAIN_RETRY") && it.contains("intentId=$r16")
            }
        )
        assertFalse(
            decisionLogs.any {
                it.contains("DEFERRED_INTENT_HELD") && it.contains("intentId=$r16")
            }
        )
    }

    @Test
    fun r17_freshOwnershipCapabilityEvidence_noInheritance() {
        val r16 = createHeldDispatchR16()
        triggerEdgeStartedSupersede()
        assertTrue(decisionLogs.any { it.contains("oldIntent=$r16") && it.contains("SUPERSEDED") })

        // Replacement intent is independent — must re-establish ownership / fence / evidence.
        decisionLogs.clear()
        iceRestartCalls = 0
        Pr52cDebugInjection.blockDispatch(sessionId, remoteModuleId)
        val r17 = controller.debugCreateDeferredNegotiationIntent(
            sessionId, channelId, remoteModuleId, admissionSeq = 10L
        )
        assertNotNull(r17)
        assertNotEquals(r16, r17)
        assertTrue(
            decisionLogs.any {
                it.contains("DEFERRED_INTENT_CREATED") && it.contains("intentId=$r17")
            }
        )
        assertTrue(
            decisionLogs.any {
                it.contains("DEFERRED_INTENT_VALIDATION_FENCE_ARMED") &&
                    it.contains("intentId=$r17")
            }
        )
        assertEquals(r17, Pr52cDebugInjection.fencedIntentId(sessionId, remoteModuleId))
        assertEquals(r17, controller.pendingIceRestartIntentId(sessionId, remoteModuleId))

        // R17 must earn HELD/EXECUTED on its own evidence — not inherit R16 dispatch readiness.
        decisionLogs.clear()
        controller.drainPendingIceRestart(sessionId, remoteModuleId, capabilityEventObservationSeq = 11L)
        assertTrue(
            decisionLogs.any {
                it.contains("DEFERRED_INTENT_HELD") &&
                    it.contains("hold=DISPATCH") &&
                    it.contains("intentId=$r17")
            }
        )
        assertFalse(decisionLogs.any { it.contains("intentId=$r16") })
        assertEquals(0, iceRestartCalls)

        decisionLogs.clear()
        controller.debugReleaseDispatchReadiness(sessionId, remoteModuleId)
        assertTrue(
            decisionLogs.any {
                it.contains("DEFERRED_INTENT_EXECUTED") && it.contains("intentId=$r17")
            }
        )
        assertFalse(
            decisionLogs.any {
                it.contains("DEFERRED_INTENT_EXECUTED") && it.contains("intentId=$r16")
            }
        )
        assertEquals(1, iceRestartCalls)
    }
}
