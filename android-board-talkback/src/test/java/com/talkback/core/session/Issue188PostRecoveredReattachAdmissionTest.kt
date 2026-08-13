package com.talkback.core.session

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.Executors

/** #188 Track P — post-RECOVERED inbound reattach admission gate. */
class Issue188PostRecoveredReattachAdmissionTest {

    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private var nowMs = 0L
    private val decisionLogs = mutableListOf<String>()
    private lateinit var controller: ConferenceEdgeRecoveryController

    @Before
    fun setUp() {
        nowMs = 0L
        decisionLogs.clear()
        controller = buildController()
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

    private fun buildController() = ConferenceEdgeRecoveryController(
        debounceMs = 50L,
        iceRestartTimeoutMs = 200L,
        attemptBudgetMs = 500L,
        observationWindowMs = 10_000L,
        clock = { nowMs },
        scheduler = scheduler,
        onLog = { decisionLogs.add(it) },
        onRequestReattach = { _, _, _ -> ReattachDispatchOutcome.SENT },
        onIceRestart = { _, _ -> true },
        isIceConnected = { _, _ -> false },
        canDispatchRecoveryMediaAction = { _, _ -> true }
    )

    private fun driveEdgeToRecovered(
        sessionId: String = "sess-188",
        channelId: String = "CH-1",
        remote: String = "M02"
    ) {
        controller.onIceStateChanged(
            sessionId = sessionId,
            channelId = channelId,
            remoteModuleId = remote,
            iceState = "FAILED",
            eligibility = eligible(),
            initiatesReattach = false
        )
        controller.onRecoveryReattachAccepted(
            sessionId,
            remote,
            RecoveryReason.NETWORK_RECOVERY,
            RecoverySource.ICE_MONITOR
        )
        controller.onIceConnected(sessionId, remote)
        nowMs += 50L
        controller.applyMarkRecoveredForTest(sessionId, remote, evidence = "ICE_CONNECTED")
        assertEquals(ObligationCloseReason.RECOVERED, controller.obligationCloseReason(sessionId, remote))
        assertFalse(controller.edgeObligationOpen(sessionId, remote))
    }

    /** P1 — ER then inbound reattach must not open a new obligation/attempt. */
    @Test
    fun p1_edgeRecovered_inboundReattach_doesNotOpenNewObligation() {
        driveEdgeToRecovered()
        val genBefore = controller.obligationGeneration("sess-188", "M02")!!
        val attemptBefore = controller.attemptLineageObservation("sess-188", "M02")!!.attemptId
        decisionLogs.clear()

        controller.onRecoveryReattachAccepted(
            "sess-188",
            "M02",
            RecoveryReason.NETWORK_RECOVERY,
            RecoverySource.ICE_MONITOR,
            disposition = ReattachDisposition.CONVERGING
        )

        assertFalse(controller.edgeObligationOpen("sess-188", "M02"))
        assertEquals(genBefore, controller.obligationGeneration("sess-188", "M02"))
        assertEquals(attemptBefore, controller.attemptLineageObservation("sess-188", "M02")!!.attemptId)
        assertEquals(EdgeRecoveryPhase.RECOVERED, controller.attemptLineageObservation("sess-188", "M02")!!.phase)
        assertTrue(decisionLogs.any { it.contains("rejectReason=post_recovered_stable_inbound_reattach") })
        assertFalse(decisionLogs.any { it.contains("RECOVERY_OBLIGATION_OPENED") })
    }

    /** P2 — material ICE failure after ER still allows recovery to reopen. */
    @Test
    fun p2_iceFailureAfterEdgeRecovered_allowsRecoveryReopen() {
        driveEdgeToRecovered()
        val genBefore = controller.obligationGeneration("sess-188", "M02")!!
        decisionLogs.clear()

        controller.onIceStateChanged(
            sessionId = "sess-188",
            channelId = "CH-1",
            remoteModuleId = "M02",
            iceState = "FAILED",
            eligibility = eligible(),
            initiatesReattach = false
        )

        assertTrue(controller.edgeObligationOpen("sess-188", "M02"))
        assertTrue(
            controller.obligationGeneration("sess-188", "M02")!! > genBefore ||
                controller.isEdgeRecovering("sess-188", "M02")
        )
    }
}
