package com.talkback.core.session

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ADR-0050 R2a — bounded negotiation ingress gate after lease, before createOffer.
 */
class Adr0050R2aNegotiationIngressGateTest {

    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private val decisionLogs = mutableListOf<String>()
    private var nowMs = 0L
    private var iceRestartCalls = 0
    private lateinit var controller: ConferenceEdgeRecoveryController

    private val sessionId = "sess-r2a"
    private val remoteModuleId = "M02"

    @Before
    fun setUp() {
        nowMs = 1_000L
        iceRestartCalls = 0
        decisionLogs.clear()
    }

    @After
    fun tearDown() {
        if (::controller.isInitialized) {
            controller.clearAll()
        }
        scheduler.shutdownNow()
    }

    private fun buildController(
        ingressReadyProbe: ((String, String) -> Boolean)? = null,
        negotiationIngressBudgetMs: Long = 3_000L
    ) = ConferenceEdgeRecoveryController(
        localModuleId = "LOCAL",
        debounceMs = 10L,
        iceRestartTimeoutMs = 5_000L,
        attemptBudgetMs = 10_000L,
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
        canDispatchRecoveryMediaAction = { _, _ -> true },
        probeIceRestartGate = { _, _ -> IceRestartGateProbe(executable = true) },
        probeRemoteNegotiationIngressReady = ingressReadyProbe,
        negotiationIngressBudgetMs = negotiationIngressBudgetMs,
        negotiationIngressFreshMs = 5_000L
    )

    private fun eligible() = EdgeRecoveryEligibility(
        lifecycleEstablished = true,
        localJoined = true,
        remoteJoined = true,
        conferenceTerminated = false
    )

    private fun triggerIceOnlyRecovery() {
        controller.onIceStateChanged(
            sessionId = sessionId,
            channelId = "CH-1",
            remoteModuleId = remoteModuleId,
            iceState = "DISCONNECTED",
            eligibility = eligible(),
            initiatesReattach = false
        )
        nowMs += 20L
        Thread.sleep(80)
    }

    private fun hasDispatched(): Boolean =
        decisionLogs.any {
            it.contains("RECOVERY_ICE_RESTART_DISPATCHED") && it.contains("remote=$remoteModuleId")
        }

    @Test
    fun immediateReady_probeTrue_dispatchesWithoutPending() {
        controller = buildController(ingressReadyProbe = { _, _ -> true })
        triggerIceOnlyRecovery()

        assertTrue(decisionLogs.any { it.contains("REMOTE_NEGOTIATION_READY") && it.contains("trigger=IMMEDIATE") })
        assertFalse(decisionLogs.any { it.contains("NEGOTIATION_INGRESS_PENDING") })
        assertTrue(hasDispatched())
        assertTrue(iceRestartCalls >= 1)
    }

    @Test
    fun pendingThenObserve_dispatchesOnIngress() {
        val ready = AtomicBoolean(false)
        controller = buildController(
            ingressReadyProbe = { _, _ -> ready.get() },
            negotiationIngressBudgetMs = 5_000L
        )
        triggerIceOnlyRecovery()

        assertTrue(decisionLogs.any { it.contains("NEGOTIATION_INGRESS_PENDING") })
        assertFalse(hasDispatched())
        assertEquals(0, iceRestartCalls)

        ready.set(true)
        controller.onRemoteNegotiationIngressObserved(sessionId, remoteModuleId, observedAtMs = nowMs)
        Thread.sleep(50)

        assertTrue(
            decisionLogs.any {
                it.contains("REMOTE_NEGOTIATION_READY") && it.contains("trigger=INGRESS_OBSERVED")
            }
        )
        assertTrue(hasDispatched())
        assertTrue(iceRestartCalls >= 1)
    }

    @Test
    fun episodeTracker_observeStampsReadyWithoutProbe() {
        controller = buildController(
            ingressReadyProbe = null,
            negotiationIngressBudgetMs = 5_000L
        )
        triggerIceOnlyRecovery()
        assertTrue(decisionLogs.any { it.contains("NEGOTIATION_INGRESS_PENDING") })
        assertFalse(hasDispatched())

        controller.onRemoteNegotiationIngressObserved(sessionId, remoteModuleId, observedAtMs = nowMs)
        Thread.sleep(50)

        assertTrue(hasDispatched())
        assertTrue(iceRestartCalls >= 1)
    }

    @Test
    fun deadline_noDispatch_leavesAttemptForExistingFailurePath() {
        controller = buildController(
            ingressReadyProbe = { _, _ -> false },
            negotiationIngressBudgetMs = 80L
        )
        triggerIceOnlyRecovery()
        assertTrue(decisionLogs.any { it.contains("NEGOTIATION_INGRESS_PENDING") })
        assertFalse(hasDispatched())

        Thread.sleep(250)

        assertTrue(decisionLogs.any { it.contains("NEGOTIATION_INGRESS_DEADLINE") })
        assertFalse(hasDispatched())
        assertEquals(0, iceRestartCalls)
        assertFalse(decisionLogs.any { it.contains("ingress") && it.contains("FAILED_MEDIA") })
    }
}
