package com.talkback.core.session

import com.talkback.core.model.RecoveryHandlerOutcome
import com.talkback.core.util.TalkbackLog

/** PR5-0 read-only completion observation (ADR-0022). */
object CompletionObservationProjection {

    enum class CompletionCandidate { RECOVERED, WAITING, CONTINUE_RECOVERY }

    enum class AttemptObservationState {
        ATTEMPT_IDLE, ATTEMPT_REQUESTED, ATTEMPT_DISPATCHING,
        ATTEMPT_WAITING_DELIVERY, ATTEMPT_NEGOTIATING, ATTEMPT_FAILED, ATTEMPT_SUCCEEDED
    }

    enum class WaitingReason {
        DELIVERY_PENDING, MEDIA_RECOVERY_PENDING, CONTROL_RECONCILIATION_PENDING,
        TOPOLOGY_PENDING, DEFERRED_INTENT_UNCOVERED, ATTEMPT_TERMINAL_OPEN_OBLIGATION,
        ICE_TRANSPORT_PENDING, NONE
    }

    data class CompletionObservationResult(
        val sessionId: String,
        val remoteModuleId: String,
        val attemptId: Long,
        val obligationGeneration: Long,
        val deliveryConfirmed: Boolean,
        val deliveryRequired: Boolean,
        val iceConnected: Boolean,
        val mediaRecoveryEvidenceSatisfied: Boolean,
        val mediaUnavailableAdvisory: Boolean,
        val controlReconciled: Boolean,
        val topologySatisfied: Boolean,
        val hasUncoveredDeferredIntent: Boolean,
        val deliveryConfirmedOutcome: RecoveryHandlerOutcome?,
        val candidate: CompletionCandidate,
        val waitingReason: WaitingReason,
        val attemptState: AttemptObservationState,
        val attemptTerminal: Boolean,
        val obligationOpen: Boolean,
        val episodeCompletionCandidate: CompletionCandidate
    )

    internal fun project(
        record: EdgeRecoveryRecord,
        snapshot: EdgeReachabilitySnapshot,
        iceConnected: Boolean,
        mediaUnavailableAdvisory: Boolean,
        hasUncoveredDeferredIntent: Boolean
    ): CompletionObservationResult {
        val key = record.key
        val deliveryRequired = deliveryPredicateRequired(record)
        val deliveryConfirmed = record.recoveryOfferDeliveryPhase == RecoveryOfferDeliveryPhase.CONFIRMED
        val mediaRecoveryEvidenceSatisfied =
            !mediaUnavailableAdvisory && (record.mediaRestored || snapshot.mediaRouteConnected)
        val controlReconciled = controlReconciliationCompleted(record)
        val topologySatisfied = topologyPredicateSatisfied(snapshot)
        val attemptState = mapAttemptState(record)
        val attemptTerminal =
            attemptState == AttemptObservationState.ATTEMPT_FAILED ||
                attemptState == AttemptObservationState.ATTEMPT_SUCCEEDED
        val obligationOpen = record.edgeObligationOpen()
        val waitingReason = firstBlockingReason(
            deliveryRequired, deliveryConfirmed, iceConnected, mediaRecoveryEvidenceSatisfied,
            controlReconciled, topologySatisfied, hasUncoveredDeferredIntent, attemptTerminal, obligationOpen
        )
        val candidate = when {
            waitingReason == WaitingReason.NONE -> CompletionCandidate.RECOVERED
            attemptTerminal && obligationOpen -> CompletionCandidate.CONTINUE_RECOVERY
            else -> CompletionCandidate.WAITING
        }
        return CompletionObservationResult(
            sessionId = key.sessionId,
            remoteModuleId = key.remoteModuleId,
            attemptId = record.recoveryAttemptId,
            obligationGeneration = record.obligationGeneration,
            deliveryConfirmed = deliveryConfirmed,
            deliveryRequired = deliveryRequired,
            iceConnected = iceConnected,
            mediaRecoveryEvidenceSatisfied = mediaRecoveryEvidenceSatisfied,
            mediaUnavailableAdvisory = mediaUnavailableAdvisory,
            controlReconciled = controlReconciled,
            topologySatisfied = topologySatisfied,
            hasUncoveredDeferredIntent = hasUncoveredDeferredIntent,
            deliveryConfirmedOutcome = record.deliveryConfirmedOutcome,
            candidate = candidate,
            waitingReason = waitingReason,
            attemptState = attemptState,
            attemptTerminal = attemptTerminal,
            obligationOpen = obligationOpen,
            episodeCompletionCandidate = candidate
        )
    }

