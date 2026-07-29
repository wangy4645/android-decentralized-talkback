package com.talkback.core.session

import com.talkback.core.util.RecoveryDeliveryFact
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.Executors

/**
 * ADR-0035 PR2 UT Cases A/B/C — bounded recovery-offer delivery retry.
 */
class RecoveryOfferDeliveryRetryTest {
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private var nowMs = 0L
    private val deliveryLogs = mutableListOf<String>()
    private val dispatchCalls = mutableListOf<Pair<String, Long>>()
    private lateinit var controller: ConferenceEdgeRecoveryController

    private val sessionId = "sess-1"
    private val remoteModuleId = "M03"
    private val lineageId = "L1"

    @Before
    fun setUp() {
        nowMs = 0L
        deliveryLogs.clear()
        dispatchCalls.clear()
        RecoveryDeliveryFact.resetForTest { deliveryLogs.add(it) }
        controller = buildController()
        seedEdge()
    }

    @After
    fun tearDown() {
        RecoveryDeliveryFact.resetForTest()
        controller.clearAll()
        scheduler.shutdownNow()
    }

    private fun buildController(
        maxDeliveryAttempts: Int = 3,
        deliveryRetryIntervalMs: Long = 200L,
        deliveryRetryMinGapMs: Long = 0L
    ): ConferenceEdgeRecoveryController {
        return ConferenceEdgeRecoveryController(
            localModuleId = "M02",
            debounceMs = 50L,
            iceRestartTimeoutMs = 200L,
            attemptBudgetMs = 5_000L,
            observationWindowMs = 10_000L,
            clock = { nowMs },
            scheduler = scheduler,
            onLog = { },
            onRequestReattach = { _, _, _ -> ReattachDispatchOutcome.SENT },
            onIceRestart = { _, _ -> true },
            maxDeliveryAttempts = maxDeliveryAttempts,
            deliveryRetryIntervalMs = deliveryRetryIntervalMs,
            deliveryRetryMinGapMs = deliveryRetryMinGapMs,
            onDispatchRecoveryOffer = { sid, rid, lineage, attempt ->
                dispatchCalls.add(lineage to attempt)
                controller.onRecoveryOfferDeliveryPending(
                    sessionId = sid,
                    remoteModuleId = rid,
                    identity = deliveryIdentity(deliveryAttemptId = attempt, lineage = lineage)
                )
                true
            }
        )
    }

    private fun eligible() = EdgeRecoveryEligibility(
        lifecycleEstablished = true,
        localJoined = true,
        remoteJoined = true,
        conferenceTerminated = false
    )

    private fun seedEdge() {
        controller.onIceStateChanged(
            sessionId = sessionId,
            channelId = "CH-1",
            remoteModuleId = remoteModuleId,
            iceState = "FAILED",
            eligibility = eligible(),
            initiatesReattach = false
        )
    }

    private fun deliveryIdentity(
        recoveryAttemptId: Long = 1L,
        deliveryAttemptId: Long = 1L,
        lineage: String = lineageId
    ) = RecoveryDeliveryFact.Identity(
        offerLineageId = lineage,
        recoveryAttemptId = recoveryAttemptId,
        obligationGeneration = 1L,
        deliveryAttemptId = deliveryAttemptId,
        from = "M02",
        to = remoteModuleId
    )

    private fun firstOutboundPending(attemptId: Long = 1L) {
        controller.onRecoveryOfferDeliveryPending(
            sessionId = sessionId,
            remoteModuleId = remoteModuleId,
            identity = deliveryIdentity(deliveryAttemptId = attemptId)
        )
    }

    @Test
    fun caseA_hintRetrySameLineageThenAckConfirmed() {
        firstOutboundPending(1L)
        deliveryLogs.clear()

        controller.evaluateRecoveryOfferDeliveryRetryForTest(
            sessionId,
            remoteModuleId,
            "LINK_READY"
        )

        assertEquals(listOf(lineageId to 2L), dispatchCalls)
        assertTrue(
            deliveryLogs.any {
                it.contains("RECOVERY_DELIVERY_RETRY_PENDING") && it.contains("deliveryAttemptId=2")
            }
        )

        controller.onRecoveryOfferDeliveryConfirmed(sessionId, remoteModuleId, lineageId)
        RecoveryDeliveryFact.emit(
            RecoveryDeliveryFact.Phase.DELIVERY_CONFIRMED,
            deliveryIdentity(deliveryAttemptId = 2L),
            sessionId
        )

        assertFalse(controller.isRecoveryOfferDeliveryExhausted(sessionId, remoteModuleId))
        assertTrue(deliveryLogs.any { it.contains("RECOVERY_DELIVERY_CONFIRMED") })
    }

    @Test
    fun caseB_singleAckNoRetry() {
        firstOutboundPending(1L)
        dispatchCalls.clear()
        deliveryLogs.clear()

        controller.onRecoveryOfferDeliveryConfirmed(sessionId, remoteModuleId, lineageId)
        RecoveryDeliveryFact.emit(
            RecoveryDeliveryFact.Phase.DELIVERY_CONFIRMED,
            deliveryIdentity(deliveryAttemptId = 1L),
            sessionId
        )

        nowMs += 500L
        controller.evaluateRecoveryOfferDeliveryRetryForTest(
            sessionId,
            remoteModuleId,
            "delivery_retry_timer"
        )

        assertTrue(dispatchCalls.isEmpty())
        assertTrue(deliveryLogs.none { it.contains("RECOVERY_DELIVERY_RETRY_PENDING") })
        assertTrue(deliveryLogs.any { it.contains("RECOVERY_DELIVERY_CONFIRMED") })
    }

    @Test
    fun caseC_exhaustThenLateAckDiscarded() {
        firstOutboundPending(1L)
        controller.evaluateRecoveryOfferDeliveryRetryForTest(sessionId, remoteModuleId, "LINK_READY")
        controller.evaluateRecoveryOfferDeliveryRetryForTest(sessionId, remoteModuleId, "LINK_READY")
        controller.evaluateRecoveryOfferDeliveryRetryForTest(sessionId, remoteModuleId, "delivery_retry_timer")

        assertTrue(controller.isRecoveryOfferDeliveryExhausted(sessionId, remoteModuleId))
        assertTrue(deliveryLogs.any { it.contains("RECOVERY_DELIVERY_EXHAUSTED") })

        dispatchCalls.clear()
        controller.onRecoveryOfferDeliveryConfirmed(sessionId, remoteModuleId, lineageId)

        assertTrue(controller.isRecoveryOfferDeliveryExhausted(sessionId, remoteModuleId))
        assertTrue(dispatchCalls.isEmpty())
        assertTrue(deliveryLogs.none { it.contains("RECOVERY_DELIVERY_CONFIRMED") })
    }
}