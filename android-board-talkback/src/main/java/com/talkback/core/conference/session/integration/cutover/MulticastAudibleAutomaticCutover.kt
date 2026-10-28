package com.talkback.core.conference.session.integration.cutover

import com.talkback.core.conference.session.integration.MeetingProductMediaShadow
import java.util.concurrent.ConcurrentHashMap

/**
 * R1 — production automatic cutover when [MulticastAudibleCutoverReadiness] is satisfied.
 * Idempotent per session; retries on later readiness hooks until success or terminal reject.
 */
object MulticastAudibleAutomaticCutover {
    private val terminalSessions = ConcurrentHashMap.newKeySet<String>()

    fun maybeAttempt(
        sessionId: String,
        localModuleId: String,
    ) {
        val entryState = ReplacementCutoverRc1.currentState()
        val entryDetail =
            "shadowEnabled=${MeetingProductMediaShadow.enabled} " +
                "terminal=${terminalSessions.contains(sessionId)} " +
                "audibleEnabled=${ReplacementCutoverRc1.multicastAudibleEnabled} " +
                "controllerInstalled=${ReplacementCutoverRc1.controller() != null} " +
                "state=${entryState?.name ?: "null"} module=$localModuleId"
        ReplacementCutoverObservability.logRc1Field(
            outcome = "AUTO_CUTOVER_ENTRY",
            sessionId = sessionId,
            detail = entryDetail,
        )
        android.util.Log.i(
            MeetingProductMediaShadow.LOG_TAG,
            "AUTO_CUTOVER_ENTRY session=$sessionId $entryDetail",
        )
        if (!MeetingProductMediaShadow.enabled) return
        if (terminalSessions.contains(sessionId)) return

        if (!ReplacementCutoverRc1.multicastAudibleEnabled) {
            ReplacementCutoverObservability.logRc1Field(
                outcome = "AUTO_CUTOVER_DEFERRED",
                sessionId = sessionId,
                detail = "MULTICAST_AUDIBLE_DISABLED",
            )
            return
        }

        val state = ReplacementCutoverRc1.currentState()
        when (state) {
            AudibleOwnershipState.MULTICAST_ACTIVE -> {
                terminalSessions.add(sessionId)
                return
            }
            AudibleOwnershipState.CUTOVER_ARMED -> {
                executeCutover(sessionId)
                return
            }
            AudibleOwnershipState.ANCHOR_ACTIVE, null -> Unit
        }

        val readiness = MulticastAudibleCutoverReadiness.evaluate(sessionId, localModuleId)
        if (!readiness.ready) {
            val missing = readiness.missing.joinToString(",")
            ReplacementCutoverObservability.logRc1Field(
                outcome = "AUTO_CUTOVER_DEFERRED",
                sessionId = sessionId,
                detail = missing,
            )
            android.util.Log.i(
                MeetingProductMediaShadow.LOG_TAG,
                "AUTO_CUTOVER_DEFERRED session=$sessionId missing=$missing",
            )
            return
        }

        val armOutcome = ReplacementCutoverRc1.armCutover(sessionId)
        if (armOutcome != CutoverOutcome.APPLIED) {
            markTerminalIfNeeded(sessionId, armOutcome)
            ReplacementCutoverObservability.logRc1Field(
                outcome = "AUTO_CUTOVER_ARM_FAILED",
                sessionId = sessionId,
                detail = armOutcome.name,
            )
            return
        }

        executeCutover(sessionId)
    }

    fun onSessionStopped(sessionId: String) {
        terminalSessions.remove(sessionId)
    }

    /** Wiring rotated media key — allow auto cutover to run again after member reinstall. */
    fun onMediaKeyEpochRotated(sessionId: String) {
        terminalSessions.remove(sessionId)
        ReplacementCutoverRc1.onMediaKeyEpochRotated(sessionId)
        ReplacementCutoverObservability.logRc1Field(
            outcome = "MEDIA_KEY_EPOCH_CUTOVER_RESET",
            sessionId = sessionId,
        )
    }

    private fun executeCutover(sessionId: String) {
        val outcome = ReplacementCutoverRc1.executeCutover(sessionId)
        if (outcome == CutoverOutcome.APPLIED) {
            terminalSessions.add(sessionId)
            ReplacementCutoverObservability.logRc1Field(
                outcome = "AUTO_CUTOVER_SUCCEEDED",
                sessionId = sessionId,
            )
            return
        }
        markTerminalIfNeeded(sessionId, outcome)
        ReplacementCutoverObservability.logRc1Field(
            outcome = "AUTO_CUTOVER_EXECUTE_FAILED",
            sessionId = sessionId,
            detail = outcome.name,
        )
    }

    private fun markTerminalIfNeeded(
        sessionId: String,
        outcome: CutoverOutcome,
    ) {
        when (outcome) {
            CutoverOutcome.REJECTED_INVALID_STATE,
            CutoverOutcome.REJECTED_INVARIANT_VIOLATION,
            CutoverOutcome.REJECTED_HANDOFF_FAILURE,
            -> terminalSessions.add(sessionId)
            else -> Unit
        }
    }
}
