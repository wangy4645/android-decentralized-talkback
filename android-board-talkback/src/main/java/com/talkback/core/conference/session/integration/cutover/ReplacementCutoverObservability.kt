package com.talkback.core.conference.session.integration.cutover

import android.util.Log

object ReplacementCutoverObservability {
    const val LOG_TAG = "REPLACEMENT_CUTOVER_RC1"

    fun logState(
        sessionId: String,
        state: AudibleOwnershipState,
        detail: String? = null,
    ) {
        emit(
            "AUDIBLE_OWNERSHIP_STATE session=$sessionId state=$state" +
                (detail?.let { " detail=$it" } ?: ""),
        )
    }

    fun logHandoff(
        sessionId: String,
        phase: String,
        outcome: CutoverOutcome,
        detail: String? = null,
    ) {
        emit(
            "AUDIBLE_OWNERSHIP_HANDOFF session=$sessionId phase=$phase outcome=$outcome" +
                (detail?.let { " detail=$it" } ?: ""),
        )
    }

    fun logOwnerVerify(
        sessionId: String,
        pass: Boolean,
        anchorActive: Boolean,
        multicastProductionActive: Boolean,
    ) {
        emit(
            "OWNER_VERIFY session=$sessionId pass=$pass " +
                "anchorAudible=$anchorActive multicastProduction=$multicastProductionActive",
        )
    }

    fun logRollback(
        sessionId: String,
        trigger: RollbackTrigger,
        outcome: CutoverOutcome,
        detail: String? = null,
    ) {
        emit(
            "AUDIBLE_OWNERSHIP_ROLLBACK session=$sessionId trigger=$trigger outcome=$outcome" +
                (detail?.let { " detail=$it" } ?: ""),
        )
    }

    fun logRc1Field(
        outcome: String,
        sessionId: String,
        detail: String? = null,
    ) {
        emit(
            "RC1_FIELD outcome=$outcome session=$sessionId" +
                (detail?.let { " detail=$detail" } ?: ""),
        )
    }

    fun logOwnershipInvariantViolation(sessionId: String) {
        emit("OWNERSHIP_INVARIANT_VIOLATION session=$sessionId anchorAudibleActive=true multicastAudibleActive=true")
    }

    fun logAnchorAudibleProbe(
        sessionId: String,
        active: Boolean,
        reason: String,
    ) {
        emit("ANCHOR_AUDIBLE_PROBE session=$sessionId active=$active reason=$reason")
    }

    fun logMulticastAudibleProbe(
        sessionId: String,
        active: Boolean,
        successfulWrites: Long,
    ) {
        emit(
            "MULTICAST_AUDIBLE_PROBE session=$sessionId active=$active successfulProductionWrites=$successfulWrites",
        )
    }

    private fun emit(message: String) {
        try {
            Log.i(LOG_TAG, message)
        } catch (_: Throwable) {
        }
    }
}
