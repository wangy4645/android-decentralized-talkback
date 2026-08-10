package com.talkback.core.session

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ADR-0042 INV-T3-SCHEDULE — G4 progress oracle (Commit 4).
 *
 * Asserts progress **liveness**, not delivery / WiFi success rate:
 *
 * ```
 * SEND_FAILED
 *   → progress window created
 *   → redispatch opportunity exists when action gate permits
 *   → terminal disposition of the window is explicit (SATISFIED | EXPIRED)
 * ```
 *
 * Does not change completion predicate (ADR-0038) or INV-T1/T2 send truth.
 */
class Adr0042InvT3ScheduleProgressOracleTest {
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private var nowMs = 0L
    private var reattachCalls = 0
    private var nextOutcome: () -> ReattachDispatchOutcome = { ReattachDispatchOutcome.SENT }
    private val gateOpen = AtomicBoolean(true)
    private val logs = mutableListOf<String>()
    private lateinit var controller: ConferenceEdgeRecoveryController

    @Before
    fun setUp() {
        nowMs = 0L
        reattachCalls = 0
        nextOutcome = { ReattachDispatchOutcome.SENT }
        gateOpen.set(true)
        logs.clear()
        controller = ConferenceEdgeRecoveryController(
            debounceMs = 50L,
            iceRestartTimeoutMs = 200L,
            attemptBudgetMs = 2_000L,
            observationWindowMs = 10_000L,
            clock = { nowMs },
            scheduler = scheduler,
            onLog = { logs.add(it) },
            onRequestReattach = { _, _, _ ->
                reattachCalls++
                nextOutcome()
            },
            onIceRestart = { _, _ -> true },
            isIceConnected = { _, _ -> false },
            canDispatchRecoveryMediaAction = { _, _ -> gateOpen.get() },
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

    private fun startOutboundReattachThatFails() {
        var round = 0
        nextOutcome = {
            round++
            if (round == 1) ReattachDispatchOutcome.SEND_FAILED else ReattachDispatchOutcome.SENT
        }
        controller.onIceStateChanged(
            sessionId = "sess-g4",
            channelId = "CH-1",
            remoteModuleId = "M02",
            iceState = "FAILED",
            eligibility = eligible(),
            initiatesReattach = true
        )
        nowMs = 60L
        Thread.sleep(80)
    }

    private fun awaitProgressWindowFire() {
        nowMs = 300L
        Thread.sleep(250)
    }

    @Test
    fun g4_sendFailed_createsProgressWindow_notFailedMedia() {
        startOutboundReattachThatFails()

        assertTrue(
            "G4: progress window must be created after SEND_FAILED",
            logs.any { it.contains("RECOVERY_PROGRESS_WINDOW_ARMED") }
        )
        assertTrue(
            "WAKEUP_ARMED may coexist (capability deferral)",
            logs.any { it.contains("RECOVERY_WAKEUP_ARMED") }
        )
        assertTrue(controller.edgeObligationOpen("sess-g4", "M02"))
        assertFalse(controller.factsForSession("sess-g4").anyFailedMediaRecovery)
    }

    @Test
    fun g4_progressOpportunity_existsWithoutExternalRouteEvent() {
        startOutboundReattachThatFails()
        val callsAfterArm = reattachCalls
        logs.clear()

        awaitProgressWindowFire()

        assertTrue(
            "G4: when gate permits, progress fire must produce redispatch opportunity",
            reattachCalls > callsAfterArm
        )
        assertTrue(logs.any { it.contains("RECOVERY_PROGRESS_WINDOW_FIRED") })
        assertTrue(logs.any { it.contains("RECOVERY_PROGRESS_WINDOW_REEVALUATE") })
        assertTrue(
            "G4: window disposition must be explicit after opportunity",
            logs.any { it.contains("RECOVERY_PROGRESS_WINDOW_SATISFIED") } ||
                logs.any { it.contains("RECOVERY_PROGRESS_WINDOW_EXPIRED") }
        )
        assertFalse(controller.factsForSession("sess-g4").anyFailedMediaRecovery)
    }

    @Test
    fun g4_gateBlocked_progressExpiresWithoutFailedMedia() {
        startOutboundReattachThatFails()
        gateOpen.set(false)
        val callsAfterArm = reattachCalls
        logs.clear()

        awaitProgressWindowFire()

        assertTrue(logs.any { it.contains("RECOVERY_PROGRESS_WINDOW_FIRED") })
        assertTrue(
            "G4: blocked gate → explicit EXPIRED (not delivery failure / FAILED_MEDIA)",
            logs.any { it.contains("RECOVERY_PROGRESS_WINDOW_EXPIRED") }
        )
        assertTrue(
            "no redispatch when action gate blocks",
            reattachCalls == callsAfterArm
        )
        assertFalse(controller.factsForSession("sess-g4").anyFailedMediaRecovery)
        assertTrue(controller.edgeObligationOpen("sess-g4", "M02"))
    }

    @Test
    fun g4_progressDoesNotRequireDeliverySuccess() {
        // SEND_FAILED again on progress redispatch — window may expire; obligation stays open.
        nextOutcome = { ReattachDispatchOutcome.SEND_FAILED }
        controller.onIceStateChanged(
            sessionId = "sess-g4",
            channelId = "CH-1",
            remoteModuleId = "M02",
            iceState = "FAILED",
            eligibility = eligible(),
            initiatesReattach = true
        )
        nowMs = 60L
        Thread.sleep(80)
        assertTrue(logs.any { it.contains("RECOVERY_PROGRESS_WINDOW_ARMED") })

        logs.clear()
        awaitProgressWindowFire()

        assertTrue(logs.any { it.contains("RECOVERY_PROGRESS_WINDOW_FIRED") })
        // Delivery may fail again; INV-T3-SCHEDULE is progress, not success.
        assertFalse(controller.factsForSession("sess-g4").anyFailedMediaRecovery)
        assertTrue(controller.edgeObligationOpen("sess-g4", "M02"))
        assertTrue(
            logs.any { it.contains("RECOVERY_PROGRESS_WINDOW_EXPIRED") } ||
                logs.any { it.contains("RECOVERY_PROGRESS_WINDOW_ARMED") } ||
                logs.any { it.contains("outcome=SEND_FAILED") }
        )
    }
}
