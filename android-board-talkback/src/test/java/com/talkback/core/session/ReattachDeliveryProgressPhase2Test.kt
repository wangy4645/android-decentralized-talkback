package com.talkback.core.session

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * RRA-005 Phase-2: REATTACH delivery observation is additive to INV-T3.
 *
 * ```
 * SENT → WAITING_REMOTE_EVIDENCE → OBTAINED | EXPIRED
 * ```
 *
 * Does not retry, fail-media, or complete on expire.
 */
class ReattachDeliveryProgressPhase2Test {
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

    /** SEND_FAILED → ProgressWindow → SENT (Phase-1 + Phase-2 arm). */
    private fun sendFailedThenProgressSent(sessionId: String) {
        var round = 0
        nextOutcome = {
            round++
            if (round == 1) ReattachDispatchOutcome.SEND_FAILED else ReattachDispatchOutcome.SENT
        }
        controller.onIceStateChanged(
            sessionId = sessionId,
            channelId = "CH-1",
            remoteModuleId = "M01",
            iceState = "FAILED",
            eligibility = eligible(),
            initiatesReattach = true
        )
        nowMs = 60L
        Thread.sleep(80)
        assertTrue(logs.any { it.contains("RECOVERY_PROGRESS_WINDOW_ARMED") })
        // Nonce for the progress redispath SENT (Phase-2 arm).
        controller.registerReattachTransportNonce(sessionId, "M01", "nonce-$sessionId")
        nowMs = 300L
        Thread.sleep(250)
    }

    @Test
    fun sent_armsDeliveryProgress_and_keepsProgressWindowSatisfiedIndependent() {
        sendFailedThenProgressSent("sess-p2-a")

        assertTrue(
            "Phase-1 SATISFIED must remain",
            logs.any { it.contains("RECOVERY_PROGRESS_WINDOW_SATISFIED") }
        )
        assertTrue(
            "Phase-2 must arm after SENT",
            logs.any { it.contains("REATTACH_DELIVERY_PROGRESS_ARMED") }
        )
        assertTrue(logs.any { it.contains("deliveryState=TRANSPORT_SENT") })
    }

    @Test
    fun receipt_marksEvidenceObtained_withoutAutoRecovered() {
        sendFailedThenProgressSent("sess-p2-b")
        assertTrue(logs.any { it.contains("REATTACH_DELIVERY_PROGRESS_ARMED") })

        val attemptLine = logs.first { it.contains("RECOVERY_REATTACH_SENT") && it.contains("attempt=") }
        val attemptId = Regex("attempt=(\\d+)").find(attemptLine)!!.groupValues[1].toLong()
        val oblGen = Regex("obligationGen=(\\d+)").find(attemptLine)?.groupValues?.get(1)?.toLong()
            ?: 1L

        assertTrue(
            controller.onRecoveryReattachReceipt(
                sessionId = "sess-p2-b",
                remoteModuleId = "M01",
                nonce = "nonce-sess-p2-b",
                attemptId = attemptId,
                obligationGeneration = oblGen
            )
        )
        assertTrue(logs.any { it.contains("REATTACH_DELIVERY_PROGRESS_OBTAINED") })
        assertFalse(
            logs.any {
                it.contains("decision=RECOVERED") && it.contains("trigger=REMOTE_RECEIPT_ACKED")
            }
        )
    }

    @Test
    fun expire_isObservationFact_notFailedMedia() {
        sendFailedThenProgressSent("sess-p2-c")
        assertTrue(logs.any { it.contains("REATTACH_DELIVERY_PROGRESS_ARMED") })
        val beforeExpireFails = logs.count { it.contains("FAILED_MEDIA") || it.contains("enterFailedMedia") }
        val sendsBeforeExpire = reattachCalls

        // Delivery observation budget = iceRestartTimeoutMs (200ms from arm at ~300)
        nowMs = 600L
        Thread.sleep(300)

        assertTrue(logs.any { it.contains("REATTACH_DELIVERY_PROGRESS_EXPIRED") })
        val afterExpireFails = logs.count { it.contains("FAILED_MEDIA") || it.contains("enterFailedMedia") }
        assertEquals(
            "EXPIRED must not escalate FAILED_MEDIA",
            beforeExpireFails,
            afterExpireFails
        )
        // RCA-002: opportunity eligibility may open a new SENT — that is not FAILED_MEDIA.
        assertTrue(
            logs.any { it.contains("REATTACH_DELIVERY_OPPORTUNITY_REACQUISITION_ELIGIBLE") }
        )
        assertTrue(
            "opportunity may reacquire when dispatch gate ready",
            reattachCalls >= sendsBeforeExpire
        )
    }

    @Test
    fun rca002_expiredWithoutReceipt_releasesInFlight_andMayArmNewDelivery() {
        sendFailedThenProgressSent("sess-rca002")
        val armedBefore = logs.count { it.contains("REATTACH_DELIVERY_PROGRESS_ARMED") }
        assertEquals(1, armedBefore)
        val sendsAtArm = reattachCalls

        nowMs = 600L
        Thread.sleep(350)

        assertTrue(logs.any { it.contains("REATTACH_DELIVERY_PROGRESS_EXPIRED") })
        assertTrue(logs.any { it.contains("REATTACH_DELIVERY_OPPORTUNITY_REACQUISITION_ELIGIBLE") })
        assertTrue(logs.any { it.contains("REATTACH_DELIVERY_OPPORTUNITY_REEVALUATE") })
        // New delivery attempt (not Phase-2 "retry because EXPIRED").
        assertTrue(reattachCalls > sendsAtArm)
        assertTrue(
            logs.count { it.contains("REATTACH_DELIVERY_PROGRESS_ARMED") } >= 2
        )
        assertFalse(
            logs.any {
                it.contains("rejectReason=transport_in_flight") &&
                    it.contains("DELIVERY_OPPORTUNITY_REACQUIRED")
            }
        )
    }

    @Test
    fun rca002_expired_whenDispatchGateClosed_doesNotForceSend() {
        sendFailedThenProgressSent("sess-rca002-wait")
        gateOpen.set(false)
        val sendsAtArm = reattachCalls

        nowMs = 600L
        Thread.sleep(350)

        assertTrue(logs.any { it.contains("REATTACH_DELIVERY_PROGRESS_EXPIRED") })
        assertTrue(logs.any { it.contains("REATTACH_DELIVERY_OPPORTUNITY_REACQUISITION_ELIGIBLE") })
        assertTrue(logs.any { it.contains("REATTACH_DELIVERY_OPPORTUNITY_WAITING") })
        assertEquals(
            "EXPIRED must not force send when dispatch gate closed",
            sendsAtArm,
            reattachCalls
        )
    }
}
