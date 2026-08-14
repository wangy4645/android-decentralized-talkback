package com.talkback.core.session

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.Executors

/** ADR-0055 Track O — observer watchdog timeout must terminal, not defer on dispatch_gate. */
class Adr0055TrackOWatchdogTimeoutContractTest {
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private var nowMs = 0L
    private val decisionLogs = mutableListOf<String>()
    private lateinit var controller: ConferenceEdgeRecoveryController
    private var canDispatchMediaAction = true

    @Before
    fun setUp() {
        nowMs = 0L
        decisionLogs.clear()
        canDispatchMediaAction = true
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
            canDispatchRecoveryMediaAction = { _, _ -> canDispatchMediaAction },
            membershipEpochProbe = DefaultOpenMembershipAuthoritySentinel
        ).also { controller = it }
    }

    private fun startObserverRecoveryWithLiveWatchdog() {
        controller.onIceStateChanged(
            sessionId = "sess-o",
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
    }

    @Test
    fun trackO_timeoutWithDispatchGateUnavailable_emitsTerminalNotWatchdogDefer() {
        buildController(attemptBudgetMs = 120L)
        startObserverRecoveryWithLiveWatchdog()
        canDispatchMediaAction = false

        Thread.sleep(200)
        assertTrue(
            decisionLogs.any {
                it.contains("RECOVERY_ATTEMPT_TIMEOUT") && it.contains("edge=M02")
            }
        )
        assertFalse(
            decisionLogs.any {
                it.contains("RECOVERY_WATCHDOG_DEFERRED") &&
                    it.contains("CAPABILITY_UNAVAILABLE_AT_FIRE") &&
                    it.contains("edge=M02")
            }
        )
        assertTrue(
            decisionLogs.any {
                it.contains("RECOVERY_ATTEMPT_STATE") &&
                    it.contains("to=ATTEMPT_FAILED") &&
                    it.contains("attemptTerminal=true") &&
                    it.contains("remote=M02")
            }
        )
    }

    @Test
    fun trackO_timeoutWithRouteNotReadyDefer_emitsTerminalNotWatchdogDefer() {
        buildController(attemptBudgetMs = 120L)
        startObserverRecoveryWithLiveWatchdog()
        controller.applyCapabilityDeferForTest("sess-o", "M02", DeferredReason.ROUTE_NOT_READY)
        assertTrue(
            decisionLogs.any {
                it.contains("RECOVERY_MEDIA_ACTION_DEFERRED") &&
                    it.contains("deferredReason=ROUTE_NOT_READY") &&
                    it.contains("remote=M02")
            }
        )

        Thread.sleep(200)
        assertTrue(
            decisionLogs.any {
                it.contains("RECOVERY_ATTEMPT_TIMEOUT") && it.contains("edge=M02")
            }
        )
        assertFalse(
            decisionLogs.any {
                it.contains("RECOVERY_WATCHDOG_DEFERRED") &&
                    it.contains("CAPABILITY_UNAVAILABLE_AT_FIRE") &&
                    it.contains("edge=M02")
            }
        )
        assertTrue(
            decisionLogs.any {
                (it.contains("EXPLICIT_RECOVERY_ABORT") && it.contains("OWNER_BLOCKED")) ||
                    (it.contains("FAILED_MEDIA_RECOVERY") && it.contains("remote=M02"))
            }
        )
        assertTrue(controller.factsForSession("sess-o").anyFailedMediaRecovery)
    }
}
