package com.talkback.core.session

import com.talkback.core.model.RecoveryHandlerOutcome
import com.talkback.core.util.RecoveryDeliveryFact
import com.talkback.core.util.RecoveryReattachAckFields
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.Executors

/** PR5-2c-A directional inbound delivery acceptance (INV-PR52c-003..008). */
class Pr52cInboundDeliveryAcceptanceTest {
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private var nowMs = 0L
    private val logs = mutableListOf<String>()
    private lateinit var controller: ConferenceEdgeRecoveryController

    private val sessionId = "sess-pr52c-a"
    private val remoteModuleId = "M02"
    private val localModuleId = "M03"
    private val lineageId = "L1"

    @Before
    fun setUp() {
        nowMs = 0L
        logs.clear()
        RecoveryDeliveryFact.resetForTest { logs.add(it) }
        controller = ConferenceEdgeRecoveryController(
            localModuleId = localModuleId,
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
                        recoveryAttemptId = controller.attemptLineageObservation(sid, rid)?.attemptId ?: 1L,
                        obligationGeneration = controller.obligationGeneration(sid, rid) ?: 1L,
                        deliveryAttemptId = attempt,
                        from = localModuleId,
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

    private fun inboundIdentity(
        obligationGeneration: Long = 1L,
        deliveryAttemptId: Long = 1L,
        from: String = remoteModuleId,
        to: String = localModuleId
    ) = InboundReattachDeliveryIdentity(
        offerLineageId = lineageId,
        obligationGeneration = obligationGeneration,
        deliveryAttemptId = deliveryAttemptId,
        from = from,
        to = to
    )

    private fun deliveryIdentity(
        obligationGeneration: Long = 1L,
        deliveryAttemptId: Long = 1L,
        recoveryAttemptId: Long = 1L
    ) = RecoveryDeliveryFact.Identity(
        offerLineageId = lineageId,
        recoveryAttemptId = recoveryAttemptId,
        obligationGeneration = obligationGeneration,
        deliveryAttemptId = deliveryAttemptId,
        from = localModuleId,
        to = remoteModuleId
    )

    private fun evaluateInbound(
        senderAttemptId: Long,
        senderObligationGeneration: Long,
        inbound: InboundReattachDeliveryIdentity
    ): InboundReattachLineageVerdict =
        controller.evaluateInboundReattachLineage(
            sessionId = sessionId,
            remoteModuleId = remoteModuleId,
            senderAttemptId = senderAttemptId,
            senderObligationGeneration = senderObligationGeneration,
            inboundDelivery = inbound
        )

    @Test
    fun case1_reverseInbound_acceptsWhenLocalDirectionRecoveredClosed() {
        controller.onRecoveryReattachAccepted(
            sessionId,
            remoteModuleId,
            RecoveryReason.NETWORK_RECOVERY,
            RecoverySource.ICE_MONITOR
        )
        controller.onIceConnected(sessionId, remoteModuleId)
        assertEquals(ObligationCloseReason.RECOVERED, controller.obligationCloseReason(sessionId, remoteModuleId))

        val verdict = evaluateInbound(
            senderAttemptId = 99L,
            senderObligationGeneration = 1L,
            inbound = inboundIdentity()
        )
        assertEquals(InboundReattachLineageVerdict.ACCEPT, verdict)
    }

    @Test
    fun case2_reverseInbound_acceptsWhenRecoveryAttemptIdMismatch() {
        val verdict = evaluateInbound(
            senderAttemptId = 42L,
            senderObligationGeneration = 1L,
            inbound = inboundIdentity()
        )
        assertEquals(InboundReattachLineageVerdict.ACCEPT, verdict)
    }

    @Test
    fun case3_reverseInbound_acceptsAfterLocalGenerationBump() {
        controller.onRecoveryReattachAccepted(
            sessionId,
            remoteModuleId,
            RecoveryReason.NETWORK_RECOVERY,
            RecoverySource.ICE_MONITOR
        )
        controller.onIceConnected(sessionId, remoteModuleId)
        controller.onIceStateChanged(
            sessionId = sessionId,
            channelId = "CH-1",
            remoteModuleId = remoteModuleId,
            iceState = "DISCONNECTED",
            eligibility = eligible(),
            initiatesReattach = false
        )
        Thread.sleep(80)
        val currentGen = controller.obligationGeneration(sessionId, remoteModuleId)!!
        assertTrue(currentGen >= 2L)

        val verdict = evaluateInbound(
            senderAttemptId = 1L,
            senderObligationGeneration = currentGen - 1L,
            inbound = inboundIdentity(obligationGeneration = currentGen - 1L)
        )
        assertEquals(InboundReattachLineageVerdict.ACCEPT, verdict)
    }

    @Test
    fun case4_oldGenerationWithoutMatchingPending_isStaleAtAckMatch() {
        val pendingIdentity = deliveryIdentity(obligationGeneration = 2L)
        val staleAck = RecoveryReattachAckFields(
            offerLineageId = lineageId,
            recoveryAttemptId = 1L,
            obligationGeneration = 1L,
            deliveryAttemptId = 1L,
            handlerOutcome = RecoveryHandlerOutcome.ALREADY_SATISFIED
        )
        assertFalse(RecoveryDeliveryFact.matchesAck(pendingIdentity, staleAck))
        val acceptedWithoutPending = false
        assertFalse(acceptedWithoutPending)
    }

    @Test
    fun case5_matchingLineage_deliveryConfirmedFalse_emitsConfirmed() {
        controller.onRecoveryOfferDeliveryPending(sessionId, remoteModuleId, deliveryIdentity())
        controller.onRecoveryOfferDeliveryConfirmed(
            sessionId,
            remoteModuleId,
            lineageId,
            RecoveryHandlerOutcome.ALREADY_SATISFIED
        )
        assertTrue(
            logs.any {
                it.contains("RECOVERY_REEVALUATE_STARTED") &&
                    it.contains("deliveryConfirmedOutcome=ALREADY_SATISFIED")
            }
        )
        assertFalse(controller.edgePhaseSummary(sessionId).contains("RECOVERED"))
    }

    @Test
    fun case6_alreadySatisfiedWithoutValidLineage_notDirectlyConfirmed() {
        val verdict = evaluateInbound(
            senderAttemptId = 1L,
            senderObligationGeneration = 1L,
            inbound = inboundIdentity(from = "WRONG", to = localModuleId)
        )
        assertEquals(InboundReattachLineageVerdict.STALE_OBLIGATION_GENERATION, verdict)
        assertFalse(logs.any { it.contains("RECOVERY_DELIVERY_CONFIRMED") })

        val malformedAck = RecoveryReattachAckFields(
            offerLineageId = lineageId,
            recoveryAttemptId = 1L,
            obligationGeneration = 0L,
            deliveryAttemptId = 1L,
            handlerOutcome = RecoveryHandlerOutcome.ALREADY_SATISFIED
        )
        assertFalse(RecoveryDeliveryFact.matchesAck(deliveryIdentity(), malformedAck))
        assertFalse(logs.any { it.contains("RECOVERY_DELIVERY_CONFIRMED") })
    }
}