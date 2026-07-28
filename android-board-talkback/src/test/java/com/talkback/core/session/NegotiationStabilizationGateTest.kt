package com.talkback.core.session

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.Executors

/**
 * 4.3-E Negotiation Stabilization Gate (INV-NEG-001..006).
 * DEFERRED ≠ phase; drain re-validates; CLOSE/SUPERSEDE stale-discards intent.
 */
class NegotiationStabilizationGateTest {
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private var nowMs = 0L
    private var iceRestartCalls = 0
    private var canExecute = true
    private val decisionLogs = mutableListOf<String>()
    private lateinit var controller: ConferenceEdgeRecoveryController

    private val sessionId = "sess-neg-gate"
    private val remoteModuleId = "M20"

    @Before
    fun setUp() {
        nowMs = 0L
        iceRestartCalls = 0
        canExecute = true
        decisionLogs.clear()
        controller = buildController()
    }

    @After
    fun tearDown() {
        controller.clearAll()
        scheduler.shutdownNow()
    }

    private fun buildController() = ConferenceEdgeRecoveryController(
        localModuleId = "LOCAL",
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
        probeIceRestartGate = { _, _ ->
            if (canExecute) IceRestartGateProbe(executable = true)
            else IceRestartGateProbe(
                executable = false,
                blockReason = IceRestartGateBlockReason.ANSWERER_SETTLING,
                signalingState = "STABLE",
                localRole = "ANSWERER"
            )
        }
    )

    @Test
    fun gate_defersIceRestart_keepsPhase_andDoesNotDispatch() {
        canExecute = false
        controller.onRecoveryReattachAccepted(
            sessionId = sessionId,
            remoteModuleId = remoteModuleId,
            recoveryReason = RecoveryReason.NETWORK_RECOVERY,
            source = RecoverySource.ICE_MONITOR
        )

        assertEquals(0, iceRestartCalls)
        val lineage = controller.attemptLineageObservation(sessionId, remoteModuleId)!!
        assertEquals(EdgeRecoveryPhase.REATTACH_ACCEPTED, lineage.phase)
                assertTrue(decisionLogs.any { it.contains("ICE_RESTART_GATE_BLOCKED") && it.contains("ANSWERER_SETTLING") })
        assertTrue(
            decisionLogs.any {
                it.contains("ICE_RESTART_DEFERRED") &&
                    it.contains("intentId=R") &&
                    it.contains("reason=ANSWERER_SETTLING")
            }
        )
        assertTrue(
            decisionLogs.any {
                it.contains("RECOVERY_MEDIA_ACTION_DEFERRED") &&
                    it.contains("deferredReason=NEGOTIATION_SETTLING")
            }
        )
        assertTrue(
            decisionLogs.any {
                it.contains("wakeupBinding=NEGOTIATION_CAN_EXECUTE/")
            }
        )
        assertFalse(decisionLogs.any { it.contains("RECOVERY_ICE_RESTART_DISPATCHED") })
        assertFalse(decisionLogs.any { it.contains("RECOVERY_WATCHDOG_STARTED") })
    }

    @Test
    fun drain_afterGateOpens_dispatchesIceRestart() {
        canExecute = false
        controller.onRecoveryReattachAccepted(
            sessionId = sessionId,
            remoteModuleId = remoteModuleId,
            recoveryReason = RecoveryReason.NETWORK_RECOVERY,
            source = RecoverySource.ICE_MONITOR
        )
        assertEquals(0, iceRestartCalls)

        canExecute = true
        controller.drainPendingIceRestart(sessionId, remoteModuleId)

        assertEquals(1, iceRestartCalls)
        val lineage = controller.attemptLineageObservation(sessionId, remoteModuleId)!!
        assertEquals(EdgeRecoveryPhase.ICE_RESTARTING, lineage.phase)
        assertTrue(decisionLogs.any { it.contains("RECOVERY_ICE_RESTART_DISPATCHED") })
                assertTrue(
            decisionLogs.any {
                it.contains("terminal=EXECUTED") && it.contains("intentId=R")
            }
        )
        assertTrue(
            decisionLogs.any {
                it.contains("RECOVERY_WAKEUP_FIRED") && it.contains("trigger=NEGOTIATION_CAN_EXECUTE")
            }
        )
    }

