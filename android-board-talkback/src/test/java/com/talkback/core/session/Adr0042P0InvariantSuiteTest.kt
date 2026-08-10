package com.talkback.core.session

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.Executors

/**
 * ADR-0042 P0 — invariant suite (INV-T1/T2/T3 eligibility + INV-T3-SCHEDULE desk cases).
 *
 * Complements [Adr0042P0ReattachSendFailedReactionTest] and
 * [Adr0042InvT3ScheduleProgressOracleTest] (G4 progress oracle).
 * Does not change ADR-0035 / X1 / completion predicates.
 */
class Adr0042P0InvariantSuiteTest {
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private var nowMs = 0L
    private var reattachCalls = 0
    private var nextOutcome: () -> ReattachDispatchOutcome = { ReattachDispatchOutcome.SENT }
    private val decisionLogs = mutableListOf<String>()
    private lateinit var controller: ConferenceEdgeRecoveryController

    @Before
    fun setUp() {
        nowMs = 0L
        reattachCalls = 0
        nextOutcome = { ReattachDispatchOutcome.SENT }
        decisionLogs.clear()
        controller = ConferenceEdgeRecoveryController(
            debounceMs = 50L,
            iceRestartTimeoutMs = 200L,
            attemptBudgetMs = 500L,
            observationWindowMs = 10_000L,
            clock = { nowMs },
            scheduler = scheduler,
            onLog = { decisionLogs.add(it) },
            onRequestReattach = { _, _, _ ->
                reattachCalls++
                nextOutcome()
            },
            onIceRestart = { _, _ -> true },
            isIceConnected = { _, _ -> false },
            canDispatchRecoveryMediaAction = { _, _ -> true },
            membershipEpochProbe = DefaultOpenMembershipAuthoritySentinel
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

    private fun startParticipantReattach(remote: String = "M02") {
        controller.onIceStateChanged(
            sessionId = "sess-adr42",
            channelId = "CH-1",
            remoteModuleId = remote,
            iceState = "FAILED",
            eligibility = eligible(),
            initiatesReattach = true
        )
        nowMs = 60L
        Thread.sleep(80)
    }

    private fun notifyRouteConverged(remote: String = "M02") {
        val before = projectRecoveryCapabilitySignature(
            EdgeReachabilitySnapshot(
                linkReady = false,
                peerDiscovered = true,
                peerSignalingReachable = true,
                mediaRouteConnected = false,
                authorityReachable = true
            ),
            initiatesReattach = true,
            controlPlaneStarted = false
        )
        val snapshot = EdgeReachabilitySnapshot(
            linkReady = true,
            peerDiscovered = true,
            peerSignalingReachable = true,
            mediaRouteConnected = true,
            authorityReachable = true
        )
        val signature = projectRecoveryCapabilitySignature(
            snapshot,
            initiatesReattach = true,
            controlPlaneStarted = false
        )
        controller.onRecoveryReachabilityChanged(
            sessionId = "sess-adr42",
            channelId = "CH-1",
            remoteModuleId = remote,
            snapshot = snapshot,
            signature = signature,
            capabilityBefore = before,
            trigger = RecoveryReevaluateTrigger.ROUTE_CONVERGED
        )
    }

    @Test
    fun t1_sendtoSuccess_marksSentAndTransportInFlight() {
        nextOutcome = { ReattachDispatchOutcome.SENT }
        startParticipantReattach()
        assertEquals(1, reattachCalls)
        assertTrue(
            decisionLogs.any {
                it.contains("RECOVERY_REATTACH_SENT") && it.contains("deliveryState=TRANSPORT_SENT")
            }
        )
        assertTrue(controller.edgeObligationOpen("sess-adr42", "M02"))
        assertFalse(controller.factsForSession("sess-adr42").anyFailedMediaRecovery)
    }

    @Test
    fun t2_sendFailed_clearsInFlight_noSent_noFailedMedia() {
        nextOutcome = { ReattachDispatchOutcome.SEND_FAILED }
        startParticipantReattach()
        assertEquals(1, reattachCalls)
        assertFalse(
            decisionLogs.any {
                it.contains("RECOVERY_REATTACH_SENT") && it.contains("deliveryState=TRANSPORT_SENT")
            }
        )
        assertTrue(
            decisionLogs.any { it.contains("outcome=SEND_FAILED") && it.contains("obligationOpen=true") }
        )
        assertTrue(
            "INV-T3-SCHEDULE: SEND_FAILED must arm progress window alongside WAKEUP",
            decisionLogs.any { it.contains("RECOVERY_PROGRESS_WINDOW_ARMED") }
        )
        assertFalse(controller.factsForSession("sess-adr42").anyFailedMediaRecovery)
        assertTrue(controller.edgeObligationOpen("sess-adr42", "M02"))
        assertTrue(controller.factsForSession("sess-adr42").anyRecovering)
    }

    @Test
    fun t3_afterSendFailed_routeConverged_obligationOwnerMayRedispatch() {
        var round = 0
        nextOutcome = {
            round++
            if (round == 1) ReattachDispatchOutcome.SEND_FAILED else ReattachDispatchOutcome.SENT
        }
        startParticipantReattach()
        assertEquals(1, reattachCalls)
        assertTrue(controller.edgeObligationOpen("sess-adr42", "M02"))

        decisionLogs.clear()
        reattachCalls = 0
        notifyRouteConverged()

        assertTrue(
            "obligation owner must be able to redispatch after transport SEND_FAILED",
            reattachCalls >= 1 ||
                decisionLogs.any {
                    it.contains("DISPATCH_REATTACH") && !it.contains("rejectReason=transport_in_flight")
                }
        )
        assertFalse(
            decisionLogs.any { it.contains("rejectReason=transport_in_flight") }
        )
        assertFalse(controller.factsForSession("sess-adr42").anyFailedMediaRecovery)
    }

    @Test
    fun t3b_afterSendFailed_progressWindowFires_redispatchWithoutExternalRouteEvent() {
        var round = 0
        nextOutcome = {
            round++
            if (round == 1) ReattachDispatchOutcome.SEND_FAILED else ReattachDispatchOutcome.SENT
        }
        startParticipantReattach()
        assertEquals(1, reattachCalls)
        assertTrue(decisionLogs.any { it.contains("RECOVERY_PROGRESS_WINDOW_ARMED") })

        decisionLogs.clear()
        reattachCalls = 0
        // iceRestartTimeoutMs=200 — progress window budget; no ROUTE_CONVERGED / DIGEST.
        nowMs = 300L
        Thread.sleep(250)

        assertTrue(
            "INV-T3-SCHEDULE: progress window must attempt redispatch without external route event",
            reattachCalls >= 1
        )
        assertTrue(
            decisionLogs.any { it.contains("RECOVERY_PROGRESS_WINDOW_FIRED") }
        )
        assertTrue(
            "successful progress redispatch must satisfy window",
            decisionLogs.any { it.contains("RECOVERY_PROGRESS_WINDOW_SATISFIED") } ||
                decisionLogs.any { it.contains("outcome=TRANSPORT_SENT") }
        )
        assertFalse(controller.factsForSession("sess-adr42").anyFailedMediaRecovery)
        assertTrue(controller.edgeObligationOpen("sess-adr42", "M02"))
    }

    @Test
    fun t4_sendFailed_mediaReadyDoesNotInventTransportSent() {
        nextOutcome = { ReattachDispatchOutcome.SEND_FAILED }
        startParticipantReattach()
        assertFalse(
            "D-min: SEND_FAILED must not leave TRANSPORT_SENT evidence",
            decisionLogs.any {
                it.contains("RECOVERY_REATTACH_SENT") && it.contains("deliveryState=TRANSPORT_SENT")
            }
        )
        // Media-connected reachability must not close via false delivery evidence.
        decisionLogs.clear()
        notifyRouteConverged()
        assertFalse(
            decisionLogs.any { it.contains("reason=REATTACH_MEDIA_ALREADY_LIVE") }
        )
        assertTrue(controller.edgeObligationOpen("sess-adr42", "M02") ||
            controller.factsForSession("sess-adr42").anyRecovering)
    }

    @Test
    fun caseB_trueSent_keepsTransportInFlightGuard() {
        nextOutcome = { ReattachDispatchOutcome.SENT }
        startParticipantReattach()
        assertEquals(1, reattachCalls)

        decisionLogs.clear()
        reattachCalls = 0
        notifyRouteConverged()

        assertTrue(
            "true TRANSPORT_SENT must still be protected by transport_in_flight",
            reattachCalls == 0 ||
                decisionLogs.any { it.contains("rejectReason=transport_in_flight") }
        )
    }
}
