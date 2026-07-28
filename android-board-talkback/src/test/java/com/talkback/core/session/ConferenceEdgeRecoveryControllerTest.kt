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
import java.util.concurrent.TimeUnit

class ConferenceEdgeRecoveryControllerTest {
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private var nowMs = 0L
    private var reattachCalls = 0
    private var iceRestartCalls = 0
    private val decisionLogs = mutableListOf<String>()
    private lateinit var controller: ConferenceEdgeRecoveryController

    /**
     * ADR-0032 INV-REC-013: the media-action gate MUST NOT default to a permissive
     * constant. The default evaluates the production predicate over these facts, with
     * the media plane down so the harness exercises the admission path the coordinator
     * wires. Tests that need the gate closed mutate the transport or signaling planes.
     */
    private var harnessReachability = defaultHarnessReachability()

    @Before
    fun setUp() {
        nowMs = 0L
        reattachCalls = 0
        iceRestartCalls = 0
        decisionLogs.clear()
        harnessReachability = defaultHarnessReachability()
        controller = buildController(observationWindowMs = 10_000L)
    }

    private fun defaultHarnessReachability() = EdgeReachabilitySnapshot(
        linkReady = true,
        peerDiscovered = true,
        peerSignalingReachable = true,
        mediaRouteConnected = false,
        authorityReachable = true
    )

