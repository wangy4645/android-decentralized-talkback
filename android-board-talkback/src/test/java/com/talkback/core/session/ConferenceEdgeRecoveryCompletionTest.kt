package com.talkback.core.session

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.Executors

/**
 * ADR-0022 edge recovery completion on participant nodes (soak ea6466f1 / M03->M02).
 */
class ConferenceEdgeRecoveryCompletionTest {
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private var nowMs = 0L
    private var iceConnected = false
    private val decisionLogs = mutableListOf<String>()
    private lateinit var controller: ConferenceEdgeRecoveryController

    private val sessionId = "ea6466f1-participant"
    private val channelId = "CH-01"
    private val remoteModuleId = "M02"
    private val localMeshPeer = "M01"

    @Before
    fun setUp() {
        nowMs = 0L
        iceConnected = false
        decisionLogs.clear()
        controller = buildController(attemptBudgetMs = 500L)
    }

    @After
    fun tearDown() {
        controller.clearAll()
        scheduler.shutdownNow()
    }

    private fun buildController(
        attemptBudgetMs: Long = 500L,
        observationWindowMs: Long = 10_000L
    ) = ConferenceEdgeRecoveryController(
        debounceMs = 50L,
        iceRestartTimeoutMs = 200L,
        attemptBudgetMs = attemptBudgetMs,
        observationWindowMs = observationWindowMs,
        clock = { nowMs },
        scheduler = scheduler,
        onLog = { message -> decisionLogs.add(message) },
        onRequestReattach = { _, _, _ -> ReattachDispatchOutcome.SENT },
        onIceRestart = { _, _ -> true },
        isIceConnected = { _, _ -> iceConnected }
    )

    private fun eligible() = EdgeRecoveryEligibility(
        lifecycleEstablished = true,
        localJoined = true,
        remoteJoined = true,
        conferenceTerminated = false
    )

    private fun presenceProjection(connectedRemotes: Set<String>): ConferencePresenceProjection {
        val facts = controller.factsForSession(sessionId)
        return ConferencePresenceProjector.project(
            ConferencePresenceProjector.Input(
                sessionAccepted = true,
                joinedParticipantCount = 3,
                connectedRemoteModuleIds = connectedRemotes,
                recoveringRemoteModuleIds = facts.recoveringRemoteModuleIds,
                mediaUnavailableRemoteModuleIds = facts.mediaUnavailableRemoteModuleIds
            )
        )
    }

    private fun replayParticipantSoakPathThroughIceRestored(): List<String> {
        controller.onIceStateChanged(
            sessionId = sessionId,
            channelId = channelId,
            remoteModuleId = remoteModuleId,
            iceState = "DISCONNECTED",
            eligibility = eligible(),
            initiatesReattach = false
        )
        Thread.sleep(600)
        assertTrue(controller.factsForSession(sessionId).anyFailedMediaRecovery)

        val checkingSnapshot = EdgeReachabilitySnapshot(
            linkReady = true,
            peerDiscovered = true,
            routeConverged = false,
            authorityReachable = true
        )
        controller.onRecoveryReachabilityChanged(
            sessionId = sessionId,
            channelId = channelId,
            remoteModuleId = remoteModuleId,
            snapshot = checkingSnapshot,
            signature = projectRecoveryCapabilitySignature(
                checkingSnapshot,
                initiatesReattach = false,
                controlPlaneStarted = false
            ),
            capabilityBefore = projectRecoveryCapabilitySignature(
                checkingSnapshot,
                initiatesReattach = false,
                controlPlaneStarted = false
            ),
            trigger = RecoveryReevaluateTrigger.ICE_CHECKING
        )

        val routeSnapshot = checkingSnapshot.copy(routeConverged = true)
        controller.onRecoveryReachabilityChanged(
            sessionId = sessionId,
            channelId = channelId,
            remoteModuleId = remoteModuleId,
            snapshot = routeSnapshot,
            signature = projectRecoveryCapabilitySignature(
                routeSnapshot,
                initiatesReattach = false,
                controlPlaneStarted = false
            ),
            capabilityBefore = projectRecoveryCapabilitySignature(
                checkingSnapshot,
                initiatesReattach = false,
                controlPlaneStarted = false
            ),
            trigger = RecoveryReevaluateTrigger.ROUTE_CONVERGED
        )

        val logMark = decisionLogs.size
        iceConnected = true
        controller.onIceStateChanged(
            sessionId = sessionId,
            channelId = channelId,
            remoteModuleId = remoteModuleId,
            iceState = "CONNECTED",
            eligibility = eligible(),
            initiatesReattach = false
        )
        return decisionLogs.drop(logMark)
    }

    @Test
    fun participantEdge_iceRestoredAfterFailedMediaRecovery_mustContinueControlPlaneNotDeadEndWaiting() {
        val iceRestoredLogs = replayParticipantSoakPathThroughIceRestored()

        assertTrue(
            iceRestoredLogs.any {
                it.contains("RECOVERY_REEVALUATE") &&
                    it.contains("ICE_RESTORED") &&
                    it.contains("mediaRestored=true")
            }
        )

        assertTrue(
            iceRestoredLogs.any { it.contains("RECOVERY_CONTROL_PLANE_REQUIRED") }
        )

        assertTrue(
            iceRestoredLogs.any { it.contains("RECOVERY_CONTROL_PLANE_BOUNDARY") }
        )

        assertFalse(
            iceRestoredLogs.any { it.contains("rejectReason=control_plane_not_started") }
        )

        assertEventually {
            !controller.isEdgeRecovering(sessionId, remoteModuleId) &&
                controller.obligationCloseReason(sessionId, remoteModuleId) == ObligationCloseReason.RECOVERED
        }

        val connectedRemotes = setOf(localMeshPeer, remoteModuleId)
        assertEventually {
            val presence = presenceProjection(connectedRemotes)
            !presence.recoveringPeers.contains(remoteModuleId) &&
                !presence.mediaUnavailablePeers.contains(remoteModuleId)
        }
    }

    private fun assertEventually(
        timeoutMs: Long = 2_000L,
        pollMs: Long = 50L,
        predicate: () -> Boolean
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return
            Thread.sleep(pollMs)
        }
        assertTrue("condition not met within ${timeoutMs}ms", predicate())
    }
}