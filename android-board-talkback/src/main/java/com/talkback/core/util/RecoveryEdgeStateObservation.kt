package com.talkback.core.util

import com.talkback.core.session.CompletionObservationProjection
import com.talkback.core.session.CompletionObservationProjection.CompletionObservationResult
import com.talkback.core.session.CompletionObservationProjection.WaitingReason
import com.talkback.core.session.ConferenceEdgeKey
import com.talkback.core.session.EdgeRecoveryPhase
import com.talkback.core.session.EdgeRecoveryRecord
import com.talkback.core.session.RecoveryReevaluateTrigger

/**
 * PR-OBS: read-only edge obligation lifecycle heartbeat (ADR-0040 observability spec).
 * Maps current authority facts to [ObligationState] without mutating recovery state.
 *
 * Axis contract (observability only):
 * - L1 [receivePathLive]: inbound PCM path live ([ReceivePathLivenessObserver] via [receivePathLiveProvider])
 * - L2 [mediaReady] / [iceConnected]: transport recovery evidence (completion projection)
 * - L3 [obligationState]: obligation lifecycle position
 */
object RecoveryEdgeStateObservation {

    /**
     * L1 probe wired from coordinator at startup. Read-only; does not affect recovery FSM.
     */
    @Volatile
    var receivePathLiveProvider: ((sessionId: String, remoteModuleId: String) -> Boolean)? = null

    enum class ObligationState {
        NONE,
        RECOVERING,
        GRACE_OBSERVATION,
        SYNC_PENDING,
        CONVERGED,
        DIAGNOSTIC_STALE,
        FAILED,
        CANCELLED
    }

    private const val HEARTBEAT_MS = 5_000L

    private var testLogSink: ((String) -> Unit)? = null

    private data class Track(
        var obligationState: ObligationState,
        var enteredAtMs: Long,
        var lastEmitAtMs: Long
    )

    private data class Signature(
        val obligationState: ObligationState,
        val l2Satisfied: Boolean,
        val phase: EdgeRecoveryPhase,
        val waitingReason: WaitingReason,
        val attemptTerminal: Boolean
    )

    private val tracks = mutableMapOf<ConferenceEdgeKey, Track>()
    private val lastSignature = mutableMapOf<ConferenceEdgeKey, Signature>()

    internal fun resetForTest(
        sink: ((String) -> Unit)? = null,
        receivePathLiveProvider: ((String, String) -> Boolean)? = null
    ) {
        testLogSink = sink
        this.receivePathLiveProvider = receivePathLiveProvider
        tracks.clear()
        lastSignature.clear()
    }

    internal fun derive(
        record: EdgeRecoveryRecord,
        result: CompletionObservationResult,
        nowMs: Long = System.currentTimeMillis()
    ): Derived {
        val (state, reason) = mapObligationState(record, result)
        val l2 = l2Satisfied(result)
        val key = record.key
        val track = tracks[key]
        val stateEnteredAtMs = if (track?.obligationState == state) {
            track.enteredAtMs
        } else {
            nowMs
        }
        tracks[key] = Track(
            obligationState = state,
            enteredAtMs = stateEnteredAtMs,
            lastEmitAtMs = track?.lastEmitAtMs ?: 0L
        )
        val obligationAgeMs = record.obligationOpenedAtMs?.let { nowMs - it }
        val durationInStateMs = nowMs - stateEnteredAtMs
        val receivePathLive =
            receivePathLiveProvider?.invoke(key.sessionId, key.remoteModuleId) ?: false
        return Derived(
            obligationState = state,
            stateReason = reason,
            l2Satisfied = l2,
            mediaReady = result.mediaRecoveryEvidenceSatisfied,
            controlReady = result.controlReconciled,
            receivePathLive = receivePathLive,
            obligationAgeMs = obligationAgeMs,
            durationInStateMs = durationInStateMs,
            graceWindowActive = state == ObligationState.GRACE_OBSERVATION
        )
    }

    internal data class Derived(
        val obligationState: ObligationState,
        val stateReason: String,
        val l2Satisfied: Boolean,
        val mediaReady: Boolean,
        val controlReady: Boolean,
        val receivePathLive: Boolean,
        val obligationAgeMs: Long?,
        val durationInStateMs: Long,
        val graceWindowActive: Boolean
    )

    internal fun format(
        record: EdgeRecoveryRecord,
        result: CompletionObservationResult,
        derived: Derived,
        trigger: RecoveryReevaluateTrigger?,
        lastTransition: String?
    ): String {
        val key = record.key
        val triggerPart = trigger?.let { " trigger=$it" } ?: ""
        val transitionPart = lastTransition?.let { " lastTransition=$it" } ?: ""
        return buildString {
            append("RECOVERY_EDGE_STATE")
            append(" session=").append(key.sessionId)
            append(" edge=").append(key.remoteModuleId)
            append(" episodeId=").append(record.obligationGeneration)
            append(" attemptId=").append(record.recoveryAttemptId)
            append(" obligationGen=").append(record.obligationGeneration)
            append(" obligationOpen=").append(result.obligationOpen)
            append(" obligationState=").append(derived.obligationState)
            append(" stateReason=").append(derived.stateReason)
            append(" edgePhase=").append(record.phase)
            append(" l2Satisfied=").append(derived.l2Satisfied)
            append(" mediaReady=").append(derived.mediaReady)
            append(" controlReady=").append(derived.controlReady)
            append(" receivePathLive=").append(derived.receivePathLive)
            append(" iceConnected=").append(result.iceConnected)
            append(" completionCandidate=").append(result.episodeCompletionCandidate)
            append(" completionReason=").append(result.waitingReason)
            append(" attemptState=").append(result.attemptState)
            append(" attemptTerminal=").append(result.attemptTerminal)
            append(" intentTerminal=").append(record.negotiationIntentTerminalState ?: "NONE")
            append(" graceWindowActive=").append(derived.graceWindowActive)
            append(" obligationAgeMs=").append(derived.obligationAgeMs ?: "NONE")
            append(" durationInStateMs=").append(derived.durationInStateMs)
            append(transitionPart)
            append(triggerPart)
        }
    }

