package com.talkback.core.session

import com.talkback.core.model.RecoveryHandlerOutcome
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * C0 characterization: frozen ADR-0022 Q2 completion predicate projection on main.
 *
 * Assertion discipline (ADR-0022 Appendix E):
 * - **INVARIANT** — permanent contract; removal requires ADR amendment.
 * - **CURRENT_BEHAVIOR** — pins injected/unwired seams; change only via numbered resolution (§E.18).
 */
class CompletionObservationProjectionTest {

    private val logs = mutableListOf<String>()

    @Before
    fun setUp() {
        logs.clear()
        CompletionObservationProjection.resetForTest { logs.add(it) }
    }

    @After
    fun tearDown() {
        CompletionObservationProjection.resetForTest()
    }

    private fun record(
        phase: EdgeRecoveryPhase,
        deliveryPhase: RecoveryOfferDeliveryPhase = RecoveryOfferDeliveryPhase.NONE,
        lineageId: String? = "L1",
        mediaRestored: Boolean = false,
        outcome: RecoveryHandlerOutcome? = null
    ): EdgeRecoveryRecord {
        val key = ConferenceEdgeKey("sess-pr50", "M02")
        return EdgeRecoveryRecord(
            key = key,
            phase = phase,
            channelId = "CH-1",
            recoveryAttemptId = 1L,
            recoveryStartedAtMs = 0L,
            recoveryOfferDeliveryPhase = deliveryPhase,
            recoveryOfferLineageId = lineageId,
            mediaRestored = mediaRestored,
            deliveryConfirmedOutcome = outcome
        )
    }

    private fun snapshot(mediaRoute: Boolean = false) = EdgeReachabilitySnapshot(
        linkReady = true,
        peerDiscovered = true,
        peerSignalingReachable = true,
        mediaRouteConnected = mediaRoute,
        authorityReachable = true
    )

    /** Injects evaluator result directly; not the production unwired membership seam (§E.18). */
    private fun attachControlFact(record: EdgeRecoveryRecord, membershipConverged: Boolean = true) {
        record.controlReconciliationFact = ControlReconciliationEvaluator.evaluate(
            record = record,
            membershipEpochConverged = membershipConverged
        )
    }

    /** INVARIANT: delivery not confirmed → WAITING / DELIVERY_PENDING. */
    @Test
    fun invariant_deliveryFalse_candidateWaiting() {
        val r = project(
            record(phase = EdgeRecoveryPhase.REATTACH_REQUESTED, deliveryPhase = RecoveryOfferDeliveryPhase.PENDING),
            iceConnected = true,
            mediaUnavailable = false
        )
        assertEquals(CompletionObservationProjection.CompletionCandidate.WAITING, r.candidate)
        assertEquals(
            CompletionObservationProjection.WaitingReason.DELIVERY_PENDING,
            r.waitingReason
        )
    }

    /** INVARIANT: delivery confirmed but media advisory → WAITING / MEDIA_RECOVERY_PENDING. */
    @Test
    fun invariant_deliveryTrue_mediaFalse_candidateWaiting() {
        val r = project(
            record(
                phase = EdgeRecoveryPhase.REATTACH_REQUESTED,
                deliveryPhase = RecoveryOfferDeliveryPhase.CONFIRMED
            ),
            iceConnected = true,
            mediaUnavailable = true
        )
        assertEquals(CompletionObservationProjection.CompletionCandidate.WAITING, r.candidate)
        assertEquals(
            CompletionObservationProjection.WaitingReason.MEDIA_RECOVERY_PENDING,
            r.waitingReason
        )
    }

    /** INVARIANT: delivery + media satisfied but control fact absent → CONTROL_RECONCILIATION_PENDING. */
    @Test
    fun invariant_deliveryAndMediaTrue_controlFalse_candidateWaiting() {
        val r = project(
            record(
                phase = EdgeRecoveryPhase.REATTACH_REQUESTED,
                deliveryPhase = RecoveryOfferDeliveryPhase.CONFIRMED,
                mediaRestored = true
            ),
            iceConnected = true,
            mediaUnavailable = false
        )
        assertEquals(CompletionObservationProjection.CompletionCandidate.WAITING, r.candidate)
        assertEquals(
            CompletionObservationProjection.WaitingReason.CONTROL_RECONCILIATION_PENDING,
            r.waitingReason
        )
    }

