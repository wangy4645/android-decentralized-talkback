package com.talkback.core.session

import com.talkback.core.util.TalkbackLog
import java.util.concurrent.ConcurrentHashMap

/**
 * PR5-3 — thin facade (Option A) beside [CompletionObservationProjection].
 *
 * M0: optional shadow log ([shadowEnabled], default OFF).
 * M1: cache latest [EpisodeCompletionProjection] for adapter read (UVCP completion axis).
 * Does not decide canClose, mutate obligations, or alter connectivity Class C mapping.
 */
object EpisodeCompletionProjectionFacade {

    const val COMPLETION_SOURCE_OBSERVATION = "COMPLETION_OBSERVATION"

    @Volatile
    var shadowEnabled: Boolean = false

    private val latestByEdge = ConcurrentHashMap<String, EpisodeCompletionProjection>()

    private fun edgeKey(sessionId: String, remoteModuleId: String): String =
        "$sessionId|$remoteModuleId"

    fun fromObservation(
        result: CompletionObservationProjection.CompletionObservationResult,
        completionEpochMs: Long = System.currentTimeMillis()
    ): EpisodeCompletionProjection {
        val state = mapState(result.episodeCompletionCandidate)
        val reason = when (state) {
            EpisodeCompletionState.RECOVERED ->
                CompletionObservationProjection.WaitingReason.NONE.name
            EpisodeCompletionState.FAILED_FINAL,
            EpisodeCompletionState.OPEN ->
                result.waitingReason.name
        }
        return EpisodeCompletionProjection(
            sessionId = result.sessionId,
            remoteModuleId = result.remoteModuleId,
            obligationGeneration = result.obligationGeneration,
            completionState = state,
            completionReason = reason,
            completionSource = COMPLETION_SOURCE_OBSERVATION,
            completionEpochMs = completionEpochMs
        )
    }

    /** Remember projection for M1 adapter consumers. Always safe; no UI by itself. */
    fun remember(projection: EpisodeCompletionProjection): EpisodeCompletionProjection {
        latestByEdge[edgeKey(projection.sessionId, projection.remoteModuleId)] = projection
        return projection
    }

    fun latest(sessionId: String, remoteModuleId: String): EpisodeCompletionProjection? =
        latestByEdge[edgeKey(sessionId, remoteModuleId)]

    /**
     * Map observation → projection, cache for M1, optionally shadow-log (M0).
     * One evaluation → one remember; shadow line only if [shadowEnabled].
     */
    fun onObservation(
        result: CompletionObservationProjection.CompletionObservationResult,
        logSink: ((String) -> Unit)? = null,
        completionEpochMs: Long = System.currentTimeMillis()
    ): EpisodeCompletionProjection {
        val projection = remember(fromObservation(result, completionEpochMs))
        if (shadowEnabled) {
            val emit = logSink ?: { TalkbackLog.i(it) }
            emit(projection.toShadowLogLine())
        }
        return projection
    }

    @Deprecated("Use onObservation", ReplaceWith("onObservation(result, logSink, completionEpochMs)"))
    fun maybeShadowLog(
        result: CompletionObservationProjection.CompletionObservationResult,
        logSink: ((String) -> Unit)? = null,
        completionEpochMs: Long = System.currentTimeMillis()
    ) {
        onObservation(result, logSink, completionEpochMs)
    }

    internal fun mapState(
        candidate: CompletionObservationProjection.CompletionCandidate
    ): EpisodeCompletionState =
        when (candidate) {
            CompletionObservationProjection.CompletionCandidate.RECOVERED ->
                EpisodeCompletionState.RECOVERED
            CompletionObservationProjection.CompletionCandidate.WAITING,
            CompletionObservationProjection.CompletionCandidate.CONTINUE_RECOVERY ->
                EpisodeCompletionState.OPEN
        }

    internal fun resetForTest(enabled: Boolean = false) {
        shadowEnabled = enabled
        latestByEdge.clear()
    }
}