    private fun buildController(
        observationWindowMs: Long = 10_000L,
        attemptBudgetMs: Long = 500L,
        debounceMs: Long = 50L,
        onRequestReattach: (String, String, String) -> ReattachDispatchOutcome = { _, _, _ ->
            reattachCalls++
            ReattachDispatchOutcome.SENT
        },
        onIceRestart: (String, String) -> Boolean = { _, _ ->
            iceRestartCalls++
            true
        },
        isIceConnected: (String, String) -> Boolean = { _, _ -> false },
        canDispatchRecoveryMediaAction: (String, String) -> Boolean = { _, _ ->
            harnessReachability.canDispatchRecoverySignal()
        }
    ) = ConferenceEdgeRecoveryController(
        debounceMs = debounceMs,
        iceRestartTimeoutMs = 200L,
        attemptBudgetMs = attemptBudgetMs,
        observationWindowMs = observationWindowMs,
        clock = { nowMs },
        scheduler = scheduler,
        onLog = { message -> decisionLogs.add(message) },
        onRequestReattach = onRequestReattach,
        onIceRestart = onIceRestart,
        isIceConnected = isIceConnected,
        canDispatchRecoveryMediaAction = canDispatchRecoveryMediaAction
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

    private fun participantReattachCapabilityBeforeRoute(): RecoveryCapabilitySignature =
        RecoveryCapabilitySignature(
            permittedActions = setOf(RecoveryAction.DISPATCH_REATTACH),
            waitingReason = null
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
        assertTrue(
            decisionLogs.any {
                it.contains("RECOVERY_MEDIA_OWNER_ASSIGNED") &&
                    it.contains("owner=PARTICIPANT_REATTACH")
            }
        )
        assertTrue(
            decisionLogs.any {
                it.contains("RECOVERY_MEDIA_ACTION_DEFERRED") &&
                    it.contains("disposition=DEFERRED") &&
                    it.contains("deferredReason=MEDIA_NOT_READY") &&
                    it.contains("wakeupBinding=ROUTE_CONVERGED/edge(sess-1,M01)")
            }
        )
    }

    @Test
    fun gC2_5_deferredReattach_capabilityBlocked_doesNotTimeout() {
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
            iceState = "FAILED",
            eligibility = eligible(),
            initiatesReattach = true
        )
        Thread.sleep(350)
        assertTrue(controller.factsForSession("sess-1").anyRecovering)
        assertFalse(controller.factsForSession("sess-1").anyFailedMediaRecovery)
        assertTrue(decisionLogs.any { it.contains("RECOVERY_WATCHDOG_DEFERRED") })
        assertFalse(
            decisionLogs.any {
                it.contains("EXPLICIT_RECOVERY_ABORT") && it.contains("reason=OWNER_BLOCKED")
            }
        )
        assertFalse(
            decisionLogs.any {
                it.contains("EXPLICIT_RECOVERY_ABORT") && it.contains("NO_MEDIA_ACTION_OWNER")
            }
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
        Thread.sleep(150)
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
        Thread.sleep(150)
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
        assertEquals(2, iceRestartCalls)
        controller.onRecoveryReattachAccepted(
            "sess-1",
            "M02",
            RecoveryReason.NETWORK_RECOVERY,
            RecoverySource.ICE_MONITOR
        )
        assertEquals(2, iceRestartCalls)
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
        controller.onRecoveryReattachAccepted(
            "sess-1",
            "M01",
            RecoveryReason.HOST_REATTACH,
            RecoverySource.ICE_MONITOR
        )
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
        controller.onRecoveryReattachAccepted(
            "sess-1",
            "M01",
            RecoveryReason.HOST_REATTACH,
            RecoverySource.ICE_MONITOR
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
    fun gC2_iceRestartOnly_edgeStarted_assignsOwnerAndDispatchesRestart() {
        controller.onIceStateChanged(
            sessionId = "sess-1",
            channelId = "CH-1",
            remoteModuleId = "M03",
            iceState = "FAILED",
            eligibility = eligible(),
            initiatesReattach = false
        )
        assertEquals(1, iceRestartCalls)
        assertTrue(
            decisionLogs.any {
                it.contains("RECOVERY_MEDIA_ACTION_ASSIGNMENT") && it.contains("owner=HOST_RESTART")
            }
        )
        assertTrue(
            decisionLogs.any {
                it.contains("RECOVERY_MEDIA_OWNER_ASSIGNED") && it.contains("owner=HOST_RESTART")
            }
        )
        assertTrue(
            decisionLogs.any {
                it.contains("RECOVERY_ICE_RESTART_DISPATCHED") && it.contains("remote=M03")
            }
        )
        assertFalse(
            decisionLogs.any {
                it.contains("EXPLICIT_RECOVERY_ABORT") && it.contains("NO_MEDIA_ACTION_OWNER")
            }
        )
    }

    /**
     * Recovery Dispatch Eligibility Contract ??mirrors the production wiring where
     * [canDispatchRecoveryMediaAction] is [EdgeReachabilitySnapshot.canDispatchRecoverySignal].
     * ICE_DISCONNECTED takes the non-immediate path, so this gate decides whether the host
     * may restart ICE. Media-plane state MUST NOT block the action that restores it.
     */
    @Test
    fun dispatchContract_host_mediaDown_stillDispatchesIceRestart() {
        val mediaDown = EdgeReachabilitySnapshot(
            linkReady = true,
            peerDiscovered = true,
            peerSignalingReachable = true,
            mediaRouteConnected = false,
            authorityReachable = true
        )
        controller = buildController(
            canDispatchRecoveryMediaAction = { _, _ -> mediaDown.canDispatchRecoverySignal() }
        )
        controller.onIceStateChanged(
            sessionId = "sess-1",
            channelId = "CH-1",
            remoteModuleId = "M03",
            iceState = "DISCONNECTED",
            eligibility = eligible(),
            initiatesReattach = false
        )
        Thread.sleep(250L)
        assertFalse(
            "media-plane state must not defer host media action",
            decisionLogs.any {
                it.contains("RECOVERY_MEDIA_ACTION_DEFERRED") &&
                    it.contains("deferredReason=MEDIA_NOT_READY")
            }
        )
        assertTrue(
            decisionLogs.any {
                it.contains("RECOVERY_ICE_RESTART_DISPATCHED") && it.contains("remote=M03")
            }
        )
    }

    @Test
    fun gC2_reattachInbound_supersedesThenDispatchesOnNewAttempt() {
        controller.onIceStateChanged(
            sessionId = "sess-1",
            channelId = "CH-1",
            remoteModuleId = "M03",
            iceState = "FAILED",
            eligibility = eligible(),
            initiatesReattach = false
        )
        val startedAttempt = decisionLogs
            .first { it.contains("RECOVERY_EDGE_STARTED") && it.contains("remote=M03") }
            .substringAfter("attempt=")
            .substringBefore(' ')
            .toLong()
        assertEquals(1, iceRestartCalls)

        controller.onRecoveryReattachAccepted(
            "sess-1",
            "M03",
            RecoveryReason.NETWORK_RECOVERY,
            RecoverySource.ICE_MONITOR
        )
        assertTrue(decisionLogs.any { it.contains("RECOVERY_HANDOFF_TO_REATTACH") })
        assertTrue(decisionLogs.any { it.contains("RECOVERY_ATTEMPT_SUPERSEDED") })
        val acceptedAttempt = decisionLogs
            .last { it.contains("RECOVERY_REATTACH_ACCEPTED") && it.contains("remote=M03") }
            .substringAfter("attempt=")
            .substringBefore(' ')
            .toLong()
        assertTrue(acceptedAttempt > startedAttempt)
        assertEquals(2, iceRestartCalls)
        assertTrue(
            decisionLogs.any {
                it.contains("RECOVERY_ICE_RESTART_DISPATCHED") &&
                    it.contains("attempt=$acceptedAttempt")
            }
        )
    }

    @Test
    fun gC2_iceRestartDispatchFails_entersFailedMediaRecoveryNotNoOwnerAbort() {
        controller = buildController(
            onIceRestart = { _, _ ->
                iceRestartCalls++
                false
            },
            isIceConnected = { _, _ -> false }
        )
        controller.onIceStateChanged(
            sessionId = "sess-1",
            channelId = "CH-1",
            remoteModuleId = "M03",
            iceState = "FAILED",
            eligibility = eligible(),
            initiatesReattach = false
        )
        assertEquals(1, iceRestartCalls)
        assertTrue(
            decisionLogs.any {
                it.contains("RECOVERY_MEDIA_OWNER_ASSIGNED") && it.contains("owner=HOST_RESTART")
            }
        )
        assertTrue(
            decisionLogs.any {
                it.contains("FAILED_MEDIA_RECOVERY") && it.contains("ice_restart_failed")
            }
        )
        assertFalse(
            decisionLogs.any {
                it.contains("EXPLICIT_RECOVERY_ABORT") && it.contains("NO_MEDIA_ACTION_OWNER")
            }
        )
    }

    @Test
    fun gC1_iceRestartOnly_deferredUntilActionGateReady_thenDispatches() {
        var actionReady = false
        controller = buildController(
            canDispatchRecoveryMediaAction = { _, _ -> actionReady }
        )
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
        assertEquals(0, iceRestartCalls)
        assertTrue(decisionLogs.any { it.contains("RECOVERY_MEDIA_ACTION_DEFERRED") })
        assertTrue(
            decisionLogs.any {
                it.contains("RECOVERY_MEDIA_OWNER_ASSIGNED") && it.contains("owner=HOST_RESTART")
            }
        )
        assertTrue(
            decisionLogs.any {
                it.contains("wakeupBinding=ROUTE_CONVERGED/edge(sess-1,M02)")
            }
        )

        actionReady = true
        val snapshot = EdgeReachabilitySnapshot(
            linkReady = true,
            peerDiscovered = true,
            peerSignalingReachable = true,
            mediaRouteConnected = false,
            authorityReachable = false
        )
        val before = RecoveryCapabilitySignature(
            permittedActions = emptySet(),
            waitingReason = RecoveryWaitingReason.WAITING_FOR_DISCOVERY
        )
        val after = projectRecoveryCapabilitySignature(
            snapshot,
            initiatesReattach = false,
            controlPlaneStarted = false
        )
        controller.onRecoveryReachabilityChanged(
            sessionId = "sess-1",
            channelId = "CH-1",
            remoteModuleId = "M02",
            snapshot = snapshot,
            signature = after,
            capabilityBefore = before,
            trigger = RecoveryReevaluateTrigger.PEER_REACHABILITY_RESTORED
        )
        assertEquals(1, iceRestartCalls)
        assertTrue(decisionLogs.any { it.contains("RECOVERY_ICE_RESTART_DISPATCHED") })
    }

    @Test
    fun r28k_actionGateBlocked_watchdogDeferred_noAttemptTimeoutUntilDispatchReady() {
        var actionReady = false
        controller = buildController(
            canDispatchRecoveryMediaAction = { _, _ -> actionReady }
        )
        controller.onIceStateChanged(
            sessionId = "sess-1",
            channelId = "CH-1",
            remoteModuleId = "M01",
            iceState = "DISCONNECTED",
            eligibility = eligible(),
            initiatesReattach = false
        )
        val attemptAtStart = decisionLogs
            .last { it.contains("RECOVERY_ATTEMPT_OPENED") && it.contains("remote=M01") }
            .substringAfter("attemptId=")
            .substringBefore(' ')
            .toLong()
        Thread.sleep(350)
        assertTrue(controller.isEdgeRecovering("sess-1", "M01"))
        assertFalse(decisionLogs.any { it.contains("RECOVERY_ATTEMPT_TIMEOUT") && it.contains("remote=M01") })
        assertFalse(decisionLogs.any { it.contains("OWNER_BLOCKED") && it.contains("remote=M01") })
        assertTrue(decisionLogs.any { it.contains("RECOVERY_WATCHDOG_DEFERRED") && it.contains("edge=M01") })

        actionReady = true
        val snapshot = EdgeReachabilitySnapshot(
            linkReady = true,
            peerDiscovered = true,
            peerSignalingReachable = true,
            mediaRouteConnected = false,
            authorityReachable = true
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
            capabilityBefore = RecoveryCapabilitySignature(
                permittedActions = emptySet(),
                waitingReason = RecoveryWaitingReason.WAITING_FOR_DISCOVERY
            ),
            trigger = RecoveryReevaluateTrigger.PEER_REACHABILITY_RESTORED
        )
        assertTrue(decisionLogs.any { it.contains("RECOVERY_ICE_RESTART_DISPATCHED") && it.contains("remote=M01") })
        assertTrue(decisionLogs.any { it.contains("RECOVERY_WATCHDOG_STARTED") && it.contains("attempt=$attemptAtStart") })
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
        assertTrue(facts.mediaUnavailableRemoteModuleIds.contains("M01"))
        assertFalse(controller.isMediaUnavailable("sess-1", "M02"))
        assertTrue(controller.isMediaUnavailable("sess-1", "M01"))
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
            peerSignalingReachable = true,
            mediaRouteConnected = true,
            authorityReachable = false
        )
        val before = participantReattachCapabilityBeforeRoute()
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
            peerSignalingReachable = true,
            mediaRouteConnected = true,
            authorityReachable = false
        )
        val before = participantReattachCapabilityBeforeRoute()
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
            peerSignalingReachable = true,
            mediaRouteConnected = true,
            authorityReachable = false
        )
        val before = participantReattachCapabilityBeforeRoute()
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
    fun reevaluate_mediaRouteConnected_host_dispatchesWhenStillPending() {
        controller.onIceStateChanged(
            sessionId = "sess-1",
            channelId = "CH-1",
            remoteModuleId = "M01",
            iceState = "FAILED",
            eligibility = eligible(),
            initiatesReattach = false
        )
        assertEquals(1, iceRestartCalls)
        iceRestartCalls = 0
        decisionLogs.clear()

        val snapshot = EdgeReachabilitySnapshot(
            linkReady = true,
            peerDiscovered = true,
            peerSignalingReachable = true,
            mediaRouteConnected = true,
            authorityReachable = false
        )
        val before = RecoveryCapabilitySignature(
            permittedActions = emptySet(),
            waitingReason = RecoveryWaitingReason.WAITING_FOR_INBOUND
        )
        val after = projectRecoveryCapabilitySignature(
            snapshot,
            initiatesReattach = false,
            controlPlaneStarted = true
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
        assertEquals(0, iceRestartCalls)
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
        assertFalse(ObligationCloseReason.OBLIGATION_DEADLINE.isPruneEligible())
        assertFalse(controller.hasPendingCompletionDecision("sess-1", "M01"))
    }

    @Test
    fun obligationCloseReason_v2_noReasonIsPruneEligible() {
        assertFalse(ObligationCloseReason.OBLIGATION_DEADLINE.isPruneEligible())
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
            peerSignalingReachable = true,
            mediaRouteConnected = true,
            authorityReachable = false
        )
        val before = participantReattachCapabilityBeforeRoute()
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
        controller.onRecoveryReattachAccepted(
            "sess-1",
            "M01",
            RecoveryReason.HOST_REATTACH,
            RecoverySource.ICE_MONITOR
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
    fun r28k_capabilityBlocked_iceChecking_staysOnSameAttempt() {
        controller = buildController(
            canDispatchRecoveryMediaAction = { _, _ -> false }
        )
        controller.onIceStateChanged(
            sessionId = "sess-1",
            channelId = "CH-1",
            remoteModuleId = "M02",
            iceState = "DISCONNECTED",
            eligibility = eligible(),
            initiatesReattach = false
        )
        Thread.sleep(600)
        assertTrue(controller.factsForSession("sess-1").anyRecovering)
        assertFalse(controller.factsForSession("sess-1").anyFailedMediaRecovery)
        val priorAttemptId = decisionLogs
            .last { it.contains("RECOVERY_MEDIA_ACTION_DEFERRED") && it.contains("remote=M02") }
            .substringAfter("attempt=")
            .substringBefore(' ')
            .toLong()
        iceRestartCalls = 0
        decisionLogs.clear()

        val snapshot = EdgeReachabilitySnapshot(
            linkReady = true,
            peerDiscovered = true,
            peerSignalingReachable = true,
            mediaRouteConnected = false,
            authorityReachable = true
        )
        val signature = projectRecoveryCapabilitySignature(
            snapshot,
            initiatesReattach = false,
            controlPlaneStarted = false
        )
        controller.onRecoveryReachabilityChanged(
            sessionId = "sess-1",
            channelId = "CH-1",
            remoteModuleId = "M02",
            snapshot = snapshot,
            signature = signature,
            capabilityBefore = signature,
            trigger = RecoveryReevaluateTrigger.ICE_CHECKING
        )
        assertTrue(decisionLogs.any { it.contains("RECOVERY_REEVALUATE") && it.contains("ICE_CHECKING") })
        assertFalse(decisionLogs.any { it.contains("decision=SUPERSEDED") && it.contains("edge=M02") })
        assertTrue(decisionLogs.any { it.contains("attempt=$priorAttemptId") })
        assertEquals(0, iceRestartCalls)
        assertTrue(controller.isEdgeRecovering("sess-1", "M02"))
        assertTrue(controller.edgeObligationOpen("sess-1", "M02"))
    }

    @Test
    fun gC3_2_deferredAttempt_remoteModuleRecovered_triggersReevaluate() {
        controller = buildController(
            onRequestReattach = { _, _, _ ->
                reattachCalls++
                ReattachDispatchOutcome.DEFERRED
            }
        )
        controller.onIceStateChanged(
            sessionId = "sess-1",
            channelId = "CH-1",
            remoteModuleId = "M02",
            iceState = "DISCONNECTED",
            eligibility = eligible(),
            initiatesReattach = true
        )
        nowMs = 60L
        Thread.sleep(150)
        assertTrue(
            decisionLogs.any {
                it.contains("RECOVERY_MEDIA_ACTION_DEFERRED") &&
                    it.contains("remote=M02") &&
                    it.contains("wakeupBinding=ROUTE_CONVERGED/edge(sess-1,M02)")
            }
        )
        assertTrue(
            controller.hasDeferredWakeupForTrigger(
                "sess-1",
                "M02",
                RecoveryReevaluateTrigger.REMOTE_MODULE_RECOVERED
            )
        )
        val attempt = decisionLogs
            .last { it.contains("RECOVERY_MEDIA_ACTION_DEFERRED") && it.contains("remote=M02") }
            .substringAfter("attempt=")
            .substringBefore(' ')
            .toLong()
        decisionLogs.clear()

        val snapshot = EdgeReachabilitySnapshot(
            linkReady = true,
            peerDiscovered = true,
            peerSignalingReachable = true,
            mediaRouteConnected = true,
            authorityReachable = false
        )
        val signature = projectRecoveryCapabilitySignature(
            snapshot,
            initiatesReattach = true,
            controlPlaneStarted = false
        )
        controller.onRecoveryReachabilityChanged(
            sessionId = "sess-1",
            channelId = "CH-1",
            remoteModuleId = "M02",
            snapshot = snapshot,
            signature = signature,
            capabilityBefore = signature,
            trigger = RecoveryReevaluateTrigger.REMOTE_MODULE_RECOVERED
        )
        assertTrue(
            decisionLogs.any {
                it.contains("RECOVERY_REEVALUATE") &&
                    it.contains("edge=M02") &&
                    it.contains("trigger=REMOTE_MODULE_RECOVERED") &&
                    it.contains("attempt=$attempt")
            }
        )
        assertTrue(
            decisionLogs.any {
                it.contains("decision=DISPATCH_REATTACH") &&
                    it.contains("edge=M02") &&
                    it.contains("trigger=REMOTE_MODULE_RECOVERED")
            }
        )
    }

    @Test
    fun gC3_2_deferredAttempt_peerDiscovered_dispatchesReattach() {
        var dispatchRound = 0
        controller = buildController(
            onRequestReattach = { _, _, _ ->
                reattachCalls++
                dispatchRound++
                if (dispatchRound == 1) ReattachDispatchOutcome.DEFERRED else ReattachDispatchOutcome.SENT
            }
        )
        controller.onIceStateChanged(
            sessionId = "sess-1",
            channelId = "CH-1",
            remoteModuleId = "M02",
            iceState = "DISCONNECTED",
            eligibility = eligible(),
            initiatesReattach = true
        )
        nowMs = 60L
        Thread.sleep(150)
        val before = projectRecoveryCapabilitySignature(
            EdgeReachabilitySnapshot(
                linkReady = true,
                peerDiscovered = false,
                peerSignalingReachable = true,
                mediaRouteConnected = false,
                authorityReachable = false
            ),
            initiatesReattach = true,
            controlPlaneStarted = false
        )
        val readySnapshot = EdgeReachabilitySnapshot(
            linkReady = true,
            peerDiscovered = true,
            peerSignalingReachable = true,
            mediaRouteConnected = true,
            authorityReachable = false
        )
        val ready = projectRecoveryCapabilitySignature(
            readySnapshot,
            initiatesReattach = true,
            controlPlaneStarted = false
        )
        decisionLogs.clear()
        reattachCalls = 0

        controller.onRecoveryReachabilityChanged(
            sessionId = "sess-1",
            channelId = "CH-1",
            remoteModuleId = "M02",
            snapshot = readySnapshot,
            signature = ready,
            capabilityBefore = before,
            trigger = RecoveryReevaluateTrigger.PEER_DISCOVERED
        )
        assertTrue(decisionLogs.any { it.contains("RECOVERY_REEVALUATE") && it.contains("PEER_DISCOVERED") })
        assertEquals(1, reattachCalls)
        assertTrue(decisionLogs.any { it.contains("decision=DISPATCH_REATTACH") && it.contains("edge=M02") })
    }

    @Test
    fun gC3_1_peerDiscoveredSupersedeFromFailed_assignsOwner() {
        // C-3.1: PEER_DISCOVERED supersede from FAILED must not leave attempt without owner.
        var dispatchRound = 0
        controller = buildController(
            onRequestReattach = { _, _, _ ->
                reattachCalls++
                dispatchRound++
                if (dispatchRound == 1) {
                    ReattachDispatchOutcome.SEND_FAILED
                } else {
                    ReattachDispatchOutcome.DEFERRED
                }
            }
        )
        controller.onIceStateChanged(
            sessionId = "sess-1",
            channelId = "CH-1",
            remoteModuleId = "M02",
            iceState = "FAILED",
            eligibility = eligible(),
            initiatesReattach = true
        )
        Thread.sleep(350)
        assertTrue(controller.factsForSession("sess-1").anyFailedMediaRecovery)
        val failedAttempt = decisionLogs
            .last {
                (it.contains("FAILED_MEDIA_RECOVERY") || it.contains("EXPLICIT_RECOVERY_ABORT")) &&
                    it.contains("remote=M02")
            }
            .substringAfter("attempt=")
            .substringBefore(' ')
            .toLong()
        decisionLogs.clear()

        val snapshot = EdgeReachabilitySnapshot(
            linkReady = true,
            peerDiscovered = true,
            peerSignalingReachable = true,
            mediaRouteConnected = false,
            authorityReachable = true
        )
        val signature = projectRecoveryCapabilitySignature(
            snapshot,
            initiatesReattach = true,
            controlPlaneStarted = false
        )
        controller.onRecoveryReachabilityChanged(
            sessionId = "sess-1",
            channelId = "CH-1",
            remoteModuleId = "M02",
            snapshot = snapshot,
            signature = signature,
            capabilityBefore = signature,
            trigger = RecoveryReevaluateTrigger.PEER_DISCOVERED
        )
        assertTrue(decisionLogs.any { it.contains("decision=SUPERSEDED") && it.contains("edge=M02") })
        assertTrue(
            decisionLogs.any {
                it.contains("RECOVERY_EDGE_STARTED") &&
                    it.contains("pathway=SUPERSEDE") &&
                    it.contains("remote=M02")
            }
        )
        val newAttempt = decisionLogs
            .last { it.contains("decision=SUPERSEDED") && it.contains("edge=M02") }
            .substringAfter("attempt=")
            .substringBefore(' ')
            .toLong()
        assertTrue(newAttempt > failedAttempt)
        assertTrue(
            decisionLogs.any {
                it.contains("RECOVERY_MEDIA_OWNER_ASSIGNED") &&
                    it.contains("owner=PARTICIPANT_REATTACH") &&
                    it.contains("attempt=$newAttempt")
            }
        )
        assertFalse(controller.factsForSession("sess-1").anyFailedMediaRecovery)

        Thread.sleep(350)
        assertFalse(
            decisionLogs.any {
                it.contains("NO_MEDIA_ACTION_OWNER") && it.contains("attempt=$newAttempt")
            }
        )
        assertFalse(
            decisionLogs.any {
                it.contains("OWNER_BLOCKED") && it.contains("attempt=$newAttempt")
            }
        )
        assertTrue(controller.isEdgeRecovering("sess-1", "M02"))
    }

    @Test
    fun reattachAccepted_afterFailedResidency_supersedesAndStartsNewAttempt() {
        // Soak fddec479: FAILED then ACCEPTED must not keep attempt=N.
        controller = buildController(
            attemptBudgetMs = 120L,
            onIceRestart = { _, _ ->
                iceRestartCalls++
                iceRestartCalls > 1
            },
            isIceConnected = { _, _ -> false }
        )
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
            .last {
                it.contains("FAILED_MEDIA_RECOVERY") &&
                    it.contains("remote=M02") &&
                    it.contains("ice_restart_failed")
            }
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
        assertEquals(2, iceRestartCalls)
        assertTrue(controller.isEdgeRecovering("sess-1", "M02"))
        assertFalse(controller.factsForSession("sess-1").anyFailedMediaRecovery)
    }

    @Test
    fun p0_5_iceFailed_doesNotCreateNewAttempt_whenReattachRequested() {
        controller.onIceStateChanged(
            sessionId = "sess-1",
            channelId = "CH-1",
            remoteModuleId = "M02",
            iceState = "DISCONNECTED",
            eligibility = eligible(),
            initiatesReattach = true
        )
        Thread.sleep(80)
        val attemptId = controller.attemptLineageObservation("sess-1", "M02")!!.attemptId
        assertEquals(EdgeRecoveryPhase.REATTACH_REQUESTED, controller.attemptLineageObservation("sess-1", "M02")!!.phase)
        decisionLogs.clear()

        controller.onIceStateChanged(
            sessionId = "sess-1",
            channelId = "CH-1",
            remoteModuleId = "M02",
            iceState = "FAILED",
            eligibility = eligible(),
            initiatesReattach = true
        )

        assertEquals(attemptId, controller.attemptLineageObservation("sess-1", "M02")!!.attemptId)
        assertTrue(decisionLogs.any { it.contains("RECOVERY_EVENT_ATTACHED_EXISTING_ATTEMPT") })
        assertFalse(decisionLogs.any { it.contains("RECOVERY_EDGE_STARTED") })
        assertFalse(decisionLogs.any { it.contains("pathway=BEGIN_RECOVERY") })
    }

    @Test
    fun p0_5_debounceCancelledAfterEarlyDispatch_attemptUnchanged() {
        controller.onIceStateChanged(
            sessionId = "sess-1",
            channelId = "CH-1",
            remoteModuleId = "M02",
            iceState = "DISCONNECTED",
            eligibility = eligible(),
            initiatesReattach = true
        )
        val attemptId = controller.attemptLineageObservation("sess-1", "M02")!!.attemptId
        val snapshot = EdgeReachabilitySnapshot(
            linkReady = true,
            peerDiscovered = true,
            peerSignalingReachable = true,
            mediaRouteConnected = false,
            authorityReachable = true
        )
        val signature = participantReattachCapabilityBeforeRoute()
        controller.onRecoveryReachabilityChanged(
            sessionId = "sess-1",
            channelId = "CH-1",
            remoteModuleId = "M02",
            snapshot = snapshot,
            signature = signature,
            capabilityBefore = signature,
            trigger = RecoveryReevaluateTrigger.REMOTE_MODULE_RECOVERED
        )
        assertTrue(decisionLogs.any { it.contains("RECOVERY_REATTACH_REQUESTED") })
        assertFalse(controller.isControlPlaneStarted("sess-1", "M02"))
        decisionLogs.clear()

        Thread.sleep(80)

        assertEquals(attemptId, controller.attemptLineageObservation("sess-1", "M02")!!.attemptId)
        assertFalse(decisionLogs.any { it.contains("RECOVERY_EDGE_STARTED") })
        assertFalse(decisionLogs.any { it.contains("pathway=BEGIN_RECOVERY") })
        assertFalse(decisionLogs.any { it.contains("RECOVERY_ATTEMPT_REUSED") })
    }

    @Test
    fun p0_5_explicitSupersede_stillCreatesNewAttempt() {
        controller.onIceStateChanged(
            sessionId = "sess-1",
            channelId = "CH-1",
            remoteModuleId = "M02",
            iceState = "DISCONNECTED",
            eligibility = eligible(),
            initiatesReattach = true
        )
        Thread.sleep(80)
        val attempt1 = controller.attemptLineageObservation("sess-1", "M02")!!.attemptId
        assertEquals(EdgeRecoveryPhase.REATTACH_REQUESTED, controller.attemptLineageObservation("sess-1", "M02")!!.phase)

        controller.onRecoveryReattachAccepted(
            "sess-1",
            "M02",
            RecoveryReason.NETWORK_RECOVERY,
            RecoverySource.ICE_MONITOR
        )

        val attempt2 = controller.attemptLineageObservation("sess-1", "M02")!!.attemptId
        assertTrue(attempt2 > attempt1)
        assertTrue(decisionLogs.any { it.contains("RECOVERY_ATTEMPT_SUPERSEDED") })
        assertTrue(decisionLogs.any { it.contains("decision=SUPERSEDED") })
    }

    @Test
    fun p1_recoveredDisconnect_opensNewObligationEpisode() {
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
        controller.onIceConnected("sess-1", "M01")
        val gen1 = controller.obligationGeneration("sess-1", "M01")!!
        val attempt1 = controller.attemptLineageObservation("sess-1", "M01")!!.attemptId
        assertEquals(ObligationCloseReason.RECOVERED, controller.obligationCloseReason("sess-1", "M01"))
        decisionLogs.clear()

        controller.onIceStateChanged(
            sessionId = "sess-1",
            channelId = "CH-1",
            remoteModuleId = "M01",
            iceState = "DISCONNECTED",
            eligibility = eligible(),
            initiatesReattach = true
        )

        assertTrue(decisionLogs.any { it.contains("RECOVERY_OBLIGATION_OPENED") })
        val lineage = controller.attemptLineageObservation("sess-1", "M01")!!
        assertTrue(lineage.obligationGeneration > gen1)
        assertTrue(lineage.attemptId > attempt1)
        assertTrue(lineage.obligationOpen)
        assertEquals(EdgeRecoveryPhase.DISCONNECTED_DEBOUNCING, lineage.phase)
    }

    @Test
    fun p1_activeRecovery_keepsSameObligationGeneration() {
        controller.onIceStateChanged(
            sessionId = "sess-1",
            channelId = "CH-1",
            remoteModuleId = "M02",
            iceState = "DISCONNECTED",
            eligibility = eligible(),
            initiatesReattach = true
        )
        Thread.sleep(80)
        val gen = controller.obligationGeneration("sess-1", "M02")!!
        val attempt = controller.attemptLineageObservation("sess-1", "M02")!!.attemptId
        decisionLogs.clear()

        controller.onIceStateChanged(
            sessionId = "sess-1",
            channelId = "CH-1",
            remoteModuleId = "M02",
            iceState = "FAILED",
            eligibility = eligible(),
            initiatesReattach = true
        )

        assertEquals(gen, controller.obligationGeneration("sess-1", "M02"))
        assertEquals(attempt, controller.attemptLineageObservation("sess-1", "M02")!!.attemptId)
        assertTrue(decisionLogs.any { it.contains("RECOVERY_EVENT_ATTACHED_EXISTING_ATTEMPT") })
        assertFalse(decisionLogs.any { it.contains("RECOVERY_OBLIGATION_OPENED") })
    }

    @Test
    fun p1_watchdog_doesNotTimeoutPriorObligationGeneration() {
        controller = buildController(
            attemptBudgetMs = 120L,
            debounceMs = 20L
        )
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
        val gen1 = controller.obligationGeneration("sess-1", "M01")!!
        decisionLogs.clear()

        controller.onIceStateChanged(
            sessionId = "sess-1",
            channelId = "CH-1",
            remoteModuleId = "M01",
            iceState = "DISCONNECTED",
            eligibility = eligible(),
            initiatesReattach = true
        )
        val gen2 = controller.obligationGeneration("sess-1", "M01")!!
        assertTrue(gen2 > gen1)
        Thread.sleep(80)
        assertTrue(decisionLogs.any { it.contains("RECOVERY_WATCHDOG_STARTED") && it.contains("obligationGen=$gen2") })
        decisionLogs.clear()

        Thread.sleep(200)

        assertFalse(decisionLogs.any { it.contains("RECOVERY_ATTEMPT_TIMEOUT") && it.contains("obligationGen=$gen1") })
        assertTrue(controller.attemptLineageObservation("sess-1", "M01")!!.obligationGeneration == gen2)
    }

    @Test
    fun reattach_transportSent_mediaAlreadyLive_crossesBoundaryAndRecovers() {
        // 4.3-D: REATTACH_THEN_ICE_RESTART + E2 (sent + peer reachable + media live)
        // MUST reuse CONTROL_PLANE_BOUNDARY → ICE_RESTARTING → existing completion (H1/P2).
        controller = buildController(
            attemptBudgetMs = 500L,
            isIceConnected = { _, _ -> true }
        )
        controller.onIceStateChanged(
            sessionId = "sess-1",
            channelId = "CH-1",
            remoteModuleId = "M02",
            iceState = "DISCONNECTED",
            eligibility = eligible(),
            initiatesReattach = true
        )
        Thread.sleep(80)
        assertTrue(
            decisionLogs.any {
                it.contains("RECOVERY_REATTACH_SENT") && it.contains("deliveryState=TRANSPORT_SENT")
            }
        )
        assertFalse(controller.isControlPlaneStarted("sess-1", "M02"))
        assertEquals(
            EdgeRecoveryPhase.REATTACH_REQUESTED,
            controller.attemptLineageObservation("sess-1", "M02")!!.phase
        )

        // Media restores without REATTACH_ACCEPTED — missing boundary was the soak gap.
        controller.onIceConnected("sess-1", "M02")

        assertTrue(
            decisionLogs.any {
                it.contains("RECOVERY_CONTROL_PLANE_BOUNDARY") &&
                    it.contains("reason=REATTACH_MEDIA_ALREADY_LIVE")
            }
        )
        assertTrue(decisionLogs.any { it.contains("RECOVERY_EDGE_RECOVERED") })
        assertEquals(
            ObligationCloseReason.RECOVERED,
            controller.obligationCloseReason("sess-1", "M02")
        )
        assertFalse(controller.isEdgeRecovering("sess-1", "M02"))
        assertFalse(controller.edgeObligationOpen("sess-1", "M02"))
        assertFalse(
            decisionLogs.any {
                it.contains("decision=WAIT_FOR_CONTROL_PLANE") &&
                    it.contains("trigger=ICE_RESTORED")
            }
        )
    }

    @Test
    fun reattach_transportSent_mediaRestored_peerUnreachable_keepsWaiting() {
        controller = buildController(
            attemptBudgetMs = 500L,
            isIceConnected = { _, _ -> true }
        )
        controller.onIceStateChanged(
            sessionId = "sess-1",
            channelId = "CH-1",
            remoteModuleId = "M02",
            iceState = "DISCONNECTED",
            eligibility = eligible(),
            initiatesReattach = true
        )
        Thread.sleep(80)
        assertTrue(
            decisionLogs.any {
                it.contains("RECOVERY_REATTACH_SENT") && it.contains("deliveryState=TRANSPORT_SENT")
            }
        )
        harnessReachability = EdgeReachabilitySnapshot(
            linkReady = true,
            peerDiscovered = true,
            peerSignalingReachable = false,
            mediaRouteConnected = true,
            authorityReachable = true
        )

        controller.onIceConnected("sess-1", "M02")

        assertTrue(
            decisionLogs.any {
                it.contains("decision=WAIT_FOR_CONTROL_PLANE") &&
                    it.contains("trigger=ICE_RESTORED")
            }
        )
        assertFalse(
            decisionLogs.any {
                it.contains("RECOVERY_CONTROL_PLANE_BOUNDARY") &&
                    it.contains("reason=REATTACH_MEDIA_ALREADY_LIVE")
            }
        )
        assertFalse(decisionLogs.any { it.contains("RECOVERY_EDGE_RECOVERED") })
        assertTrue(controller.edgeObligationOpen("sess-1", "M02"))
        assertFalse(controller.isControlPlaneStarted("sess-1", "M02"))
    }
    @Test
    fun reattach_sent_without_receipt_doesNotStartControlPlane() {
        controller.onIceStateChanged(
            sessionId = "sess-1",
            channelId = "CH-1",
            remoteModuleId = "M02",
            iceState = "DISCONNECTED",
            eligibility = eligible(),
            initiatesReattach = true
        )
        Thread.sleep(80)
        assertTrue(
            decisionLogs.any {
                it.contains("RECOVERY_REATTACH_SENT") && it.contains("deliveryState=TRANSPORT_SENT")
            }
        )
        assertFalse(controller.isControlPlaneStarted("sess-1", "M02"))
        assertEquals(
            EdgeRecoveryPhase.REATTACH_REQUESTED,
            controller.attemptLineageObservation("sess-1", "M02")!!.phase
        )
    }

    @Test
    fun stale_obligationGeneration_rejectsInboundReattach() {
        controller = buildController(isIceConnected = { _, _ -> true })
        controller.onIceStateChanged(
            sessionId = "sess-1",
            channelId = "CH-1",
            remoteModuleId = "M01",
            iceState = "DISCONNECTED",
            eligibility = eligible(),
            initiatesReattach = false
        )
        Thread.sleep(80)
        controller.onRecoveryReattachAccepted(
            "sess-1",
            "M01",
            RecoveryReason.NETWORK_RECOVERY,
            RecoverySource.ICE_MONITOR
        )
        controller.onIceConnected("sess-1", "M01")
        assertFalse(controller.edgeObligationOpen("sess-1", "M01"))

        controller.onIceStateChanged(
            sessionId = "sess-1",
            channelId = "CH-1",
            remoteModuleId = "M01",
            iceState = "DISCONNECTED",
            eligibility = eligible(),
            initiatesReattach = false
        )
        Thread.sleep(80)
        val currentGen = controller.obligationGeneration("sess-1", "M01")!!
        assertTrue(currentGen >= 2L)

        val verdict = controller.evaluateInboundReattachLineage(
            sessionId = "sess-1",
            remoteModuleId = "M01",
            senderAttemptId = 1L,
            senderObligationGeneration = currentGen - 1L
        )
        assertEquals(InboundReattachLineageVerdict.STALE_OBLIGATION_GENERATION, verdict)

        val inbound = controller.onRecoveryReattachInboundReceived(
            sessionId = "sess-1",
            channelId = "CH-1",
            remoteModuleId = "M01",
            senderAttemptId = 1L,
            senderObligationGeneration = currentGen - 1L,
            nonce = "stale-nonce"
        )
        assertEquals(InboundReattachLineageVerdict.STALE_OBLIGATION_GENERATION, inbound)
        assertTrue(decisionLogs.any { it.contains("RECOVERY_REATTACH_INBOUND_REJECTED") })
    }

    @Test
    fun ownerBlockedInboundProducesDeferredDecision() {
        controller.onIceStateChanged(
            sessionId = "sess-1",
            channelId = "CH-1",
            remoteModuleId = "M01",
            iceState = "DISCONNECTED",
            eligibility = eligible(),
            initiatesReattach = false
        )
        Thread.sleep(80)
        val attemptId = controller.attemptLineageObservation("sess-1", "M01")!!.attemptId
        val obligationGen = controller.obligationGeneration("sess-1", "M01")!!

        val received = controller.onRecoveryReattachInboundReceived(
            sessionId = "sess-1",
            channelId = "CH-1",
            remoteModuleId = "M01",
            senderAttemptId = attemptId,
            senderObligationGeneration = obligationGen,
            nonce = "inbound-nonce"
        )
        assertEquals(InboundReattachLineageVerdict.ACCEPT, received)
        assertTrue(decisionLogs.any { it.contains("deliveryState=RECEIVED") })

        controller.onRecoveryReattachInboundDeferred(
            sessionId = "sess-1",
            channelId = "CH-1",
            remoteModuleId = "M01",
            reason = DeferredReason.MEDIA_NOT_READY,
            trigger = "INBOUND_REATTACH"
        )
        assertTrue(decisionLogs.any { it.contains("RECOVERY_REATTACH_INBOUND_DEFERRED") })
        assertTrue(decisionLogs.any { it.contains("deferredReason=MEDIA_NOT_READY") })
    }

    @Test
    fun reattach_receipt_without_accept_doesNotStartControlPlane() {
        controller = buildController(
            onRequestReattach = { sessionId, _, remoteModuleId ->
                reattachCalls++
                controller.registerReattachTransportNonce(sessionId, remoteModuleId, "nonce-42")
                ReattachDispatchOutcome.SENT
            }
        )
        controller.onIceStateChanged(
            sessionId = "sess-1",
            channelId = "CH-1",
            remoteModuleId = "M02",
            iceState = "DISCONNECTED",
            eligibility = eligible(),
            initiatesReattach = true
        )
        Thread.sleep(80)
        val attemptId = controller.attemptLineageObservation("sess-1", "M02")!!.attemptId
        val obligationGen = controller.obligationGeneration("sess-1", "M02")!!
        assertTrue(
            controller.onRecoveryReattachReceipt(
                sessionId = "sess-1",
                remoteModuleId = "M02",
                nonce = "nonce-42",
                attemptId = attemptId,
                obligationGeneration = obligationGen
            )
        )
        assertTrue(decisionLogs.any { it.contains("RECOVERY_REATTACH_RECEIPT") })
        assertFalse(controller.isControlPlaneStarted("sess-1", "M02"))
    }

    // --- R28-L (ADR-0022 INV-REC-005/007/008) — recovery-domain completion gates ---

    @Test
    fun r28l_invRec005_iceRestoredWithoutControlPlane_mustNotEdgeRecover() {
        controller = buildController(attemptBudgetMs = 5_000L)
        controller.onIceStateChanged(
            sessionId = "sess-1",
            channelId = "CH-1",
            remoteModuleId = "M02",
            iceState = "DISCONNECTED",
            eligibility = eligible(),
            initiatesReattach = true
        )
        nowMs = 60L
        Thread.sleep(150)
        assertTrue(controller.isEdgeRecovering("sess-1", "M02"))
        assertFalse(controller.isControlPlaneStarted("sess-1", "M02"))

        val logMark = decisionLogs.size
        controller.onIceConnected("sess-1", "M02")
        val afterIceConnected = decisionLogs.drop(logMark)

        assertTrue(
            afterIceConnected.any {
                it.contains("RECOVERY_REEVALUATE") &&
                    it.contains("trigger=${RecoveryReevaluateTrigger.ICE_RESTORED}") &&
                    it.contains("mediaRestored=true") &&
                    it.contains("controlPlaneStarted=false")
            }
        )
        assertFalse(afterIceConnected.any { it.contains("RECOVERY_EDGE_RECOVERED") })
        assertTrue(controller.edgeObligationOpen("sess-1", "M02"))
        assertFalse(controller.edgeObligationClosed("sess-1", "M02"))
        assertEquals(null, controller.obligationCloseReason("sess-1", "M02"))
        assertTrue(controller.isEdgeRecovering("sess-1", "M02"))
    }

    @Test
    fun r28l_invRec007_validObligationClosedReject_triggersReevaluateRequired() {
        controller.onIceStateChanged(
            sessionId = "sess-1",
            channelId = "CH-1",
            remoteModuleId = "M02",
            iceState = "DISCONNECTED",
            eligibility = eligible(),
            initiatesReattach = true
        )
        nowMs = 60L
        Thread.sleep(80)
        val lineage = controller.attemptLineageObservation("sess-1", "M02")!!
        assertEquals(EdgeRecoveryPhase.REATTACH_REQUESTED, lineage.phase)

        val logMark = decisionLogs.size
        val handled = controller.onRecoveryReattachOutboundRejected(
            sessionId = "sess-1",
            remoteModuleId = "M02",
            rejectedAttemptId = lineage.attemptId,
            rejectedObligationGeneration = controller.obligationGeneration("sess-1", "M02")!!,
            reason = OutboundReattachRejectReason.OBLIGATION_CLOSED
        )
        val afterReject = decisionLogs.drop(logMark)

        assertTrue(handled)
        assertTrue(
            afterReject.any {
                it.contains("RECOVERY_REATTACH_OUTBOUND_REJECTED") &&
                    it.contains("reason=OBLIGATION_CLOSED")
            }
        )
        assertTrue(afterReject.any { it.contains("RECOVERY_REEVALUATE_REQUIRED") })
        assertFalse(afterReject.any { it.contains("RECOVERY_EDGE_RECOVERED") })
        assertFalse(controller.edgeObligationClosed("sess-1", "M02"))
        assertEquals(null, controller.obligationCloseReason("sess-1", "M02"))
        assertTrue(controller.edgeObligationOpen("sess-1", "M02"))
        assertTrue(controller.hasPendingCompletionDecision("sess-1", "M02"))
        assertEquals(lineage.attemptId, controller.attemptLineageObservation("sess-1", "M02")!!.attemptId)
        assertEquals(
            lineage.obligationGeneration,
            controller.obligationGeneration("sess-1", "M02")
        )
    }

    @Test
    fun r28l_invRec007_staleObligationClosedReject_isIgnoredWithoutSideEffects() {
        controller.onIceStateChanged(
            sessionId = "sess-1",
            channelId = "CH-1",
            remoteModuleId = "M02",
            iceState = "DISCONNECTED",
            eligibility = eligible(),
            initiatesReattach = true
        )
        nowMs = 60L
        Thread.sleep(80)
        controller.onRecoveryReattachAccepted(
            "sess-1",
            "M02",
            RecoveryReason.HOST_REATTACH,
            RecoverySource.ICE_MONITOR
        )
        controller.onIceConnected("sess-1", "M02")
        assertEquals(ObligationCloseReason.RECOVERED, controller.obligationCloseReason("sess-1", "M02"))
        decisionLogs.clear()

        controller.onIceStateChanged(
            sessionId = "sess-1",
            channelId = "CH-1",
            remoteModuleId = "M02",
            iceState = "DISCONNECTED",
            eligibility = eligible(),
            initiatesReattach = true
        )
        nowMs += 60L
        Thread.sleep(80)
        val current = controller.attemptLineageObservation("sess-1", "M02")!!
        val currentGen = controller.obligationGeneration("sess-1", "M02")!!
        assertTrue(current.attemptId > 1L)
        assertTrue(currentGen >= 2L)

        val logMark = decisionLogs.size
        val handled = controller.onRecoveryReattachOutboundRejected(
            sessionId = "sess-1",
            remoteModuleId = "M02",
            rejectedAttemptId = current.attemptId - 1L,
            rejectedObligationGeneration = currentGen - 1L,
            reason = OutboundReattachRejectReason.OBLIGATION_CLOSED
        )
        val afterReject = decisionLogs.drop(logMark)

        assertTrue(handled)
        assertTrue(afterReject.any { it.contains("STALE_REATTACH_REJECT_IGNORED") })
        assertFalse(afterReject.any { it.contains("RECOVERY_REEVALUATE_REQUIRED") })
        assertFalse(afterReject.any { it.contains("RECOVERY_EDGE_RECOVERED") })
        assertEquals(current.attemptId, controller.attemptLineageObservation("sess-1", "M02")!!.attemptId)
        assertEquals(currentGen, controller.obligationGeneration("sess-1", "M02"))
        assertFalse(controller.hasPendingCompletionDecision("sess-1", "M02"))
    }

    @Test
    fun r28l_appendixD_obligationClosedPayload_routesToReevaluateRequired() {
        controller.onIceStateChanged(
            sessionId = "sess-1",
            channelId = "CH-1",
            remoteModuleId = "M02",
            iceState = "DISCONNECTED",
            eligibility = eligible(),
            initiatesReattach = true
        )
        nowMs = 60L
        Thread.sleep(80)

        val logMark = decisionLogs.size
        val handled = controller.onConferenceRecoveryReattachOutboundReject(
            sessionId = "sess-1",
            remoteModuleId = "M02",
            reasonPayload = "OBLIGATION_CLOSED"
        )
        val afterReject = decisionLogs.drop(logMark)

        assertTrue(handled)
        assertTrue(afterReject.any { it.contains("RECOVERY_REEVALUATE_REQUIRED") })
        assertFalse(afterReject.any { it.contains("RECOVERY_EDGE_RECOVERED") })
        assertTrue(controller.hasPendingCompletionDecision("sess-1", "M02"))
    }

    @Test
    fun r28l_invRec008_rejoinSessionBoundary_clearsPriorLineage() {
        val oldSessionId = "sess-old"
        val newSessionId = "sess-new"
        controller.onIceStateChanged(
            sessionId = oldSessionId,
            channelId = "CH-1",
            remoteModuleId = "M02",
            iceState = "DISCONNECTED",
            eligibility = eligible(),
            initiatesReattach = true
        )
        nowMs = 60L
        Thread.sleep(80)
        val oldLineage = controller.attemptLineageObservation(oldSessionId, "M02")
        assertNotNull(oldLineage)
        val oldAttemptId = oldLineage!!.attemptId
        val oldObligationGen = controller.obligationGeneration(oldSessionId, "M02")!!
        assertTrue(controller.isEdgeRecovering(oldSessionId, "M02"))
        assertTrue(controller.factsForSession(oldSessionId).recoveringRemoteModuleIds.contains("M02"))

        controller.cancelSession(oldSessionId, "local_leave")

        assertNull(controller.attemptLineageObservation(oldSessionId, "M02"))
        assertNull(controller.obligationGeneration(oldSessionId, "M02"))
        assertFalse(controller.isEdgeRecovering(oldSessionId, "M02"))
        assertFalse(controller.factsForSession(oldSessionId).anyRecovering)

        controller.onIceStateChanged(
            sessionId = newSessionId,
            channelId = "CH-1",
            remoteModuleId = "M02",
            iceState = "DISCONNECTED",
            eligibility = eligible(),
            initiatesReattach = true
        )
        nowMs += 60L
        Thread.sleep(80)

        val newLineage = controller.attemptLineageObservation(newSessionId, "M02")
        assertNotNull(newLineage)
        assertTrue(newLineage!!.obligationOpen)
        assertEquals(1L, controller.obligationGeneration(newSessionId, "M02"))
        assertTrue(newLineage.attemptId > oldAttemptId)
        assertFalse(controller.factsForSession(oldSessionId).recoveringRemoteModuleIds.contains("M02"))
        assertTrue(controller.factsForSession(newSessionId).recoveringRemoteModuleIds.contains("M02"))
        assertEquals(oldObligationGen, 1L)
    }

    @Test
    fun r28m_failedMediaRecovery_iceConnected_supersedesViaContinuation() {
        controller = buildController(
            attemptBudgetMs = 120L,
            isIceConnected = { _, _ -> true },
            onRequestReattach = { _, _, _ ->
                reattachCalls++
                ReattachDispatchOutcome.SENT
            }
        )
        controller.onIceStateChanged(
            sessionId = "sess-1",
            channelId = "CH-1",
            remoteModuleId = "M01",
            iceState = "FAILED",
            eligibility = eligible(),
            initiatesReattach = true
        )
        Thread.sleep(200)
        assertTrue(controller.factsForSession("sess-1").anyFailedMediaRecovery)
        val failedAttempt = decisionLogs
            .last { it.contains("FAILED_MEDIA_RECOVERY") && it.contains("remote=M01") }
            .substringAfter("attempt=")
            .substringBefore(' ')
            .toLong()

        controller.onIceConnected("sess-1", "M01")
        assertTrue(decisionLogs.any { it.contains("decision=SUPERSEDED") })
        val nextAttempt = decisionLogs
            .last { it.contains("decision=SUPERSEDED") }
            .substringAfter("attempt=")
            .substringBefore(' ')
            .toLong()
        assertTrue(nextAttempt > failedAttempt)
        assertFalse(controller.factsForSession("sess-1").anyFailedMediaRecovery)
    }
}
