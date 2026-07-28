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
 * ADR-0022 Q10-Q14 / INV-REC-026..031 / INV-NEG-016:
 * Recovery completion authority — domain-matched close + post-dispatch freshness.
 */
class RecoveryCompletionAuthorityTest {
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private var nowMs = 0L
    private var iceRestartCalls = 0
    private var canExecute = true
    private var iceConnected = false
    private val decisionLogs = mutableListOf<String>()
    private lateinit var controller: ConferenceEdgeRecoveryController

    private val sessionId = "sess-completion-auth"
    private val remoteModuleId = "M03"

    @Before
    fun setUp() {
        nowMs = 0L
        iceRestartCalls = 0
        canExecute = true
        iceConnected = false
        decisionLogs.clear()
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
            if (canExecute) {
                IceRestartGateProbe(executable = true)
            } else {
                IceRestartGateProbe(
                    executable = false,
                    blockReason = IceRestartGateBlockReason.SIGNALING_NOT_STABLE,
                    signalingState = "HAVE_LOCAL_OFFER",
                    localRole = "OFFERER"
                )
            }
        }
    )

    private fun eligible() = EdgeRecoveryEligibility(
        lifecycleEstablished = true,
        localJoined = true,
        remoteJoined = true,
        conferenceTerminated = false
    )

    /** Host ICE_RESTART_ONLY path that hits negotiation defer (soak 43e-b30 R1 shape). */
    private fun admitDeferredNegotiationIntent() {
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
        assertNotNull(controller.pendingIceRestartIntentId(sessionId, remoteModuleId))
        assertTrue(decisionLogs.any { it.contains("deferredReason=NEGOTIATION_SETTLING") })
    }

    @Test
    fun pendingNegotiationDefer_iceConnected_mustNotRecoverOrExpireIntent() {
        admitDeferredNegotiationIntent()
        decisionLogs.clear()

        iceConnected = true
        nowMs += 50L
        controller.onIceStateChanged(
            sessionId = sessionId,
            channelId = "CH-01",
            remoteModuleId = remoteModuleId,
            iceState = "CONNECTED",
            eligibility = eligible(),
            initiatesReattach = false
        )

        assertTrue(controller.edgeObligationOpen(sessionId, remoteModuleId))
        assertNull(controller.obligationCloseReason(sessionId, remoteModuleId))
        assertNotNull(controller.pendingIceRestartIntentId(sessionId, remoteModuleId))
        assertFalse(
            decisionLogs.any {
                it.contains("RECOVERY_EDGE_RECOVERED") ||
                    it.contains("reason=OBLIGATION_CLOSED")
            }
        )
        val phase = controller.attemptLineageObservation(sessionId, remoteModuleId)!!.phase
        assertTrue(phase.isActivelyRecovering())
        assertFalse(phase == EdgeRecoveryPhase.RECOVERED)
    }

    @Test
    fun pendingNegotiationDefer_mediaPathActiveWithoutRestart_mustNotEnterIceRestartingOrRecovered() {
        admitDeferredNegotiationIntent()
        decisionLogs.clear()

        iceConnected = true
        nowMs += 50L
        controller.onIceStateChanged(
            sessionId = sessionId,
            channelId = "CH-01",
            remoteModuleId = remoteModuleId,
            iceState = "CONNECTED",
            eligibility = eligible(),
            initiatesReattach = false
        )

        assertTrue(
            decisionLogs.any {
                it.contains("RECOVERY_MEDIA_PATH_OBSERVATION") &&
                    it.contains("reason=media_path_active_without_restart") &&
                    it.contains("decision=HOLD")
            }
        )
        assertFalse(
            decisionLogs.any {
                it.contains("RECOVERY_CONTROL_PLANE_BOUNDARY") &&
                    it.contains("media_path_active_without_restart")
            }
        )
        assertNotNull(controller.pendingIceRestartIntentId(sessionId, remoteModuleId))
        assertNull(controller.obligationCloseReason(sessionId, remoteModuleId))
        assertFalse(
            controller.attemptLineageObservation(sessionId, remoteModuleId)!!.phase ==
                EdgeRecoveryPhase.ICE_RESTARTING
        )
        assertFalse(
            controller.attemptLineageObservation(sessionId, remoteModuleId)!!.phase ==
                EdgeRecoveryPhase.RECOVERED
        )
    }

    @Test
    fun executedAfterDefer_preDispatchMediaRestored_mustNotCloseObligation() {
        admitDeferredNegotiationIntent()
        iceConnected = true
        nowMs += 10L
        controller.onIceStateChanged(
            sessionId = sessionId,
            channelId = "CH-01",
            remoteModuleId = remoteModuleId,
            iceState = "CONNECTED",
            eligibility = eligible(),
            initiatesReattach = false
        )
        assertTrue(controller.edgeObligationOpen(sessionId, remoteModuleId))
        assertNotNull(controller.pendingIceRestartIntentId(sessionId, remoteModuleId))

        canExecute = true
        nowMs += 20L
        decisionLogs.clear()
        controller.drainPendingIceRestart(sessionId, remoteModuleId)

        assertEquals(1, iceRestartCalls)
        assertTrue(decisionLogs.any { it.contains("terminal=EXECUTED") })
        assertNull(controller.pendingIceRestartIntentId(sessionId, remoteModuleId))
        assertTrue(controller.edgeObligationOpen(sessionId, remoteModuleId))
        assertNull(controller.obligationCloseReason(sessionId, remoteModuleId))
        assertFalse(decisionLogs.any { it.contains("RECOVERY_EDGE_RECOVERED") })

        controller.applyMarkRecoveredForTest(sessionId, remoteModuleId, evidence = "ICE_CONNECTED")
        assertTrue(controller.edgeObligationOpen(sessionId, remoteModuleId))
        assertNull(controller.obligationCloseReason(sessionId, remoteModuleId))
        assertTrue(
            decisionLogs.any {
                it.contains("RECOVERY_COMPLETION_HELD") && it.contains("domain=RESTART_FRESHNESS")
            }
        )
    }

    @Test
    fun executedAfterDefer_postDispatchIceConnected_mayCloseObligation() {
        admitDeferredNegotiationIntent()
        iceConnected = true
        nowMs += 10L
        controller.onIceStateChanged(
            sessionId = sessionId,
            channelId = "CH-01",
            remoteModuleId = remoteModuleId,
            iceState = "CONNECTED",
            eligibility = eligible(),
            initiatesReattach = false
        )

        canExecute = true
        nowMs += 20L
        controller.drainPendingIceRestart(sessionId, remoteModuleId)
        assertEquals(1, iceRestartCalls)
        assertTrue(controller.edgeObligationOpen(sessionId, remoteModuleId))

        iceConnected = false
        nowMs += 5L
        controller.onIceStateChanged(
            sessionId = sessionId,
            channelId = "CH-01",
            remoteModuleId = remoteModuleId,
            iceState = "CHECKING",
            eligibility = eligible(),
            initiatesReattach = false
        )
        iceConnected = true
        nowMs += 30L
        decisionLogs.clear()
        controller.onIceStateChanged(
            sessionId = sessionId,
            channelId = "CH-01",
            remoteModuleId = remoteModuleId,
            iceState = "CONNECTED",
            eligibility = eligible(),
            initiatesReattach = false
        )

        assertEquals(ObligationCloseReason.RECOVERED, controller.obligationCloseReason(sessionId, remoteModuleId))
        assertFalse(controller.edgeObligationOpen(sessionId, remoteModuleId))
        assertTrue(decisionLogs.any { it.contains("RECOVERY_EDGE_RECOVERED") })
        assertEquals(
            EdgeRecoveryPhase.RECOVERED,
            controller.attemptLineageObservation(sessionId, remoteModuleId)!!.phase
        )
    }

    @Test
    fun reattachAccepted_alreadyConnectedIce_mustNotCloseOnPostDispatchProbeAlone() {
        iceConnected = true
        canExecute = true
        controller.onRecoveryReattachAccepted(
            sessionId = sessionId,
            remoteModuleId = remoteModuleId,
            recoveryReason = RecoveryReason.NETWORK_RECOVERY,
            source = RecoverySource.ICE_MONITOR
        )
        assertEquals(1, iceRestartCalls)
        // INV-NEG-016: probe of pre-existing ICE after dispatch must not alone RECOVERED.
        assertTrue(controller.edgeObligationOpen(sessionId, remoteModuleId))
        assertNull(controller.obligationCloseReason(sessionId, remoteModuleId))
        assertTrue(
            decisionLogs.any {
                it.contains("RECOVERY_COMPLETION_HELD") && it.contains("domain=RESTART_FRESHNESS")
            }
        )

        nowMs += 40L
        decisionLogs.clear()
        controller.onIceStateChanged(
            sessionId = sessionId,
            channelId = "CH-01",
            remoteModuleId = remoteModuleId,
            iceState = "CONNECTED",
            eligibility = eligible(),
            initiatesReattach = false
        )
        assertEquals(ObligationCloseReason.RECOVERED, controller.obligationCloseReason(sessionId, remoteModuleId))
    }
}