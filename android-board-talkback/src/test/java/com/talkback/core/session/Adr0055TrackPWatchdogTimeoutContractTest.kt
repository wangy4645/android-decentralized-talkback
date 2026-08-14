package com.talkback.core.session

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.Executors

/** ADR-0055 Track P — attempt watchdog timeout must terminal, not defer on MEDIA_NOT_READY. */
class Adr0055TrackPWatchdogTimeoutContractTest {
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private var nowMs = 0L
    private val decisionLogs = mutableListOf<String>()
    private lateinit var controller: ConferenceEdgeRecoveryController

    @Before
    fun setUp() {
        nowMs = 0L
        decisionLogs.clear()
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

    private fun buildController(attemptBudgetMs: Long = 120L): ConferenceEdgeRecoveryController {
        return ConferenceEdgeRecoveryController(
            debounceMs = 50L,
            iceRestartTimeoutMs = 200L,
            attemptBudgetMs = attemptBudgetMs,
            observationWindowMs = 10_000L,
            clock = { nowMs },
            scheduler = scheduler,
            onLog = { decisionLogs.add(it) },
            onRequestReattach = { _, _, _ -> ReattachDispatchOutcome.SENT },
            onIceRestart = { _, _ -> true },
            isIceConnected = { _, _ -> false },
            isReceivePathLive = { _, _ -> false },
            canDispatchRecoveryMediaAction = { _, _ -> true },
            membershipEpochProbe = DefaultOpenMembershipAuthoritySentinel
        ).also { controller = it }
    }

    private fun startAuthorityRecoveryWithLiveWatchdog() {
        controller.onIceStateChanged(
            sessionId = "sess-p",
            channelId = "CH-1",
            remoteModuleId = "M02",
            iceState = "FAILED",
            eligibility = eligible(),
            initiatesReattach = false
        )
        assertTrue(
            decisionLogs.any {
                it.contains("RECOVERY_WATCHDOG_STARTED") && it.contains("edge=M02")
            }
        )
        controller.applyMediaNotReadyDeferForTest("sess-p", "M02")
    }

    @Test
    fun trackP_timeoutWithMediaNotReady_emitsTerminalDispositionNotWatchdogDefer() {
        buildController(attemptBudgetMs = 120L)
        startAuthorityRecoveryWithLiveWatchdog()
        assertTrue(
            decisionLogs.any {
                it.contains("RECOVERY_MEDIA_ACTION_DEFERRED") &&
                    it.contains("deferredReason=MEDIA_NOT_READY") &&
                    it.contains("remote=M02")
            }
        )

        Thread.sleep(200)
        assertTrue(
            decisionLogs.any {
                it.contains("RECOVERY_ATTEMPT_TIMEOUT") && it.contains("edge=M02")
            }
        )
        assertTrue(
            decisionLogs.any {
                it.contains("RECOVERY_DECISION") &&
                    it.contains("decision=ATTEMPT_TIMEOUT") &&
                    it.contains("approved=false") &&
                    it.contains("edge=M02")
            }
        )
        assertFalse(
            decisionLogs.any {
                it.contains("RECOVERY_WATCHDOG_DEFERRED") &&
                    it.contains("CAPABILITY_UNAVAILABLE_AT_FIRE") &&
                    it.contains("deferredReason=MEDIA_NOT_READY") &&
                    it.contains("edge=M02")
            }
        )
        assertTrue(
            decisionLogs.any {
                (it.contains("EXPLICIT_RECOVERY_ABORT") && it.contains("OWNER_BLOCKED")) ||
                    (it.contains("FAILED_MEDIA_RECOVERY") && it.contains("remote=M02"))
            }
        )
        assertTrue(controller.factsForSession("sess-p").anyFailedMediaRecovery)
    }

    @Test
    fun trackP_afterTimeoutTerminal_newRecoveryConditionOpensFreshAttempt() {
        buildController(attemptBudgetMs = 120L)
        startAuthorityRecoveryWithLiveWatchdog()
        val attempt1 = controller.attemptLineageObservation("sess-p", "M02")!!.attemptId
        Thread.sleep(200)
        assertTrue(controller.factsForSession("sess-p").anyFailedMediaRecovery)
        assertFalse(controller.isEdgeRecovering("sess-p", "M02"))
        assertTrue(controller.isPostTerminalDispatchEligible("sess-p", "M02"))

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
            controlPlaneStarted = true
        )
        controller.onRecoveryReachabilityChanged(
            sessionId = "sess-p",
            channelId = "CH-1",
            remoteModuleId = "M02",
            snapshot = snapshot,
            signature = signature,
            capabilityBefore = signature,
            trigger = RecoveryReevaluateTrigger.POST_TERMINAL_DISPATCH_CAPABLE
        )
        assertTrue(
            decisionLogs.any {
                it.contains("decision=SUPERSEDED") &&
                    it.contains("trigger=POST_TERMINAL_DISPATCH_CAPABLE") &&
                    it.contains("edge=M02")
            }
        )
        assertTrue(controller.isEdgeRecovering("sess-p", "M02"))
        val attempt2 = controller.attemptLineageObservation("sess-p", "M02")!!.attemptId
        assertTrue("expected fresh attempt after terminal", attempt2 > attempt1)
    }
}