    internal fun maybeEmit(
        record: EdgeRecoveryRecord,
        result: CompletionObservationResult,
        trigger: RecoveryReevaluateTrigger?,
        overrideSink: ((String) -> Unit)? = null,
        force: Boolean = false,
        lastTransition: String? = null,
        nowMs: Long = System.currentTimeMillis()
    ) {
        val derived = derive(record, result, nowMs)
        val key = record.key
        val signature = Signature(
            obligationState = derived.obligationState,
            l2Satisfied = derived.l2Satisfied,
            phase = record.phase,
            waitingReason = result.waitingReason,
            attemptTerminal = result.attemptTerminal
        )
        val prior = lastSignature[key]
        val track = tracks[key]!!
        val materialChange = prior != signature
        val heartbeatDue =
            result.obligationOpen &&
                nowMs - track.lastEmitAtMs >= HEARTBEAT_MS
        if (!force && !materialChange && !heartbeatDue) return
        track.lastEmitAtMs = nowMs
        lastSignature[key] = signature
        emit(format(record, result, derived, trigger, lastTransition), overrideSink)
    }

    internal fun emitPhaseTransition(
        record: EdgeRecoveryRecord,
        oldPhase: EdgeRecoveryPhase?,
        newPhase: EdgeRecoveryPhase,
        trigger: String,
        overrideSink: ((String) -> Unit)? = null
    ) {
        val snapshot = com.talkback.core.session.EdgeReachabilitySnapshot(
            linkReady = false,
            peerDiscovered = false,
            peerSignalingReachable = false,
            mediaRouteConnected = record.mediaRestored,
            authorityReachable = true
        )
        val iceConnected = record.mediaRestored
        val result = CompletionObservationProjection.project(
            record = record,
            snapshot = snapshot,
            iceConnected = iceConnected,
            mediaUnavailableAdvisory = !record.mediaRestored,
            hasUncoveredDeferredIntent = record.deferredReason != null
        )
        maybeEmit(
            record = record,
            result = result,
            trigger = null,
            overrideSink = overrideSink,
            force = true,
            lastTransition = "PHASE:${oldPhase ?: "NONE"}->$newPhase:$trigger"
        )
    }

    private fun emit(message: String, overrideSink: ((String) -> Unit)?) {
        if (overrideSink != null) {
            overrideSink(message)
            return
        }
        val sink = testLogSink
        if (sink != null) {
            sink(message)
        } else {
            TalkbackLog.i(message)
        }
    }

    internal fun l2Satisfied(result: CompletionObservationResult): Boolean =
        result.iceConnected &&
            result.mediaRecoveryEvidenceSatisfied &&
            result.controlReconciled &&
            result.topologySatisfied

    internal fun mapObligationState(
        record: EdgeRecoveryRecord,
        result: CompletionObservationResult
    ): Pair<ObligationState, String> {
        if (record.phase == EdgeRecoveryPhase.CANCELLED) {
            return ObligationState.CANCELLED to "PHASE_CANCELLED"
        }
        if (!result.obligationOpen) {
            return when {
                record.phase == EdgeRecoveryPhase.RECOVERED ->
                    ObligationState.CONVERGED to "EDGE_RECOVERED"
                record.phase.isFailedMediaRecovery() ||
                    record.phase == EdgeRecoveryPhase.FAILED_IDENTITY_MISMATCH ||
                    record.phase == EdgeRecoveryPhase.FAILED_STALE_LINEAGE ->
                    ObligationState.FAILED to "PHASE_${record.phase}"
                else ->
                    ObligationState.NONE to (record.obligationCloseReason?.name ?: "OBLIGATION_CLOSED")
            }
        }
        val l2 = l2Satisfied(result)
        if (l2) {
            val reason = when {
                result.hasUncoveredDeferredIntent ->
                    "MEDIA_RECOVERED_BUT_INTENT_UNCOVERED"
                result.waitingReason == WaitingReason.DEFERRED_INTENT_UNCOVERED ->
                    "MEDIA_RECOVERED_DEFERRED_INTENT_UNCOVERED"
                result.waitingReason == WaitingReason.ATTEMPT_TERMINAL_OPEN_OBLIGATION ->
                    "MEDIA_RECOVERED_ATTEMPT_TERMINAL_OPEN"
                record.phase.isFailedMediaRecovery() ->
                    "MEDIA_RECOVERED_FAILED_MEDIA_RESIDENCY"
                else -> "MEDIA_RECOVERED_OBLIGATION_OPEN"
            }
            return ObligationState.SYNC_PENDING to reason
        }
        if (result.attemptTerminal) {
            return ObligationState.GRACE_OBSERVATION to
                "ATTEMPT_TERMINAL_${result.waitingReason}"
        }
        if (record.phase.isFailedMediaRecovery()) {
            return ObligationState.RECOVERING to
                "FAILED_MEDIA_RESIDENCY_WAITING_${result.waitingReason}"
        }
        return ObligationState.RECOVERING to
            "PHASE_${record.phase}_WAITING_${result.waitingReason}"
    }
}