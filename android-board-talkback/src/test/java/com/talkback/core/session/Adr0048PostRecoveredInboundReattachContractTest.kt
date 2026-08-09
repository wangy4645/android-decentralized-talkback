package com.talkback.core.session

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.Executors

/**
 * ADR-0048 contract tests: post-RECOVERED inbound [onRecoveryReattachAccepted] ownership transition.
 */
class Adr0048PostRecoveredInboundReattachContractTest {

    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private var nowMs = 0L
    private var iceConnected = false
    private val decisionLogs = mutableListOf<String>()
    private lateinit var controller: ConferenceEdgeRecoveryController

    @Before
    fun setUp() {
        nowMs = 0L
        iceConnected = false
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

    private fun membershipConvergedProbe() = object : MembershipEpochConvergenceProbe {
        override fun probe(
            record: EdgeRecoveryRecord,
            channelId: String,
            conferenceSessionId: String
        ): MembershipEpochProbeResult =
            MembershipEpochProbeResult.Checked(
                authorityId = "M01",
                expectedEpoch = 1L,
                observedEpoch = 1L,
                converged = true
            )
    }

    private fun buildController(
        isIceConnected: (String, String) -> Boolean = { _, _ -> iceConnected },
        membershipEpochProbe: MembershipEpochConvergenceProbe = DefaultOpenMembershipAuthoritySentinel
    ) = ConferenceEdgeRecoveryController(
        debounceMs = 50L,
        iceRestartTimeoutMs = 200L,
        attemptBudgetMs = 500L,
        observationWindowMs = 10_000L,
        clock = { nowMs },
        scheduler = scheduler,
        onLog = { decisionLogs.add(it) },
        onRequestReattach = { _, _, _ -> ReattachDispatchOutcome.SENT },
        onIceRestart = { _, _ -> true },
        isIceConnected = isIceConnected,
        canDispatchRecoveryMediaAction = { _, _ -> true },
        membershipEpochProbe = membershipEpochProbe
    )

    private fun driveEdgeToRecovered(
        sessionId: String = "sess-1",
        channelId: String = "CH-1",
        remote: String = "M01"
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
        assertFalse(controller.isEdgeRecovering(sessionId, remote))
        assertEquals(EdgeRecoveryPhase.RECOVERED, controller.attemptLineageObservation(sessionId, remote)!!.phase)
    }

    private fun postRecoveredInboundReattach(
        sessionId: String = "sess-1",
        remote: String = "M01",
        disposition: ReattachDisposition = ReattachDisposition.CONVERGING
    ) {
        controller.onRecoveryReattachAccepted(
            sessionId,
            remote,
            RecoveryReason.NETWORK_RECOVERY,
            RecoverySource.ICE_MONITOR,
            disposition = disposition
        )
    }

    /** C1 — CONVERGING admission creates ownership after post-RECOVERED inbound accept. */
    @Test
    fun c1_convergingAdmission_createsOwnershipEpisode() {
        driveEdgeToRecovered()
        val genBefore = controller.obligationGeneration("sess-1", "M01")!!
        decisionLogs.clear()

        postRecoveredInboundReattach(disposition = ReattachDisposition.CONVERGING)

        assertTrue(controller.edgeObligationOpen("sess-1", "M01"))
        assertTrue(controller.isEdgeRecovering("sess-1", "M01"))
        assertEquals(EdgeRecoveryPhase.REATTACH_ACCEPTED, controller.attemptLineageObservation("sess-1", "M01")!!.phase)
        val genAfter = controller.obligationGeneration("sess-1", "M01")!!
        assertTrue(genAfter > genBefore)
        assertTrue(
            decisionLogs.any {
                it.contains("POST_RECOVERED_INBOUND_REATTACH") ||
                    it.contains("trigger=POST_RECOVERED_INBOUND_REATTACH")
            }
        )
        assertTrue(decisionLogs.any { it.contains("RECOVERY_OBLIGATION_OPENED") })
    }

    /** C2 — NON_CONVERGING does not create ownership; stays non-actively-recovering. */
    @Test
    fun c2_nonConverging_doesNotCreateOwnershipEpisode() {
        driveEdgeToRecovered()
        val genBefore = controller.obligationGeneration("sess-1", "M01")!!
        decisionLogs.clear()

        postRecoveredInboundReattach(disposition = ReattachDisposition.NON_CONVERGING_REATTACH)

        assertFalse(controller.edgeObligationOpen("sess-1", "M01"))
        assertFalse(controller.isEdgeRecovering("sess-1", "M01"))
        assertEquals(EdgeRecoveryPhase.RECOVERED, controller.attemptLineageObservation("sess-1", "M01")!!.phase)
        assertEquals(genBefore, controller.obligationGeneration("sess-1", "M01"))
        assertTrue(decisionLogs.any { it.contains("NON_CONVERGING_REATTACH") })
        assertFalse(decisionLogs.any { it.contains("RECOVERY_OBLIGATION_OPENED") })
    }

    /** C3 — ICE already live must not shortcut-clear ownership (W5 seam class). */
    @Test
    fun c3_iceAlreadyLive_ownershipRemainsOpenUntilControlReconciliation() {
        driveEdgeToRecovered()
        iceConnected = true
        decisionLogs.clear()

        postRecoveredInboundReattach(disposition = ReattachDisposition.CONVERGING)

        assertTrue(controller.edgeObligationOpen("sess-1", "M01"))
        assertTrue(controller.isEdgeRecovering("sess-1", "M01"))
        assertFalse(decisionLogs.any { it.contains("RECOVERY_EDGE_RECOVERED") })
    }

    /** C4 — transport + membership/control reconciliation clears ownership. */
    @Test
    fun c4_controlReconciliation_clearsOwnership() {
        controller = buildController(membershipEpochProbe = membershipConvergedProbe())
        driveEdgeToRecovered()
        postRecoveredInboundReattach(disposition = ReattachDisposition.CONVERGING)
        assertTrue(controller.edgeObligationOpen("sess-1", "M01"))
        iceConnected = true
        decisionLogs.clear()

        val snapshot = EdgeReachabilitySnapshot(
            linkReady = true,
            peerDiscovered = true,
            peerSignalingReachable = true,
            mediaRouteConnected = true,
            authorityReachable = true
        )
        val signature = projectRecoveryCapabilitySignature(
            snapshot,
            initiatesReattach = false,
            controlPlaneStarted = true
        )
        controller.onRecoveryReachabilityChanged(
            sessionId = "sess-1",
            channelId = "CH-1",
            remoteModuleId = "M01",
            snapshot = snapshot,
            signature = signature,
            capabilityBefore = signature,
            trigger = RecoveryReevaluateTrigger.ROUTE_CONVERGED
        )

        assertTrue(decisionLogs.any { it.contains("RECOVERY_EDGE_RECOVERED") })
        assertFalse(controller.edgeObligationOpen("sess-1", "M01"))
        assertFalse(controller.isEdgeRecovering("sess-1", "M01"))
        assertEquals(ObligationCloseReason.RECOVERED, controller.obligationCloseReason("sess-1", "M01"))
    }
}
