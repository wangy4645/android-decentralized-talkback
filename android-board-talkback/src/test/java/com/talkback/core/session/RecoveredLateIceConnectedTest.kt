package com.talkback.core.session

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.Executors

/**
 * Terminal monotonicity: late ICE_CONNECTED after CLOSED(RECOVERED) must not rewrite
 * phase back to ICE_RESTARTING (soak gap2-casea UI stuck reconnecting).
 */
class RecoveredLateIceConnectedTest {

    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private var nowMs = 0L
    private val decisionLogs = mutableListOf<String>()
    private var iceConnected = true
    private lateinit var controller: ConferenceEdgeRecoveryController

    @Before
    fun setUp() {
        nowMs = 0L
        decisionLogs.clear()
        iceConnected = true
        controller = ConferenceEdgeRecoveryController(
            localModuleId = "LOCAL",
            debounceMs = 20L,
            iceRestartTimeoutMs = 200L,
            attemptBudgetMs = 500L,
            observationWindowMs = 100L,
            clock = { nowMs },
            scheduler = scheduler,
            onLog = { decisionLogs.add(it) },
            onRequestReattach = { _, _, _ -> ReattachDispatchOutcome.SENT },
            onIceRestart = { _, _ -> true },
            isIceConnected = { _, _ -> iceConnected },
            canDispatchRecoveryMediaAction = { _, _ -> true }
        )
    }

    @After
    fun tearDown() {
        controller.clearAll()
        scheduler.shutdownNow()
    }

    private fun eligible() = EdgeRecoveryEligibility(
        lifecycleEstablished = true,
        localJoined = true,
        remoteJoined = true,
        conferenceTerminated = false
    )

    @Test
    fun lateIceConnectedAfterRecovered_keepsPhaseRecovered_notIceRestarting() {
        controller.onIceStateChanged(
            sessionId = "sess-1",
            channelId = "CH-1",
            remoteModuleId = "M01",
            iceState = "FAILED",
            eligibility = eligible(),
            initiatesReattach = true
        )
        controller.onRecoveryReattachAccepted(
            "sess-1",
            "M01",
            RecoveryReason.HOST_REATTACH,
            RecoverySource.ICE_MONITOR
        )
        controller.onIceConnected("sess-1", "M01")
        assertEquals(
            ObligationCloseReason.RECOVERED,
            controller.obligationCloseReason("sess-1", "M01")
        )
        assertFalse(controller.isEdgeRecovering("sess-1", "M01"))
        assertFalse(controller.edgeObligationOpen("sess-1", "M01"))

        decisionLogs.clear()
        // Late duplicate ICE_CONNECTED (same race as soak after EDGE_RECOVERED).
        controller.onIceConnected("sess-1", "M01")

        assertTrue(
            decisionLogs.any { it.contains("IGNORE_LATE_ICE_AFTER_RECOVERED") }
        )
        assertFalse(controller.isEdgeRecovering("sess-1", "M01"))
        assertFalse(
            decisionLogs.any {
                it.contains("RECOVERY_CONTROL_PLANE_BOUNDARY") ||
                    it.contains("reason=media_path_active_without_restart")
            }
        )
        assertEquals(
            ObligationCloseReason.RECOVERED,
            controller.obligationCloseReason("sess-1", "M01")
        )
        assertEquals(
            EdgeRecoveryPhase.RECOVERED,
            controller.attemptLineageObservation("sess-1", "M01")!!.phase
        )
    }
}
