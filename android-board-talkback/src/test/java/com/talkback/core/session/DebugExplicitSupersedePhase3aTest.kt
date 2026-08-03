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

/**
 * §E.16.2 Phase-3A FA-3 Option A — DEBUG_EXPLICIT_SUPERSEDE harness (deterministic).
 */
class DebugExplicitSupersedePhase3aTest {
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private var iceRestartCalls = 0
    private val decisionLogs = mutableListOf<String>()
    private lateinit var controller: ConferenceEdgeRecoveryController
    private val sessionId = "sess-p3a-debug"
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

    @Test
    fun heldDispatch_explicitSupersede_releasesFence_noLateExecute() {
        val r16 = controller.debugCreateDeferredNegotiationIntent(
            sessionId, channelId, remoteModuleId, admissionSeq
        )
        assertNotNull(r16)
        Pr52cDebugInjection.blockDispatch(sessionId, remoteModuleId)
        controller.drainPendingIceRestart(sessionId, remoteModuleId, admissionSeq + 1)
        assertTrue(decisionLogs.any { it.contains("hold=DISPATCH") && it.contains("intentId=$r16") })

        decisionLogs.clear()
        assertTrue(controller.debugExplicitSupersedeDeferredIntent(sessionId, remoteModuleId))
        assertTrue(
            decisionLogs.any {
                it.contains("DEBUG_EXPLICIT_SUPERSEDE") &&
                    it.contains("intentId=$r16") &&
                    it.contains("stimulus=DEBUG_EXPLICIT_SUPERSEDE")
            }
        )
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
                it.contains("FENCE_RELEASED") &&
                    it.contains("intentId=$r16") &&
                    it.contains("reason=SUPERSEDE")
            }
        )
        assertNull(controller.pendingIceRestartIntentId(sessionId, remoteModuleId))
        assertNull(Pr52cDebugInjection.fencedIntentId(sessionId, remoteModuleId))

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
    }

    @Test
    fun createdOnly_explicitSupersede_rejected() {
        val r16 = controller.debugCreateDeferredNegotiationIntent(
            sessionId, channelId, remoteModuleId, admissionSeq
        )
        assertNotNull(r16)
        decisionLogs.clear()
        assertFalse(controller.debugExplicitSupersedeDeferredIntent(sessionId, remoteModuleId))
        assertTrue(
            decisionLogs.any {
                it.contains("DEBUG_EXPLICIT_SUPERSEDE_REJECT") && it.contains("not_held_dispatch")
            }
        )
        assertFalse(
            decisionLogs.any {
                it.contains("DEFERRED_INTENT_SUPERSEDED") && it.contains("oldIntent=$r16")
            }
        )
        assertNotNull(controller.pendingIceRestartIntentId(sessionId, remoteModuleId))
    }
}
