package com.talkback.core.session

import com.talkback.core.util.TalkbackLog

/** PR5-1 attempt lifecycle states (ADR-0022 Q4-1). Orthogonal to episode completion. */
internal enum class RecoveryAttemptState {
    ATTEMPT_IDLE,
    ATTEMPT_REQUESTED,
    ATTEMPT_DISPATCHING,
    ATTEMPT_WAITING_DELIVERY,
    ATTEMPT_NEGOTIATING,
    ATTEMPT_FAILED,
    ATTEMPT_SUCCEEDED;

    fun isTerminal(): Boolean = this == ATTEMPT_FAILED || this == ATTEMPT_SUCCEEDED
}

/** Attempt-scoped facts owned by ConferenceEdgeRecoveryController (PR5-1). */
internal data class RecoveryAttemptContext(
    var attemptId: Long,
    var state: RecoveryAttemptState = RecoveryAttemptState.ATTEMPT_IDLE,
    var dispatchState: ReattachDeliveryState = ReattachDeliveryState.QUEUED,
    var negotiationActive: Boolean = false
) {
    val attemptTerminal: Boolean get() = state.isTerminal()
}

/** Single writer for attempt state (ADR-0022 Q4-1). Does not close obligation or mark RECOVERED. */
internal object RecoveryAttemptOwner {
    private var testLogSink: ((String) -> Unit)? = null
    private var boundLogSink: ((String) -> Unit)? = null

    internal fun resetForTest(logSink: ((String) -> Unit)? = null) {
        testLogSink = logSink
    }

    internal fun bindLogSink(logSink: (String) -> Unit) {
        boundLogSink = logSink
    }
    fun openAttempt(
        record: EdgeRecoveryRecord,
        state: RecoveryAttemptState = RecoveryAttemptState.ATTEMPT_REQUESTED,
        trigger: String
    ) {
        val ctx = RecoveryAttemptContext(
            attemptId = record.recoveryAttemptId,
            state = state,
            dispatchState = record.reattachDeliveryState,
            negotiationActive = state == RecoveryAttemptState.ATTEMPT_NEGOTIATING
        )
        record.attemptContext = ctx
        logTransition(record, null, state, trigger)
    }

    fun transition(
        record: EdgeRecoveryRecord,
        newState: RecoveryAttemptState,
        trigger: String,
        syncDeliveryFromRecord: Boolean = true
    ) {
        val prior = record.attemptContext?.state
        if (prior == newState) {
            if (syncDeliveryFromRecord) syncDeliveryFacts(record)
            return
        }
        if (record.attemptContext == null || record.attemptContext!!.attemptId != record.recoveryAttemptId) {
            openAttempt(record, newState, trigger)
            return
        }
        record.attemptContext!!.state = newState
        if (syncDeliveryFromRecord) syncDeliveryFacts(record)
        record.attemptContext!!.negotiationActive =
            newState == RecoveryAttemptState.ATTEMPT_NEGOTIATING
        logTransition(record, prior, newState, trigger)
    }

    fun syncDeliveryFacts(record: EdgeRecoveryRecord) {
        val ctx = record.attemptContext
        if (ctx == null || ctx.attemptId != record.recoveryAttemptId) return
        ctx.dispatchState = record.reattachDeliveryState
    }

    fun resolveState(record: EdgeRecoveryRecord): RecoveryAttemptState {
        val ctx = record.attemptContext
        if (ctx != null && ctx.attemptId == record.recoveryAttemptId) return ctx.state
        return legacyInferState(record)
    }

    fun reconcileFromFacts(record: EdgeRecoveryRecord, trigger: String) {
        val inferred = inferStateFromFacts(record)
        if (record.attemptContext == null || record.attemptContext!!.attemptId != record.recoveryAttemptId) {
            openAttempt(record, inferred, trigger)
        } else {
            transition(record, inferred, trigger)
        }
    }

