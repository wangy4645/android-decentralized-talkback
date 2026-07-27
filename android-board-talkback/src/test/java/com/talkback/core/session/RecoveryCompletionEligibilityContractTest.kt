package com.talkback.core.session

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.Executors

/**
 * Recovery Completion Reachability Projection Contract (ADR-0033).
 *
 * Documents completion-phase invariants. Dispatch eligibility is frozen by ADR-0032;
 * these tests MUST NOT be "fixed" by reintroducing media-plane facts into dispatch.
 */
class RecoveryCompletionEligibilityContractTest {

    /**
     * Mirrors [com.talkback.app.TalkbackCoordinator.isConferenceAuthorityReachable]:
     * remote authority uses host media connectivity; self-authority is tautological.
     */
    private fun projectAuthorityReachable(
        hostModuleId: String,
        localModuleId: String,
        isPeerMediaConnected: (String) -> Boolean
    ): Boolean {
        if (hostModuleId == localModuleId) return true
        return isPeerMediaConnected(hostModuleId)
    }

    @Test
    fun completionContract_hostSelfAuthorityMustNotRequireMediaLoopback() {
        val hostModuleId = "M02"
        // Peer edge media is up; host has no ICE loopback edge to itself.
        val authorityReachable = projectAuthorityReachable(
            hostModuleId = hostModuleId,
            localModuleId = hostModuleId,
            isPeerMediaConnected = { moduleId -> moduleId != hostModuleId }
        )
        val snapshot = EdgeReachabilitySnapshot(
            linkReady = true,
            peerDiscovered = true,
            peerSignalingReachable = true,
            mediaRouteConnected = true,
            authorityReachable = authorityReachable
        )
        assertTrue(
            "host self-authority must not require media loopback to hostModuleId",
            snapshot.canCompleteRecovery()
        )
    }

    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private var iceRestartCalls = 0
    private val decisionLogs = mutableListOf<String>()
    private lateinit var controller: ConferenceEdgeRecoveryController

    @Before
    fun setUp() {
        iceRestartCalls = 0
        decisionLogs.clear()
        controller = buildController()
    }

    @After
    fun tearDown() {
        controller.clearAll()
        scheduler.shutdownNow()
    }

    private fun buildController() = ConferenceEdgeRecoveryController(
        debounceMs = 50L,
        iceRestartTimeoutMs = 200L,
        attemptBudgetMs = 500L,
        observationWindowMs = 10_000L,
        clock = { System.currentTimeMillis() },
        scheduler = scheduler,
        onLog = { decisionLogs.add(it) },
        onRequestReattach = { _, _, _ -> ReattachDispatchOutcome.SENT },
        onIceRestart = { _, _ ->
            iceRestartCalls++
            true
        },
        isIceConnected = { _, _ -> false },
        canDispatchRecoveryMediaAction = { _, _ ->
            EdgeReachabilitySnapshot(
                linkReady = true,
                peerDiscovered = true,
                peerSignalingReachable = true,
                mediaRouteConnected = false,
                authorityReachable = true
            ).canDispatchRecoverySignal()
        }
    )

    private fun eligible() = EdgeRecoveryEligibility(
        lifecycleEstablished = true,
        localJoined = true,
        remoteJoined = true,
        conferenceTerminated = false
    )

    /**
     * INV-REC-016: dispatch is an action; completion is an observation.
     * Case B proved ICE restart can dispatch while EDGE_RECOVERED has not fired.
     */
    @Test
    fun completionContract_dispatchSuccessDoesNotMeanRecovered() {
        controller.onIceStateChanged(
            sessionId = "sess-1",
            channelId = "CH-1",
            remoteModuleId = "M03",
            iceState = "DISCONNECTED",
            eligibility = eligible(),
            initiatesReattach = false
        )
        Thread.sleep(100)
        assertTrue(iceRestartCalls >= 1)
        assertTrue(decisionLogs.any { it.contains("RECOVERY_ICE_RESTART_DISPATCHED") })
        assertTrue(controller.edgeObligationOpen("sess-1", "M03"))
        assertFalse(decisionLogs.any { it.contains("RECOVERY_EDGE_RECOVERED") })
    }
}