    @Test
    fun drain_whileGateStillClosed_holdsIntent() {
        canExecute = false
        controller.onRecoveryReattachAccepted(
            sessionId = sessionId,
            remoteModuleId = remoteModuleId,
            recoveryReason = RecoveryReason.NETWORK_RECOVERY,
            source = RecoverySource.ICE_MONITOR
        )
        controller.drainPendingIceRestart(sessionId, remoteModuleId)

        assertEquals(0, iceRestartCalls)
        assertEquals(
            EdgeRecoveryPhase.REATTACH_ACCEPTED,
            controller.attemptLineageObservation(sessionId, remoteModuleId)!!.phase
        )
        assertTrue(decisionLogs.any { it.contains("RECOVERY_ICE_RESTART_DRAIN_HELD") })
    }

    @Test
    fun closeObligation_expiresDeferredIntent_lateDrainNoOp() {
        canExecute = false
        controller.onRecoveryReattachAccepted(
            sessionId = sessionId,
            remoteModuleId = remoteModuleId,
            recoveryReason = RecoveryReason.NETWORK_RECOVERY,
            source = RecoverySource.ICE_MONITOR
        )
        assertEquals(0, iceRestartCalls)

        controller.cancelSession(sessionId, "session_cancelled")
        assertTrue(
            decisionLogs.any {
                it.contains("RECOVERY_ICE_RESTART_INTENT_TERMINAL") &&
                    it.contains("terminal=STALE_DISCARD") &&
                    it.contains("reason=OBLIGATION_CLOSED")
            }
        )

        canExecute = true
        decisionLogs.clear()
        iceRestartCalls = 0
        controller.drainPendingIceRestart(sessionId, remoteModuleId)

        assertEquals(0, iceRestartCalls)
        assertFalse(decisionLogs.any { it.contains("RECOVERY_ICE_RESTART_DISPATCHED") })
    }

    @Test
    fun supersede_expiresDeferredIntent_doesNotInherit() {
        canExecute = false
        controller.onIceStateChanged(
            sessionId = sessionId,
            channelId = "CH-01",
            remoteModuleId = remoteModuleId,
            iceState = "FAILED",
            eligibility = EdgeRecoveryEligibility(
                lifecycleEstablished = true,
                localJoined = true,
                remoteJoined = true,
                conferenceTerminated = false
            ),
            initiatesReattach = false
        )
        assertEquals(0, iceRestartCalls)
        assertTrue(decisionLogs.any { it.contains("deferredReason=NEGOTIATION_SETTLING") })
        val attemptBefore = controller.attemptLineageObservation(sessionId, remoteModuleId)!!.attemptId

        // Inbound ACCEPTED supersedes host-owned deferred attempt (not duplicate inbound).
        canExecute = true
        controller.onRecoveryReattachAccepted(
            sessionId = sessionId,
            remoteModuleId = remoteModuleId,
            recoveryReason = RecoveryReason.NETWORK_RECOVERY,
            source = RecoverySource.ICE_MONITOR
        )

        assertTrue(
            decisionLogs.any {
                it.contains("RECOVERY_ICE_RESTART_INTENT_EXPIRED") &&
                    it.contains("terminal=STALE_DISCARD") &&
                    it.contains("SUPERSEDE")
            }
        )
        val lineage = controller.attemptLineageObservation(sessionId, remoteModuleId)!!
        assertTrue(lineage.attemptId > attemptBefore)
        assertEquals(EdgeRecoveryPhase.ICE_RESTARTING, lineage.phase)
        assertEquals(1, iceRestartCalls)
    }

    @Test
    fun gateOpen_dispatchesImmediately_withoutDefer() {
        canExecute = true
        controller.onRecoveryReattachAccepted(
            sessionId = sessionId,
            remoteModuleId = remoteModuleId,
            recoveryReason = RecoveryReason.NETWORK_RECOVERY,
            source = RecoverySource.ICE_MONITOR
        )

        assertEquals(1, iceRestartCalls)
        assertEquals(
            EdgeRecoveryPhase.ICE_RESTARTING,
            controller.attemptLineageObservation(sessionId, remoteModuleId)!!.phase
        )
        assertFalse(decisionLogs.any { it.contains("deferredReason=NEGOTIATION_SETTLING") })
    }
}
