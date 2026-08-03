package com.talkback.core.session

import com.talkback.core.model.RecoveryHandlerOutcome
import com.talkback.core.util.RecoveryDeliveryFact
import com.talkback.core.util.RecoveryReattachAckFields
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.Executors

/** ADR-0035 PR4 — handler outcome ACK contract UT Cases A-D. */
class RecoveryHandlerAckContractTest {
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private var nowMs = 0L
    private val logs = mutableListOf<String>()
    private lateinit var controller: ConferenceEdgeRecoveryController

    private val sessionId = "sess-pr4"
    private val remoteModuleId = "M03"
    private val lineageId = "L2"

    @Before
    fun setUp() {
        nowMs = 0L
        logs.clear()
        RecoveryDeliveryFact.resetForTest { logs.add(it) }
        controller = ConferenceEdgeRecoveryController(
            localModuleId = "M02",
            debounceMs = 50L,
            iceRestartTimeoutMs = 200L,
            attemptBudgetMs = 5_000L,
            observationWindowMs = 10_000L,
            clock = { nowMs },
            scheduler = scheduler,
            onLog = { logs.add(it) },
            onRequestReattach = { _, _, _ -> ReattachDispatchOutcome.SENT },
            onIceRestart = { _, _ -> true },
            deliveryRetryMinGapMs = 0L,
            onDispatchRecoveryOffer = { sid, rid, lineage, attempt ->
                controller.onRecoveryOfferDeliveryPending(
                    sessionId = sid,
                    remoteModuleId = rid,
                    identity = RecoveryDeliveryFact.Identity(
                        offerLineageId = lineage,
                        recoveryAttemptId = 1L,
                        obligationGeneration = 1L,
                        deliveryAttemptId = attempt,
                        from = "M02",
                        to = rid
                    )
                )
                true
            }
        )
        seedEdge()
    }

    @After
    fun tearDown() {
        RecoveryDeliveryFact.resetForTest()
        controller.clearAll()
        scheduler.shutdownNow()
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

    private fun deliveryIdentity(deliveryAttemptId: Long = 1L) = RecoveryDeliveryFact.Identity(
        offerLineageId = lineageId,
        recoveryAttemptId = 1L,
        obligationGeneration = 1L,
        deliveryAttemptId = deliveryAttemptId,
        from = "M02",
        to = remoteModuleId
    )

    @Test
    fun caseA_acceptedAck_confirmedAndReevaluateStarted() {
        controller.onRecoveryOfferDeliveryPending(
            sessionId,
            remoteModuleId,
            deliveryIdentity(1L)
        )
        controller.onRecoveryOfferDeliveryConfirmed(
            sessionId,
            remoteModuleId,
            lineageId,
            RecoveryHandlerOutcome.ACCEPTED
        )
        assertTrue(logs.any { it.contains("RECOVERY_REEVALUATE_STARTED") && it.contains("ACCEPTED") })
        assertFalse(controller.isRecoveryOfferDeliveryExhausted(sessionId, remoteModuleId))
    }

    @Test
    fun caseB_alreadySatisfied_projectionResultNotRecovered() {
        controller.onRecoveryOfferDeliveryPending(sessionId, remoteModuleId, deliveryIdentity(1L))
        controller.onRecoveryOfferDeliveryConfirmed(
            sessionId,
            remoteModuleId,
            lineageId,
            RecoveryHandlerOutcome.ALREADY_SATISFIED
        )
        val snapshot = EdgeReachabilitySnapshot(
            linkReady = true,
            peerDiscovered = true,
            peerSignalingReachable = true,
            mediaRouteConnected = true,
            authorityReachable = false
        )
        controller.onRecoveryReachabilityChanged(
            sessionId = sessionId,
            channelId = "CH-1",
            remoteModuleId = remoteModuleId,
            snapshot = snapshot,
            signature = RecoveryCapabilitySignature(
                permittedActions = emptySet(),
                waitingReason = RecoveryWaitingReason.WAITING_FOR_AUTHORITY
            ),
            capabilityBefore = null,
            trigger = RecoveryReevaluateTrigger.DELIVERY_CONFIRMED
        )
        assertTrue(logs.any { it.contains("RECOVERY_PROJECTION_RESULT") })
        assertTrue(logs.any { it.contains("deliveryConfirmedOutcome=ALREADY_SATISFIED") })
        assertFalse(controller.edgePhaseSummary(sessionId).contains("RECOVERED"))
    }

    @Test
    fun caseC_staleIdentityAckIgnored() {
        val identity = deliveryIdentity(1L)
        val ackFields = RecoveryReattachAckFields(
            offerLineageId = lineageId,
            recoveryAttemptId = 1L,
            obligationGeneration = 0L,
            deliveryAttemptId = 1L,
            handlerOutcome = RecoveryHandlerOutcome.ALREADY_SATISFIED
        )
        assertFalse(RecoveryDeliveryFact.matchesAck(identity, ackFields))
    }

    @Test
    fun caseE_matchesAckIgnoresRecoveryAttemptIdMismatch() {
        val identity = deliveryIdentity(1L).copy(recoveryAttemptId = 2L)
        val ackFields = RecoveryReattachAckFields(
            offerLineageId = lineageId,
            recoveryAttemptId = 1L,
            obligationGeneration = 1L,
            deliveryAttemptId = 1L,
            handlerOutcome = RecoveryHandlerOutcome.ALREADY_SATISFIED
        )
        assertTrue(RecoveryDeliveryFact.matchesAck(identity, ackFields))
    }

    @Test
    fun caseD_lateAckAfterExhausted_observationOnly() {
        controller.onRecoveryOfferDeliveryPending(sessionId, remoteModuleId, deliveryIdentity(1L))
        controller.evaluateRecoveryOfferDeliveryRetryForTest(sessionId, remoteModuleId, "LINK_READY")
        controller.evaluateRecoveryOfferDeliveryRetryForTest(sessionId, remoteModuleId, "LINK_READY")
        controller.evaluateRecoveryOfferDeliveryRetryForTest(sessionId, remoteModuleId, "delivery_retry_timer")
        assertTrue(controller.isRecoveryOfferDeliveryExhausted(sessionId, remoteModuleId))
        controller.onRecoveryOfferDeliveryConfirmed(sessionId, remoteModuleId, lineageId)
        assertTrue(controller.isRecoveryOfferDeliveryExhausted(sessionId, remoteModuleId))
    }
}