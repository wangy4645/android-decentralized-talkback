package com.talkback.core.session

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.Executors

/**
 * ADR-0042 P0 — Commit 1 red reproduction (reattach transport result consumer).
 *
 * Captures the baseline violation: reattach [ReattachDispatchOutcome.SEND_FAILED] is
 * wrongly escalated into FAILED_MEDIA residency (X2-adjacent), violating INV-T2
 * (send failure is terminal for the transmission instance, not the recovery obligation).
 *
 * Expected on unfixed main: RED.
 * Expected after Correct-in-place consumer fix: GREEN.
 */
class Adr0042P0ReattachSendFailedReactionTest {
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private var nowMs = 0L
    private var reattachCalls = 0
    private val decisionLogs = mutableListOf<String>()
    private lateinit var controller: ConferenceEdgeRecoveryController

    @Before
    fun setUp() {
        nowMs = 0L
        reattachCalls = 0
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
                ReattachDispatchOutcome.SEND_FAILED
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

    @Test
    fun sendFailed_mustNotEnterFailedMediaResidency_obligationRemainsOpen() {
        controller.onIceStateChanged(
            sessionId = "sess-adr42",
            channelId = "CH-1",
            remoteModuleId = "M02",
            iceState = "FAILED",
            eligibility = eligible(),
            initiatesReattach = true
        )
        nowMs = 60L
        Thread.sleep(80)

        assertEquals("reattach dispatch must have been attempted", 1, reattachCalls)

        // ADR-0042 INV-T2 / P0 inseparability: transport SEND_FAILED ≠ media recovery failure.
        assertFalse(
            "SEND_FAILED must not enter FAILED_MEDIA_RESIDENCY (baseline wrongly does)",
            controller.factsForSession("sess-adr42").anyFailedMediaRecovery
        )
        assertTrue(
            "obligation must remain OPEN after transport SEND_FAILED",
            controller.edgeObligationOpen("sess-adr42", "M02")
        )
        assertFalse(
            "must not log FAILED_MEDIA_RECOVERY reason=reattach_send_failed",
            decisionLogs.any {
                it.contains("FAILED_MEDIA_RECOVERY") && it.contains("reattach_send_failed")
            }
        )
        assertTrue(
            "edge must still be recovering (not terminalized by transport fail)",
            controller.factsForSession("sess-adr42").anyRecovering
        )
    }
}
