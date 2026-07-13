package com.talkback.core.session

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ConferenceEdgeRecoveryControllerTest {
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private var nowMs = 0L
    private var reattachCalls = 0
    private var iceRestartCalls = 0
    private val decisionLogs = mutableListOf<String>()
    private lateinit var controller: ConferenceEdgeRecoveryController

    @Before
    fun setUp() {
        nowMs = 0L
        reattachCalls = 0
        iceRestartCalls = 0
        decisionLogs.clear()
        controller = buildController(observationWindowMs = 10_000L)
    }

    private fun buildController(
        observationWindowMs: Long = 10_000L,
        attemptBudgetMs: Long = 500L,
        onRequestReattach: (String, String, String) -> ReattachDispatchOutcome = { _, _, _ ->
            reattachCalls++
            ReattachDispatchOutcome.SENT
        },
        onIceRestart: (String, String) -> Boolean = { _, _ ->
            iceRestartCalls++
            true
        },
        isIceConnected: (String, String) -> Boolean = { _, _ -> false }
    ) = ConferenceEdgeRecoveryController(
        debounceMs = 50L,
        iceRestartTimeoutMs = 200L,
        attemptBudgetMs = attemptBudgetMs,
        observationWindowMs = observationWindowMs,
        clock = { nowMs },
        scheduler = scheduler,
        onLog = { message -> decisionLogs.add(message) },
        onRequestReattach = onRequestReattach,
        onIceRestart = onIceRestart,
        isIceConnected = isIceConnected
    )

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
    fun deferredReattach_keepsRecoveryPending() {
        controller = buildController(
            onRequestReattach = { _, _, _ ->
                reattachCalls++
                ReattachDispatchOutcome.DEFERRED
            }
        )
        controller.onIceStateChanged(
            sessionId = "sess-1",
            channelId = "CH-1",
            remoteModuleId = "M01",
            iceState = "DISCONNECTED",
            eligibility = eligible(),
            initiatesReattach = true
        )
        nowMs = 60L
        Thread.sleep(80)
        assertEquals(1, reattachCalls)
        assertTrue(controller.factsForSession("sess-1").anyRecovering)
        assertTrue(
            decisionLogs.any {
                it.contains("RECOVERY_EDGE_STARTED") && it.contains("initiatesReattach=true")
            }
        )
        assertFalse(
            decisionLogs.any { it.contains("RECOVERY_REATTACH_REQUESTED") }
        )
    }

    @Test
    fun participantHostDisconnect_triggersReattachAfterDebounce() {
        controller.onIceStateChanged(
            sessionId = "sess-1",
            channelId = "CH-1",
            remoteModuleId = "M01",
            iceState = "DISCONNECTED",
            eligibility = eligible(),
            initiatesReattach = true
        )
        assertEquals(0, reattachCalls)
        nowMs = 60L
        Thread.sleep(80)
        assertTrue(controller.factsForSession("sess-1").anyRecovering)
        assertEquals(1, reattachCalls)
    }

    @Test
    fun r28h2_reconnectDuringDebounce_clearsSuspicionWithoutRecovery() {
        // R28-H.2: DISCONNECTED_DEBOUNCING + ICE CONNECTED → HEALTHY, not RECOVERED / attempt.
        controller.onIceStateChanged(
            sessionId = "sess-1",
            channelId = "CH-1",
            remoteModuleId = "M02",
            iceState = "DISCONNECTED",
            eligibility = eligible(),
            initiatesReattach = true
        )
        assertTrue(controller.isEdgeRecovering("sess-1", "M02"))
        assertEquals(0, reattachCalls)
        assertEquals(0, iceRestartCalls)

        controller.onIceStateChanged(
            sessionId = "sess-1",
            channelId = "CH-1",
            remoteModuleId = "M02",
            iceState = "CONNECTED",
            eligibility = eligible(),
            initiatesReattach = true
        )
        nowMs = 200L
        Thread.sleep(120L)

        assertFalse(
            "debounce reconnect must clear recovering projection",
            controller.factsForSession("sess-1").anyRecovering
        )
        assertFalse(controller.isEdgeRecovering("sess-1", "M02"))
        assertFalse(controller.edgeObligationOpen("sess-1", "M02"))
        assertEquals(0, reattachCalls)
        assertEquals(0, iceRestartCalls)
        assertFalse(decisionLogs.any { it.contains("RECOVERY_EDGE_STARTED") })
        assertFalse(decisionLogs.any { it.contains("RECOVERY_REATTACH") })
        assertFalse(decisionLogs.any { it.contains("RECOVERY_EDGE_RECOVERED") })
    }

    @Test
    fun hostWaitsForInboundReattach_withoutSending() {
        controller.onIceStateChanged(
            sessionId = "sess-1",
            channelId = "CH-1",
            remoteModuleId = "M02",
            iceState = "DISCONNECTED",
            eligibility = eligible(),
            initiatesReattach = false
        )
        nowMs = 60L
        Thread.sleep(80)
        assertTrue(controller.factsForSession("sess-1").anyRecovering)
        assertEquals(0, reattachCalls)
    }

    @Test
    fun reattachAccepted_issuesSingleIceRestart() {
        controller.onIceStateChanged(
            sessionId = "sess-1",
            channelId = "CH-1",
            remoteModuleId = "M02",
            iceState = "FAILED",
            eligibility = eligible(),
            initiatesReattach = false
        )
        controller.onRecoveryReattachAccepted(
            "sess-1",
            "M02",
            RecoveryReason.NETWORK_RECOVERY,
            RecoverySource.ICE_MONITOR
        )
        assertEquals(1, iceRestartCalls)
        controller.onRecoveryReattachAccepted(
            "sess-1",
            "M02",
            RecoveryReason.NETWORK_RECOVERY,
            RecoverySource.ICE_MONITOR
        )
        assertEquals(1, iceRestartCalls)
        assertTrue(
            decisionLogs.any {
                it.contains("rejectReason=duplicate_reattach_accepted") &&
                    it.contains("recoveryReason=NETWORK_RECOVERY")
            }
        )
    }

    @Test
    fun membershipJoinHandler_rejectedAsNonConnectivity() {
        controller.onRecoveryReattachAccepted(
            "sess-1",
            "M03",
            RecoveryReason.NETWORK_RECOVERY,
            RecoverySource.JOIN_HANDLER
        )
        assertEquals(0, iceRestartCalls)
        assertTrue(
            decisionLogs.any {
                it.contains("rejectReason=NON_CONNECTIVITY_TRIGGER") &&
                    it.contains("approved=false")
            }
        )
    }

    @Test
    fun sessionCancellation_blocksRecovery() {
        controller.cancelSession("sess-1", "local_hangup")
        controller.onIceStateChanged(
            sessionId = "sess-1",
            channelId = "CH-1",
            remoteModuleId = "M01",
            iceState = "DISCONNECTED",
            eligibility = eligible(),
            initiatesReattach = true
        )
        nowMs = 100L
        Thread.sleep(80)
        assertEquals(0, reattachCalls)
        assertFalse(controller.factsForSession("sess-1").anyRecovering)
        assertTrue(
            decisionLogs.any {
                it.contains("rejectReason=session_cancelled") &&
                    it.contains("approved=false")
            }
        )
    }

    @Test
    fun staleChannelTombstone_doesNotBlockRecovery() {
        controller.cancelChannel("CH-1", "remote_hangup")
        controller.onIceStateChanged(
            sessionId = "sess-1",
            channelId = "CH-1",
            remoteModuleId = "M01",
            iceState = "DISCONNECTED",
            eligibility = eligible(),
            initiatesReattach = true
        )
        nowMs = 60L
        Thread.sleep(80)
        assertEquals(1, reattachCalls)
        assertTrue(controller.factsForSession("sess-1").anyRecovering)
        assertTrue(
            decisionLogs.any {
                it.contains("RECOVERY_DECISION") &&
                    it.contains("approved=true") &&
                    !it.contains("rejectReason=session_cancelled")
            }
        )
        assertFalse(decisionLogs.any { it.contains("reason=session_cancelled") })
    }

    @Test
    fun ineligibleRemoteLeft_logsRecoveryDecisionRejected() {
        controller.onIceStateChanged(
            sessionId = "sess-1",
            channelId = "CH-1",
            remoteModuleId = "M03",
            iceState = "FAILED",
            eligibility = EdgeRecoveryEligibility(
                lifecycleEstablished = true,
                localJoined = true,
                remoteJoined = false,
                conferenceTerminated = false
            ),
            initiatesReattach = false
        )
        assertTrue(
            decisionLogs.any {
                it.contains("RECOVERY_DECISION") &&
                    it.contains("recoveryReason=ICE_FAILED") &&
                    it.contains("terminationReason=USER_LEAVE") &&
                    it.contains("approved=false")
            }
        )
    }

    @Test
    fun iceConnected_marksRecovered() {
        controller.onIceStateChanged(
            sessionId = "sess-1",
            channelId = "CH-1",
            remoteModuleId = "M01",
            iceState = "FAILED",
            eligibility = eligible(),
            initiatesReattach = true
        )
        assertEquals(1, reattachCalls)
        controller.onIceConnected("sess-1", "M01")
        assertFalse(controller.factsForSession("sess-1").anyRecovering)
        assertFalse(controller.factsForSession("sess-1").anyFailedMediaRecovery)
    }

    @Test
    fun isEdgeRecovering_falseWhenNoRecovery() {
        assertFalse(controller.isEdgeRecovering("sess-1", "M01"))
    }

    @Test
    fun isEdgeRecovering_trueWhileActivelyRecovering() {
        controller.onIceStateChanged(
            sessionId = "sess-1",
            channelId = "CH-1",
            remoteModuleId = "M01",
            iceState = "FAILED",
            eligibility = eligible(),
            initiatesReattach = true
        )
        assertTrue(controller.isEdgeRecovering("sess-1", "M01"))
        assertFalse(controller.isEdgeRecovering("sess-1", "M02"))
    }

    @Test
    fun isEdgeRecovering_falseAfterRecovered() {
        controller.onIceStateChanged(
            sessionId = "sess-1",
            channelId = "CH-1",
            remoteModuleId = "M01",
            iceState = "FAILED",
            eligibility = eligible(),
            initiatesReattach = true
        )
        controller.onIceConnected("sess-1", "M01")
        assertFalse(controller.isEdgeRecovering("sess-1", "M01"))
    }

    @Test
    fun isEdgeRecovering_falseAfterAttemptTimeout() {
        controller.onIceStateChanged(
            sessionId = "sess-1",
            channelId = "CH-1",
            remoteModuleId = "M01",
            iceState = "FAILED",
            eligibility = eligible(),
            initiatesReattach = true
        )
        Thread.sleep(350)
        assertFalse(controller.isEdgeRecovering("sess-1", "M01"))
    }

    @Test
    fun attemptTimeout_exposesFailedMediaRecoveryFacts_notRecovering() {
        controller.onIceStateChanged(
            sessionId = "sess-1",
            channelId = "CH-1",
            remoteModuleId = "M01",
            iceState = "FAILED",
            eligibility = eligible(),
            initiatesReattach = true
        )
        assertTrue(controller.factsForSession("sess-1").anyRecovering)
        // Watchdog uses wall clock: min(attemptBudgetMs=500, iceRestartTimeoutMs+debounce=250) = 250ms
        Thread.sleep(350)
        val facts = controller.factsForSession("sess-1")
        assertFalse(facts.anyRecovering)
        assertTrue(facts.anyFailedMediaRecovery)
        assertTrue(facts.failedRemoteModuleIds.contains("M01"))
        assertTrue(decisionLogs.any { it.contains("FAILED_MEDIA_RECOVERY") && it.contains("attempt_timeout") })
        assertTrue(decisionLogs.any { it.contains("RECOVERY_FINAL_EVALUATION") && it.contains("reason=ATTEMPT_TIMEOUT") })
    }

    @Test
    fun deferredReattach_iceConnected_blocked_emitsReevaluateOnCapabilityChange() {
        controller = buildController(
            onRequestReattach = { _, _, _ ->
                reattachCalls++
                ReattachDispatchOutcome.DEFERRED
            }
        )
        controller.onIceStateChanged(
            sessionId = "sess-1",
            channelId = "CH-1",
            remoteModuleId = "M01",
            iceState = "DISCONNECTED",
            eligibility = eligible(),
            initiatesReattach = true
        )
        nowMs = 60L
        Thread.sleep(80)
        controller.onIceConnected("sess-1", "M01")
        assertTrue(controller.factsForSession("sess-1").anyRecovering)
        assertFalse(decisionLogs.any { it.contains("RECOVERY_EDGE_RECOVERED") })

        val snapshot = EdgeReachabilitySnapshot(
            linkReady = true,
            peerDiscovered = true,
            routeConverged = true,
            authorityReachable = false
        )
        val before = RecoveryCapabilitySignature(
            permittedActions = emptySet(),
            waitingReason = RecoveryWaitingReason.WAITING_FOR_ROUTE
        )
        val after = projectRecoveryCapabilitySignature(
            snapshot,
            initiatesReattach = true,
            controlPlaneStarted = false
        )
        controller.onRecoveryReachabilityChanged(
            sessionId = "sess-1",
            channelId = "CH-1",
            remoteModuleId = "M01",
            snapshot = snapshot,
            signature = after,
            capabilityBefore = before,
            trigger = RecoveryReevaluateTrigger.ROUTE_CONVERGED
        )
        assertTrue(
            decisionLogs.any {
                it.contains("RECOVERY_REEVALUATE") &&
                    it.contains("trigger=ROUTE_CONVERGED") &&
                    it.contains("controlPlaneStarted=false")
            }
        )
        assertFalse(decisionLogs.any { it.contains("RECOVERY_EDGE_RECOVERED") })
        assertEquals(2, reattachCalls)
        assertTrue(
            decisionLogs.any {
                it.contains("decision=DISPATCH_REATTACH") && it.contains("trigger=ROUTE_CONVERGED")
            }
        )
    }

    @Test
    fun failedMediaRecovery_materialTransition_emitsReevaluate() {
        // G-R28-H2: FAILED residency stays OPEN; material transition MUST re-evaluate and
        // MAY SUPERSEDE into Attempt N+1 (new id, never revive the failed attempt id).
        controller.onIceStateChanged(
            sessionId = "sess-1",
            channelId = "CH-1",
            remoteModuleId = "M01",
            iceState = "FAILED",
            eligibility = eligible(),
            initiatesReattach = true
        )
        Thread.sleep(350)
        assertTrue(controller.factsForSession("sess-1").anyFailedMediaRecovery)
        assertTrue(controller.edgeObligationOpen("sess-1", "M01"))
        assertFalse(controller.edgeObligationClosed("sess-1", "M01"))
        val failedAttemptId = decisionLogs
            .last { it.contains("FAILED_MEDIA_RECOVERY") && it.contains("remote=M01") }
            .substringAfter("attempt=")
            .substringBefore(' ')
            .toLong()

        val snapshot = EdgeReachabilitySnapshot(
            linkReady = true,
            peerDiscovered = true,
            routeConverged = true,
            authorityReachable = false
        )
        val before = RecoveryCapabilitySignature(
            permittedActions = emptySet(),
            waitingReason = RecoveryWaitingReason.WAITING_FOR_ROUTE
        )
        val after = projectRecoveryCapabilitySignature(
            snapshot,
            initiatesReattach = true,
            controlPlaneStarted = false
        )
        val logMark = decisionLogs.size
        controller.onRecoveryReachabilityChanged(
            sessionId = "sess-1",
            channelId = "CH-1",
            remoteModuleId = "M01",
            snapshot = snapshot,
            signature = after,
            capabilityBefore = before,
            trigger = RecoveryReevaluateTrigger.ROUTE_CONVERGED
        )
        val afterLogs = decisionLogs.drop(logMark)
        assertTrue(afterLogs.any { it.contains("RECOVERY_REEVALUATE") })
        assertTrue(controller.edgeObligationOpen("sess-1", "M01"))
        assertFalse(controller.edgeObligationClosed("sess-1", "M01"))
        assertEquals(2, reattachCalls)
        assertTrue(
            afterLogs.any {
                it.contains("decision=SUPERSEDED") || it.contains("decision=DISPATCH_REATTACH")
            }
        )
        val nextAttemptId = afterLogs
            .last {
                it.contains("decision=SUPERSEDED") ||
                    it.contains("decision=DISPATCH_REATTACH") ||
                    it.contains("RECOVERY_REATTACH_REQUESTED")
            }
            .substringAfter("attempt=")
            .substringBefore(' ')
            .toLong()
        assertTrue(
            "Attempt N+1 must use a new id, not revive failed attempt=$failedAttemptId",
            nextAttemptId > failedAttemptId
        )
        assertFalse(
            "failed attempt id must not become active again",
            afterLogs.any {
                it.contains("attempt=$failedAttemptId") &&
                    (
                        it.contains("RECOVERY_REATTACH_REQUESTED") ||
                            it.contains("RECOVERY_EDGE_STARTED") ||
                            it.contains("decision=DISPATCH_REATTACH")
                        )
            }
        )
    }

    @Test
    fun failedMediaRecovery_routeRestored_reevaluateThenRecovered() {
        // G-R29-3: FAILED_MEDIA_RECOVERY → route restore → REEVALUATE → RECOVERED;
        // obligation stays OPEN across re-eval (edge not torn down early).
        controller = buildController(
            observationWindowMs = 10_000L,
            attemptBudgetMs = 500L,
            isIceConnected = { _, _ -> true }
        )
        controller.onIceStateChanged(
            sessionId = "sess-1",
            channelId = "CH-1",
            remoteModuleId = "M01",
            iceState = "FAILED",
            eligibility = eligible(),
            initiatesReattach = true
        )
        Thread.sleep(350)
        assertTrue(controller.factsForSession("sess-1").anyFailedMediaRecovery)
        assertTrue(controller.edgeObligationOpen("sess-1", "M01"))
        assertFalse(controller.edgeObligationClosed("sess-1", "M01"))

        val snapshot = EdgeReachabilitySnapshot(
            linkReady = true,
            peerDiscovered = true,
            routeConverged = true,
            authorityReachable = false
        )
        val before = RecoveryCapabilitySignature(
            permittedActions = emptySet(),
            waitingReason = RecoveryWaitingReason.WAITING_FOR_ROUTE
        )
        val after = projectRecoveryCapabilitySignature(
            snapshot,
            initiatesReattach = true,
            controlPlaneStarted = false
        )
        controller.onRecoveryReachabilityChanged(
            sessionId = "sess-1",
            channelId = "CH-1",
            remoteModuleId = "M01",
            snapshot = snapshot,
            signature = after,
            capabilityBefore = before,
            trigger = RecoveryReevaluateTrigger.ROUTE_CONVERGED
        )
        assertTrue(decisionLogs.any { it.contains("RECOVERY_REEVALUATE") })
        assertTrue(controller.edgeObligationOpen("sess-1", "M01"))
        assertFalse(
            decisionLogs.any {
                it.contains("RECOVERY_EDGE_CANCELLED") && it.contains("remote=M01")
            }
        )

        controller.onRecoveryReattachAccepted(
            "sess-1",
            "M01",
            RecoveryReason.NETWORK_RECOVERY,
            RecoverySource.ICE_MONITOR
        )
        assertTrue(
            decisionLogs.any {
                it.contains("RECOVERY_EDGE_RECOVERED") && it.contains("remote=M01")
            }
        )
        assertTrue(
            decisionLogs.any {
                it.contains("RECOVERY_DECISION") && it.contains("decision=RECOVERED")
            }
        )
        assertEquals(ObligationCloseReason.RECOVERED, controller.obligationCloseReason("sess-1", "M01"))
        assertTrue(controller.edgeObligationClosed("sess-1", "M01"))
        assertFalse(controller.edgeObligationOpen("sess-1", "M01"))
        assertFalse(controller.factsForSession("sess-1").anyFailedMediaRecovery)
    }

    @Test
    fun reevaluate_routeConverged_host_waitsForInbound() {
        controller.onIceStateChanged(
            sessionId = "sess-1",
            channelId = "CH-1",
            remoteModuleId = "M01",
            iceState = "FAILED",
            eligibility = eligible(),
            initiatesReattach = false
        )
        val snapshot = EdgeReachabilitySnapshot(
            linkReady = true,
            peerDiscovered = true,
            routeConverged = true,
            authorityReachable = false
        )
        val before = RecoveryCapabilitySignature(
            permittedActions = emptySet(),
            waitingReason = RecoveryWaitingReason.WAITING_FOR_ROUTE
        )
        val after = projectRecoveryCapabilitySignature(
            snapshot,
            initiatesReattach = false,
            controlPlaneStarted = false
        )
        controller.onRecoveryReachabilityChanged(
            sessionId = "sess-1",
            channelId = "CH-1",
            remoteModuleId = "M01",
            snapshot = snapshot,
            signature = after,
            capabilityBefore = before,
            trigger = RecoveryReevaluateTrigger.ROUTE_CONVERGED
        )
        assertEquals(0, reattachCalls)
        assertTrue(decisionLogs.any { it.contains("decision=WAIT_FOR_INBOUND") })
    }

    @Test
    fun obligationFacts_absentEdge_areClosedDefaults() {
        assertFalse(controller.edgeObligationOpen("sess-1", "M01"))
        assertFalse(controller.edgeObligationClosed("sess-1", "M01"))
        assertEquals(null, controller.obligationDeadlineAt("sess-1", "M01"))
        assertEquals(null, controller.obligationCloseReason("sess-1", "M01"))
        assertFalse(controller.hasPendingCompletionDecision("sess-1", "M01"))
    }

    @Test
    fun obligationFacts_stayOpenAfterFailedMediaRecovery() {
        controller.onIceStateChanged(
            sessionId = "sess-1",
            channelId = "CH-1",
            remoteModuleId = "M01",
            iceState = "FAILED",
            eligibility = eligible(),
            initiatesReattach = true
        )
        assertTrue(controller.edgeObligationOpen("sess-1", "M01"))
        assertFalse(controller.edgeObligationClosed("sess-1", "M01"))
        Thread.sleep(350)
        assertTrue(controller.edgeObligationOpen("sess-1", "M01"))
        assertFalse(controller.edgeObligationClosed("sess-1", "M01"))
        assertEquals(nowMs + 10_000L, controller.obligationDeadlineAt("sess-1", "M01"))
        assertEquals(null, controller.obligationCloseReason("sess-1", "M01"))
        assertFalse(controller.hasPendingCompletionDecision("sess-1", "M01"))
    }

    @Test
    fun obligationDeadline_pastWindow_closesWithObligationDeadline() {
        // G-R28-H3: controller-owned deadline closes obligation exclusively.
        // Watchdog budget = min(120, 250) = 120ms; observation window = 150ms after FAILED.
        controller = buildController(observationWindowMs = 150L, attemptBudgetMs = 120L)
        controller.onIceStateChanged(
            sessionId = "sess-1",
            channelId = "CH-1",
            remoteModuleId = "M01",
            iceState = "FAILED",
            eligibility = eligible(),
            initiatesReattach = true
        )
        Thread.sleep(200)
        assertTrue(controller.edgeObligationOpen("sess-1", "M01"))
        assertFalse(controller.edgeObligationClosed("sess-1", "M01"))
        assertEquals(nowMs + 150L, controller.obligationDeadlineAt("sess-1", "M01"))

        Thread.sleep(200)
        assertTrue(controller.edgeObligationClosed("sess-1", "M01"))
        assertFalse(controller.edgeObligationOpen("sess-1", "M01"))
        assertEquals(
            ObligationCloseReason.OBLIGATION_DEADLINE,
            controller.obligationCloseReason("sess-1", "M01")
        )
        assertTrue(ObligationCloseReason.OBLIGATION_DEADLINE.isPruneEligible())
        assertFalse(controller.hasPendingCompletionDecision("sess-1", "M01"))
    }

    @Test
    fun obligationCloseReason_nonDeadline_isNotPruneEligible() {
        assertFalse(ObligationCloseReason.RECOVERED.isPruneEligible())
        assertFalse(ObligationCloseReason.MEMBERSHIP_LEFT.isPruneEligible())
        assertFalse(ObligationCloseReason.CONFERENCE_TERMINATED.isPruneEligible())
    }

    @Test
    fun supersedeAfterFailed_cancelsPriorObligationDeadline() {
        // Stale OBLIGATION_DEADLINE timer must not close while Attempt N+1 is active.
        controller = buildController(observationWindowMs = 120L, attemptBudgetMs = 120L)
        controller.onIceStateChanged(
            sessionId = "sess-1",
            channelId = "CH-1",
            remoteModuleId = "M01",
            iceState = "FAILED",
            eligibility = eligible(),
            initiatesReattach = true
        )
        Thread.sleep(200)
        assertTrue(controller.edgeObligationOpen("sess-1", "M01"))
        assertNotNull(controller.obligationDeadlineAt("sess-1", "M01"))

        val snapshot = EdgeReachabilitySnapshot(
            linkReady = true,
            peerDiscovered = true,
            routeConverged = true,
            authorityReachable = false
        )
        val before = RecoveryCapabilitySignature(
            permittedActions = emptySet(),
            waitingReason = RecoveryWaitingReason.WAITING_FOR_ROUTE
        )
        val after = projectRecoveryCapabilitySignature(
            snapshot,
            initiatesReattach = true,
            controlPlaneStarted = false
        )
        controller.onRecoveryReachabilityChanged(
            sessionId = "sess-1",
            channelId = "CH-1",
            remoteModuleId = "M01",
            snapshot = snapshot,
            signature = after,
            capabilityBefore = before,
            trigger = RecoveryReevaluateTrigger.ROUTE_CONVERGED
        )
        assertEquals(null, controller.obligationDeadlineAt("sess-1", "M01"))
        assertTrue(controller.edgeObligationOpen("sess-1", "M01"))
        assertFalse(controller.edgeObligationClosed("sess-1", "M01"))

        Thread.sleep(200)
        assertTrue(controller.edgeObligationOpen("sess-1", "M01"))
        assertFalse(controller.edgeObligationClosed("sess-1", "M01"))
        assertEquals(null, controller.obligationCloseReason("sess-1", "M01"))
    }

    @Test
    fun obligationFacts_closeOnRecovered() {
        controller.onIceStateChanged(
            sessionId = "sess-1",
            channelId = "CH-1",
            remoteModuleId = "M01",
            iceState = "FAILED",
            eligibility = eligible(),
            initiatesReattach = true
        )
        controller.onIceConnected("sess-1", "M01")
        assertFalse(controller.edgeObligationOpen("sess-1", "M01"))
        assertTrue(controller.edgeObligationClosed("sess-1", "M01"))
        assertEquals(ObligationCloseReason.RECOVERED, controller.obligationCloseReason("sess-1", "M01"))
        assertFalse(controller.hasPendingCompletionDecision("sess-1", "M01"))
    }

    @Test
    fun accepted_thenIceConnected_withinBudget_emitsRecovered() {
        // #83 Test A: ACCEPTED → ICE CONNECTED → RECOVERED (via completion evaluation).
        controller = buildController(attemptBudgetMs = 500L)
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
        val acceptedAttempt = decisionLogs
            .last { it.contains("RECOVERY_REATTACH_ACCEPTED") && it.contains("remote=M01") }
            .substringAfter("attempt=")
            .substringBefore(' ')
            .toLong()
        assertEquals(1, iceRestartCalls)
        assertTrue(controller.isControlPlaneStarted("sess-1", "M01"))

        controller.onIceConnected("sess-1", "M01")

        assertTrue(
            decisionLogs.any {
                it.contains("RECOVERY_EDGE_RECOVERED") && it.contains("attempt=$acceptedAttempt")
            }
        )
        assertTrue(
            decisionLogs.any {
                it.contains("RECOVERY_DECISION") &&
                    it.contains("decision=RECOVERED") &&
                    it.contains("attempt=$acceptedAttempt")
            }
        )
        assertFalse(controller.factsForSession("sess-1").anyFailedMediaRecovery)
        assertFalse(controller.isEdgeRecovering("sess-1", "M01"))
        assertEquals(ObligationCloseReason.RECOVERED, controller.obligationCloseReason("sess-1", "M01"))
        assertFalse(
            decisionLogs.any {
                it.contains("FAILED_MEDIA_RECOVERY") && it.contains("attempt=$acceptedAttempt")
            }
        )
    }

    @Test
    fun accepted_whenIceAlreadyConnected_recoversWithoutNewIceEvent() {
        // Soak gap: ICE already CONNECTED at ACCEPTED → must feed completion evaluation.
        var iceConnected = true
        controller = buildController(
            attemptBudgetMs = 500L,
            isIceConnected = { _, _ -> iceConnected }
        )
        controller.onIceStateChanged(
            sessionId = "sess-1",
            channelId = "CH-1",
            remoteModuleId = "M03",
            iceState = "FAILED",
            eligibility = eligible(),
            initiatesReattach = false
        )
        controller.onRecoveryReattachAccepted(
            "sess-1",
            "M03",
            RecoveryReason.NETWORK_RECOVERY,
            RecoverySource.ICE_MONITOR
        )
        assertEquals(1, iceRestartCalls)
        assertTrue(decisionLogs.any { it.contains("RECOVERY_EDGE_RECOVERED") })
        assertTrue(
            decisionLogs.any {
                it.contains("RECOVERY_DECISION") && it.contains("decision=RECOVERED")
            }
        )
        assertFalse(controller.factsForSession("sess-1").anyFailedMediaRecovery)
        iceConnected = false
    }

    @Test
    fun accepted_iceRestartApiFailsButIceConnected_stillRecovers() {
        // Restart call may fail while media is already up — still complete via evaluation.
        controller = buildController(
            attemptBudgetMs = 500L,
            onIceRestart = { _, _ ->
                iceRestartCalls++
                false
            },
            isIceConnected = { _, _ -> true }
        )
        controller.onIceStateChanged(
            sessionId = "sess-1",
            channelId = "CH-1",
            remoteModuleId = "M04",
            iceState = "FAILED",
            eligibility = eligible(),
            initiatesReattach = false
        )
        controller.onRecoveryReattachAccepted(
            "sess-1",
            "M04",
            RecoveryReason.NETWORK_RECOVERY,
            RecoverySource.ICE_MONITOR
        )
        assertEquals(1, iceRestartCalls)
        assertTrue(decisionLogs.any { it.contains("RECOVERY_EDGE_RECOVERED") })
        assertFalse(controller.factsForSession("sess-1").anyFailedMediaRecovery)
        assertFalse(
            decisionLogs.any {
                it.contains("FAILED_MEDIA_RECOVERY") && it.contains("ice_restart_failed")
            }
        )
    }

    @Test
    fun accepted_iceNeverRecovers_stillAttemptTimeout() {
        // #83 Test B: ACCEPTED then ICE never recovers → ATTEMPT_TIMEOUT preserved.
        controller = buildController(attemptBudgetMs = 120L)
        controller.onIceStateChanged(
            sessionId = "sess-1",
            channelId = "CH-1",
            remoteModuleId = "M02",
            iceState = "FAILED",
            eligibility = eligible(),
            initiatesReattach = false
        )
        controller.onRecoveryReattachAccepted(
            "sess-1",
            "M02",
            RecoveryReason.NETWORK_RECOVERY,
            RecoverySource.ICE_MONITOR
        )
        val acceptedAttempt = decisionLogs
            .last { it.contains("RECOVERY_REATTACH_ACCEPTED") }
            .substringAfter("attempt=")
            .substringBefore(' ')
            .toLong()
        assertEquals(1, iceRestartCalls)
        assertTrue(controller.isEdgeRecovering("sess-1", "M02"))

        Thread.sleep(200)
        assertTrue(
            decisionLogs.any {
                it.contains("RECOVERY_FINAL_EVALUATION") &&
                    it.contains("reason=ATTEMPT_TIMEOUT") &&
                    it.contains("attempt=$acceptedAttempt")
            }
        )
        assertTrue(
            decisionLogs.any {
                it.contains("FAILED_MEDIA_RECOVERY") &&
                    it.contains("attempt=$acceptedAttempt") &&
                    it.contains("attempt_timeout")
            }
        )
        assertFalse(decisionLogs.any { it.contains("RECOVERY_EDGE_RECOVERED") })
        assertTrue(controller.factsForSession("sess-1").anyFailedMediaRecovery)
    }

    @Test
    fun reattachAccepted_supersedesAttempt_oldWatchdogDoesNotFailSupersededAttempt() {
        // #79: ACCEPTED must SUPERSEDE + cancel old attempt watchdog.
        // budget=min(120, 200+50)=120ms. Accept mid-budget so old timer would fire first.
        controller = buildController(attemptBudgetMs = 120L)
        controller.onIceStateChanged(
            sessionId = "sess-1",
            channelId = "CH-1",
            remoteModuleId = "M01",
            iceState = "FAILED",
            eligibility = eligible(),
            initiatesReattach = true
        )
        val startedAttempt = decisionLogs
            .first { it.contains("RECOVERY_EDGE_STARTED") && it.contains("remote=M01") }
            .substringAfter("attempt=")
            .substringBefore(' ')
            .toLong()

        Thread.sleep(70)
        controller.onRecoveryReattachAccepted(
            "sess-1",
            "M01",
            RecoveryReason.HOST_REATTACH,
            RecoverySource.ICE_MONITOR
        )

        assertTrue(
            decisionLogs.any {
                it.contains("decision=SUPERSEDED") && it.contains("priorAttempt=$startedAttempt")
            }
        )
        val acceptedAttempt = decisionLogs
            .last { it.contains("RECOVERY_REATTACH_ACCEPTED") && it.contains("remote=M01") }
            .substringAfter("attempt=")
            .substringBefore(' ')
            .toLong()
        assertTrue(
            "ACCEPTED must own a new attempt id (was $startedAttempt, got $acceptedAttempt)",
            acceptedAttempt > startedAttempt
        )
        assertEquals(1, iceRestartCalls)
        assertTrue(controller.isEdgeRecovering("sess-1", "M01"))

        // Past when the superseded attempt's watchdog would have fired.
        Thread.sleep(80)
        assertFalse(
            "old attempt watchdog must not emit FAILED for superseded attempt=$startedAttempt",
            decisionLogs.any {
                it.contains("FAILED_MEDIA_RECOVERY") && it.contains("attempt=$startedAttempt")
            }
        )
        assertTrue(controller.isEdgeRecovering("sess-1", "M01"))
        assertFalse(controller.factsForSession("sess-1").anyFailedMediaRecovery)

        // New attempt's own budget may still expire later — with new attempt id only.
        Thread.sleep(120)
        assertFalse(
            decisionLogs.any {
                it.contains("FAILED_MEDIA_RECOVERY") && it.contains("attempt=$startedAttempt")
            }
        )
        assertTrue(
            "new attempt owns its watchdog budget and may fail as attempt=$acceptedAttempt",
            decisionLogs.any {
                it.contains("FAILED_MEDIA_RECOVERY") && it.contains("attempt=$acceptedAttempt")
            }
        )
    }

    @Test
    fun reattachAccepted_afterFailedResidency_supersedesAndStartsNewAttempt() {
        // Soak fddec479: FAILED then ACCEPTED must not keep attempt=N.
        controller = buildController(attemptBudgetMs = 120L)
        controller.onIceStateChanged(
            sessionId = "sess-1",
            channelId = "CH-1",
            remoteModuleId = "M02",
            iceState = "FAILED",
            eligibility = eligible(),
            initiatesReattach = false
        )
        Thread.sleep(200)
        assertTrue(controller.factsForSession("sess-1").anyFailedMediaRecovery)
        val failedAttempt = decisionLogs
            .last { it.contains("FAILED_MEDIA_RECOVERY") && it.contains("remote=M02") }
            .substringAfter("attempt=")
            .substringBefore(' ')
            .toLong()

        decisionLogs.clear()
        controller.onRecoveryReattachAccepted(
            "sess-1",
            "M02",
            RecoveryReason.NETWORK_RECOVERY,
            RecoverySource.ICE_MONITOR
        )
        assertTrue(decisionLogs.any { it.contains("decision=SUPERSEDED") })
        val acceptedAttempt = decisionLogs
            .last { it.contains("RECOVERY_REATTACH_ACCEPTED") }
            .substringAfter("attempt=")
            .substringBefore(' ')
            .toLong()
        assertTrue(acceptedAttempt > failedAttempt)
        assertEquals(1, iceRestartCalls)
        assertTrue(controller.isEdgeRecovering("sess-1", "M02"))
        assertFalse(controller.factsForSession("sess-1").anyFailedMediaRecovery)
    }
}
