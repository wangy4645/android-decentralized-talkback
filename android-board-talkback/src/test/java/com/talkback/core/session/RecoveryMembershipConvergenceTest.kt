package com.talkback.core.session

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.Executors

class RecoveryMembershipConvergenceTest {
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private var nowMs = 0L
    private var membershipInFlight = false
    private val logs = mutableListOf<String>()
    private lateinit var controller: ConferenceEdgeRecoveryController

    private val sessionId = "sess-membership-convergence"
    private val channelId = "CH-01"
    private val remoteModuleId = "M02"

    @Before
    fun setUp() {
        nowMs = 0L
        membershipInFlight = false
        logs.clear()
        controller = buildController()
    }

    @After
    fun tearDown() {
        controller.clearAll()
        scheduler.shutdownNow()
    }

    private fun membershipDivergedProbe(): MembershipEpochConvergenceProbe =
        object : MembershipEpochConvergenceProbe {
            override fun probe(
                record: EdgeRecoveryRecord,
                channelId: String,
                conferenceSessionId: String
            ): MembershipEpochProbeResult =
                MembershipEpochProbeResult.Checked(
                    authorityId = "M01",
                    expectedEpoch = 4L,
                    observedEpoch = 1L,
                    converged = false
                )
        }

    private fun buildController(attemptBudgetMs: Long = 200L) =
        ConferenceEdgeRecoveryController(
            debounceMs = 20L,
            iceRestartTimeoutMs = 100L,
            attemptBudgetMs = attemptBudgetMs,
            observationWindowMs = 5_000L,
            clock = { nowMs },
            scheduler = scheduler,
            onLog = { logs.add(it) },
            onRequestReattach = { _, _, _ -> ReattachDispatchOutcome.SENT },
            onIceRestart = { _, _ -> true },
            isIceConnected = { _, _ -> true },
            membershipEpochProbe = membershipDivergedProbe(),
            isMembershipConvergenceInFlight = { _, _ -> membershipInFlight }
        )

    private fun eligible() = EdgeRecoveryEligibility(
        lifecycleEstablished = true,
        localJoined = true,
        remoteJoined = true,
        conferenceTerminated = false
    )

    private fun startControlPlaneRecoveryWithMediaRestored() {
        controller.onIceStateChanged(
            sessionId = sessionId,
            channelId = channelId,
            remoteModuleId = remoteModuleId,
            iceState = "FAILED",
            eligibility = eligible(),
            initiatesReattach = false
        )
        Thread.sleep(50)
        controller.onIceConnected(sessionId, remoteModuleId)
    }

    @Test
    fun watchdogMembershipResyncInFlight_defersAndEmitsBlockedControl_notFailedMedia() {
        membershipInFlight = true
        startControlPlaneRecoveryWithMediaRestored()
        logs.clear()
        Thread.sleep(300)
        assertTrue(
            logs.any {
                it.contains("RECOVERY_COMPLETION_BLOCKED_BY_CONTROL") &&
                    it.contains("MEMBERSHIP_CONVERGENCE_PENDING")
            }
        )
        assertTrue(
            logs.any {
                it.contains("RECOVERY_WATCHDOG_DEFERRED") &&
                    it.contains("MEMBERSHIP_CONVERGENCE_PENDING")
            }
        )
        assertFalse(logs.any { it.contains("FAILED_MEDIA_RECOVERY") })
        assertTrue(controller.isEdgeRecovering(sessionId, remoteModuleId))
    }

    @Test
    fun watchdogMembershipDivergedNoResync_logsMembershipConvergenceTimeoutClass() {
        membershipInFlight = false
        startControlPlaneRecoveryWithMediaRestored()
        logs.clear()
        Thread.sleep(300)
        assertTrue(
            logs.any {
                it.contains("FAILED_MEDIA_RECOVERY") &&
                    it.contains("failureClass=MEMBERSHIP_CONVERGENCE_TIMEOUT")
            }
        )
    }

    @Test
    fun watchdogMembershipResyncInFlightThenClears_membershipConvergenceTimeout_notInfiniteDefer() {
        membershipInFlight = true
        startControlPlaneRecoveryWithMediaRestored()
        Thread.sleep(300)
        membershipInFlight = false
        logs.clear()
        Thread.sleep(300)
        assertFalse(
            logs.any {
                it.contains("RECOVERY_WATCHDOG_DEFERRED") &&
                    it.contains("MEMBERSHIP_CONVERGENCE_PENDING")
            }
        )
        assertTrue(
            logs.any {
                it.contains("FAILED_MEDIA_RECOVERY") &&
                    it.contains("failureClass=MEMBERSHIP_CONVERGENCE_TIMEOUT")
            }
        )
    }
}