    internal fun logObservations(
        result: CompletionObservationResult,
        trigger: RecoveryReevaluateTrigger? = null,
        logSink: ((String) -> Unit)? = null
    ) {
        val triggerPart = trigger?.let { " trigger=$it" } ?: ""
        val outcomePart = result.deliveryConfirmedOutcome?.name ?: "NONE"
        val emit: (String) -> Unit = logSink ?: testLogSink ?: { TalkbackLog.i(it) }
        emit(
            "RECOVERY_ATTEMPT_OBSERVATION session=${result.sessionId} remote=${result.remoteModuleId} " +
                "attemptId=${result.attemptId} attemptState=${result.attemptState} " +
                "attemptTerminal=${result.attemptTerminal}$triggerPart"
        )
        emit(
            "RECOVERY_EPISODE_OBSERVATION session=${result.sessionId} remote=${result.remoteModuleId} " +
                "obligationGen=${result.obligationGeneration} obligationOpen=${result.obligationOpen} " +
                "completionCandidate=${result.episodeCompletionCandidate} " +
                "uncoveredIntent=${result.hasUncoveredDeferredIntent}"
        )
        emit(
            "RECOVERY_COMPLETION_OBSERVATION session=${result.sessionId} edge=${result.remoteModuleId} " +
                "deliveryConfirmed=${result.deliveryConfirmed} deliveryRequired=${result.deliveryRequired} " +
                "iceConnected=${result.iceConnected} " +
                "mediaRecoveryEvidenceSatisfied=${result.mediaRecoveryEvidenceSatisfied} " +
                "mediaUnavailableAdvisory=${result.mediaUnavailableAdvisory} " +
                "controlReconciled=${result.controlReconciled} topologySatisfied=${result.topologySatisfied} " +
                "deliveryConfirmedOutcome=$outcomePart candidate=${result.candidate} " +
                "reason=${result.waitingReason}$triggerPart"
        )
    }

    internal fun deliveryPredicateRequired(record: EdgeRecoveryRecord): Boolean =
        record.recoveryOfferLineageId != null ||
            record.recoveryOfferDeliveryPhase != RecoveryOfferDeliveryPhase.NONE

    internal fun controlReconciliationCompleted(record: EdgeRecoveryRecord): Boolean =
        record.controlReconciliationFact
            ?.takeIf { it.isCurrentFor(record) }
            ?.result
            ?: false

    internal fun topologyPredicateSatisfied(snapshot: EdgeReachabilitySnapshot): Boolean =
        snapshot.linkReady && snapshot.peerDiscovered &&
            snapshot.peerSignalingReachable && snapshot.authorityReachable

    internal fun mapAttemptState(record: EdgeRecoveryRecord): AttemptObservationState =
        mapAttemptObservationState(RecoveryAttemptOwner.resolveState(record))

    internal fun mapAttemptObservationState(state: RecoveryAttemptState): AttemptObservationState =
        when (state) {
            RecoveryAttemptState.ATTEMPT_IDLE -> AttemptObservationState.ATTEMPT_IDLE
            RecoveryAttemptState.ATTEMPT_REQUESTED -> AttemptObservationState.ATTEMPT_REQUESTED
            RecoveryAttemptState.ATTEMPT_DISPATCHING -> AttemptObservationState.ATTEMPT_DISPATCHING
            RecoveryAttemptState.ATTEMPT_WAITING_DELIVERY -> AttemptObservationState.ATTEMPT_WAITING_DELIVERY
            RecoveryAttemptState.ATTEMPT_NEGOTIATING -> AttemptObservationState.ATTEMPT_NEGOTIATING
            RecoveryAttemptState.ATTEMPT_FAILED -> AttemptObservationState.ATTEMPT_FAILED
            RecoveryAttemptState.ATTEMPT_SUCCEEDED -> AttemptObservationState.ATTEMPT_SUCCEEDED
        }

    private fun firstBlockingReason(
        deliveryRequired: Boolean,
        deliveryConfirmed: Boolean,
        iceConnected: Boolean,
        mediaRecoveryEvidenceSatisfied: Boolean,
        controlReconciled: Boolean,
        topologySatisfied: Boolean,
        hasUncoveredDeferredIntent: Boolean,
        attemptTerminal: Boolean,
        obligationOpen: Boolean
    ): WaitingReason = when {
        deliveryRequired && !deliveryConfirmed -> WaitingReason.DELIVERY_PENDING
        !iceConnected -> WaitingReason.ICE_TRANSPORT_PENDING
        !mediaRecoveryEvidenceSatisfied -> WaitingReason.MEDIA_RECOVERY_PENDING
        !controlReconciled -> WaitingReason.CONTROL_RECONCILIATION_PENDING
        !topologySatisfied -> WaitingReason.TOPOLOGY_PENDING
        hasUncoveredDeferredIntent -> WaitingReason.DEFERRED_INTENT_UNCOVERED
        attemptTerminal && obligationOpen -> WaitingReason.ATTEMPT_TERMINAL_OPEN_OBLIGATION
        else -> WaitingReason.NONE
    }

    private var testLogSink: ((String) -> Unit)? = null

    internal fun resetForTest(sink: ((String) -> Unit)? = null) { testLogSink = sink }

    internal fun testLogSink(): ((String) -> Unit)? = testLogSink
}