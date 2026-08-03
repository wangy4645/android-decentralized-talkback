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
 * PR3-1: admission gate blocks dispatch without PR2 delivery lifecycle side effects.
 */
class RecoveryAdmissionGateTest {

    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private var nowMs = 0L
    private val deliveryLogs = mutableListOf<String>()
    private val dispatchCalls = mutableListOf<Pair<String, Long>>()

    private lateinit var controller: ConferenceEdgeRecoveryController
    private val sessionId = "sess-1"
    private val remoteModuleId = "003"
    private val lineageId = "L1"

    @Before
    fun setUp() {
        nowMs = 0L
        deliveryLogs.clear()
        dispatchCalls.clear()
        RecoveryDeliveryFact.resetForTest { deliveryLogs.add(it) }
        controller = buildController(admissionProjection = staleAdmissionProjection())
        seedEdge()
    }

    @After
    fun tearDown() {
        RecoveryDeliveryFact.resetForTest()
        controller.clearAll()
        scheduler.shutdownNow()
    }

    private fun staleAdmissionProjection(): PeerSignalingReachabilityProjection =
        PeerSignalingReachabilityProjection(
            confidence = PeerSignalingReachabilityConfidence.MEDIUM,
            decision = AdmissionDecisionProjection.WAITING_STALE,
            reason = AdmissionConfidenceReason.INBOUND_STALE_FOR_RECOVERY_DISPATCH,
            lastInboundAgeMs = 14_000L
        )

    private fun buildController(
        admissionProjection: PeerSignalingReachabilityProjection = defaultRecoveryAdmissionProjection()
    ): ConferenceEdgeRecoveryController {
        return ConferenceEdgeRecoveryController(
            localModuleId = "002",
            debounceMs = 50L,
            iceRestartTimeoutMs = 200L,
            attemptBudgetMs = 1_000L,
            observationWindowMs = 10_000L,
            clock = { nowMs },
            scheduler = scheduler,
            onRequestReattach = { _, _, _ -> ReattachDispatchOutcome.SENT },
            onIceRestart = { _, _ -> true },
            maxDeliveryAttempts = 3,
            deliveryRetryIntervalMs = 200L,
            deliveryRetryMinGapMs = 0L,
            onDispatchRecoveryOffer = { sid, rid, lineage, attempt ->
                dispatchCalls.add(lineage to attempt)
                controller.onRecoveryOfferDeliveryPending(
                    sessionId = sid,
                    remoteModuleId = rid,
                    identity = deliveryIdentity(attempt, lineage)
                )
                true
            },
            evaluateRecoveryAdmission = { _, _ -> admissionProjection }
        )
    }

    private fun deliveryIdentity(deliveryAttemptId: Long, lineage: String = lineageId) =
        RecoveryDeliveryFact.Identity(
            offerLineageId = lineage,
            recoveryAttemptId = 1L,
            obligationGeneration = 1L,
            deliveryAttemptId = deliveryAttemptId,
            from = "002",
            to = remoteModuleId
        )

    private fun seedEdge() {
        controller.onIceStateChanged(
            sessionId = sessionId,
            channelId = "CH-1",
            remoteModuleId = remoteModuleId,
            iceState = "FAILED",
            eligibility = EdgeRecoveryEligibility(
                lifecycleEstablished = true,
                localJoined = true,
                remoteJoined = true,
                conferenceTerminated = false
            ),
            initiatesReattach = false
        )
    }

    @Test
    fun projection_maps_stale_to_admission_waiting_reason() {
        val projection = staleAdmissionProjection()
        assertEquals(
            RecoveryWaitingReason.ADMISSION_CONFIDENCE_STALE,
            projection.toRecoveryWaitingReason()
        )
        assertEquals("admission_confidence_stale", projection.admissionRetryDeferReason())
    }

    @Test
    fun projection_maps_low_to_admission_waiting_reason() {
        val projection = PeerSignalingReachabilityProjection(
            confidence = PeerSignalingReachabilityConfidence.LOW,
            decision = AdmissionDecisionProjection.WAITING_LOW,
            reason = AdmissionConfidenceReason.NO_CURRENT_EPOCH_INBOUND,
            lastInboundAgeMs = null
        )
        assertEquals(
            RecoveryWaitingReason.ADMISSION_CONFIDENCE_LOW,
            projection.toRecoveryWaitingReason()
        )
        assertEquals("admission_confidence_low", projection.admissionRetryDeferReason())
    }

    @Test
    fun retry_deferred_when_admission_not_high_without_delivery_attempt_increment() {
        controller.onRecoveryOfferDeliveryPending(
            sessionId = sessionId,
            remoteModuleId = remoteModuleId,
            identity = deliveryIdentity(deliveryAttemptId = 1L)
        )
        deliveryLogs.clear()
        dispatchCalls.clear()

        controller.evaluateRecoveryOfferDeliveryRetryForTest(sessionId, remoteModuleId, "LINK_READY")

        assertTrue(dispatchCalls.isEmpty())
        assertTrue(
            deliveryLogs.any {
                it.contains("RETRY_DEFERRED") && it.contains("admission_confidence_stale")
            }
        )
        assertFalse(
            deliveryLogs.any { it.contains("DELIVERY_RETRY_PENDING") }
        )
    }

    @Test
    fun high_admission_allows_pr2_retry_dispatch() {
        controller.clearAll()
        controller = buildController(admissionProjection = defaultRecoveryAdmissionProjection())
        seedEdge()
        controller.onRecoveryOfferDeliveryPending(
            sessionId = sessionId,
            remoteModuleId = remoteModuleId,
            identity = deliveryIdentity(deliveryAttemptId = 1L)
        )
        deliveryLogs.clear()
        dispatchCalls.clear()
        nowMs += 500L

        controller.evaluateRecoveryOfferDeliveryRetryForTest(sessionId, remoteModuleId, "delivery_retry_timer")

        assertEquals(listOf(lineageId to 2L), dispatchCalls)
        assertTrue(deliveryLogs.any { it.contains("DELIVERY_RETRY_PENDING") })
    }
}