    private fun inferStateFromFacts(record: EdgeRecoveryRecord): RecoveryAttemptState = when {
        record.phase.isFailedMediaRecovery() -> RecoveryAttemptState.ATTEMPT_FAILED
        record.recoveryOfferDeliveryPhase == RecoveryOfferDeliveryPhase.EXHAUSTED ->
            RecoveryAttemptState.ATTEMPT_FAILED
        record.recoveryOfferDeliveryPhase == RecoveryOfferDeliveryPhase.CONFIRMED ->
            RecoveryAttemptState.ATTEMPT_SUCCEEDED
        record.phase == EdgeRecoveryPhase.RECOVERED -> RecoveryAttemptState.ATTEMPT_SUCCEEDED
        record.phase == EdgeRecoveryPhase.ICE_RESTARTING -> RecoveryAttemptState.ATTEMPT_NEGOTIATING
        record.recoveryOfferDeliveryPhase.isAwaitingAck() ->
            RecoveryAttemptState.ATTEMPT_WAITING_DELIVERY
        record.reattachDeliveryState == ReattachDeliveryState.TRANSPORT_SENT ||
            record.phase == EdgeRecoveryPhase.REATTACH_ACCEPTED ->
            RecoveryAttemptState.ATTEMPT_DISPATCHING
        record.phase.isActivelyRecovering() -> RecoveryAttemptState.ATTEMPT_REQUESTED
        else -> RecoveryAttemptState.ATTEMPT_IDLE
    }

  private fun legacyInferState(record: EdgeRecoveryRecord): RecoveryAttemptState = when (record.phase) {
        EdgeRecoveryPhase.RECOVERED -> RecoveryAttemptState.ATTEMPT_SUCCEEDED
        EdgeRecoveryPhase.FAILED_MEDIA_RECOVERY,
        EdgeRecoveryPhase.FAILED_IDENTITY_MISMATCH,
        EdgeRecoveryPhase.FAILED_STALE_LINEAGE,
        EdgeRecoveryPhase.FAILED_REQUIRES_USER_ACTION -> RecoveryAttemptState.ATTEMPT_FAILED
        EdgeRecoveryPhase.ICE_RESTARTING -> RecoveryAttemptState.ATTEMPT_NEGOTIATING
        EdgeRecoveryPhase.REATTACH_REQUESTED -> when (record.recoveryOfferDeliveryPhase) {
            RecoveryOfferDeliveryPhase.PENDING,
            RecoveryOfferDeliveryPhase.RETRY_PENDING -> RecoveryAttemptState.ATTEMPT_WAITING_DELIVERY
            RecoveryOfferDeliveryPhase.EXHAUSTED -> RecoveryAttemptState.ATTEMPT_FAILED
            else -> RecoveryAttemptState.ATTEMPT_REQUESTED
        }
        EdgeRecoveryPhase.REATTACH_ACCEPTED -> RecoveryAttemptState.ATTEMPT_DISPATCHING
        EdgeRecoveryPhase.DISCONNECTED_DEBOUNCING,
        EdgeRecoveryPhase.RECOVERY_PENDING -> RecoveryAttemptState.ATTEMPT_REQUESTED
        EdgeRecoveryPhase.CONNECTED,
        EdgeRecoveryPhase.CANCELLED -> RecoveryAttemptState.ATTEMPT_IDLE
    }

    private fun logTransition(
        record: EdgeRecoveryRecord,
        from: RecoveryAttemptState?,
        to: RecoveryAttemptState,
        trigger: String
    ) {
        val ctx = record.attemptContext
        (testLogSink ?: boundLogSink ?: { TalkbackLog.i(it) })(
            "RECOVERY_ATTEMPT_STATE session=${record.key.sessionId} remote=${record.key.remoteModuleId} " +
                "attemptId=${record.recoveryAttemptId} from=${from ?: "NONE"} to=$to trigger=$trigger " +
                "dispatchState=${ctx?.dispatchState} deliveryPhase=${record.recoveryOfferDeliveryPhase} " +
                "attemptTerminal=${ctx?.attemptTerminal ?: false}"
        )
    }
}