    /**
     * CURRENT_BEHAVIOR: with injected membershipConverged=true control fact, candidate=RECOVERED.
     * Production unwired membership default is tracked separately (§E.18); not asserted here.
     */
    @Test
    fun currentBehavior_allPredicatesWithInjectedControlFact_candidateRecovered_notEpisodeState() {
        val r = record(
            phase = EdgeRecoveryPhase.ICE_RESTARTING,
            deliveryPhase = RecoveryOfferDeliveryPhase.CONFIRMED,
            mediaRestored = true
        )
        attachControlFact(r)
        val result = project(
            r,
            iceConnected = true,
            mediaUnavailable = false
        )
        assertEquals(CompletionObservationProjection.CompletionCandidate.RECOVERED, result.candidate)
        assertEquals(CompletionObservationProjection.WaitingReason.NONE, result.waitingReason)
        assertTrue(result.obligationOpen)
        assertFalse(result.attemptTerminal)
    }

    /** INVARIANT: ALREADY_SATISFIED delivery outcome does not auto-close episode (EXECUTED != RECOVERED). */
    @Test
    fun invariant_alreadySatisfied_notAutoRecovered() {
        val r = project(
            record(
                phase = EdgeRecoveryPhase.REATTACH_REQUESTED,
                deliveryPhase = RecoveryOfferDeliveryPhase.CONFIRMED,
                outcome = RecoveryHandlerOutcome.ALREADY_SATISFIED
            ),
            iceConnected = true,
            mediaUnavailable = false
        )
        assertEquals(CompletionObservationProjection.CompletionCandidate.WAITING, r.candidate)
        assertEquals(RecoveryHandlerOutcome.ALREADY_SATISFIED, r.deliveryConfirmedOutcome)
        assertFalse(r.candidate == CompletionObservationProjection.CompletionCandidate.RECOVERED)
    }

    /** INVARIANT: attempt failed → CONTINUE_RECOVERY; obligation remains open. */
    @Test
    fun invariant_attemptFailed_episodeStillOpen_continueRecovery() {
        val r = project(
            record(
                phase = EdgeRecoveryPhase.FAILED_MEDIA_RECOVERY,
                deliveryPhase = RecoveryOfferDeliveryPhase.CONFIRMED,
                mediaRestored = true
            ),
            iceConnected = true,
            mediaUnavailable = false
        )
        assertTrue(r.obligationOpen)
        assertEquals(CompletionObservationProjection.CompletionCandidate.CONTINUE_RECOVERY, r.candidate)
        assertEquals(
            CompletionObservationProjection.AttemptObservationState.ATTEMPT_FAILED,
            r.attemptState
        )
    }

    /** INVARIANT: observation logs emit attempt / episode / completion fact types (read-only seam). */
    @Test
    fun invariant_logs_emitThreeObservationTypes() {
        val r = project(
            record(
                phase = EdgeRecoveryPhase.REATTACH_REQUESTED,
                deliveryPhase = RecoveryOfferDeliveryPhase.CONFIRMED
            ),
            iceConnected = true,
            mediaUnavailable = true
        )
        CompletionObservationProjection.logObservations(
            r,
            trigger = RecoveryReevaluateTrigger.DELIVERY_CONFIRMED
        )
        assertTrue(logs.any { it.contains("RECOVERY_COMPLETION_OBSERVATION") })
        assertTrue(logs.any { it.contains("RECOVERY_ATTEMPT_OBSERVATION") })
        assertTrue(logs.any { it.contains("RECOVERY_EPISODE_OBSERVATION") })
        assertTrue(logs.any { it.contains("reason=MEDIA_RECOVERY_PENDING") })
    }

    private fun project(
        record: EdgeRecoveryRecord,
        iceConnected: Boolean,
        mediaUnavailable: Boolean
    ): CompletionObservationProjection.CompletionObservationResult =
        CompletionObservationProjection.project(
            record = record,
            snapshot = snapshot(mediaRoute = record.mediaRestored),
            iceConnected = iceConnected,
            mediaUnavailableAdvisory = mediaUnavailable,
            hasUncoveredDeferredIntent = false
        )
}
