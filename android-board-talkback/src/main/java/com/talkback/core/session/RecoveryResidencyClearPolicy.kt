package com.talkback.core.session

/**
 * ADR-0045: sole writer for post-obligation failed-media residency exit.
 *
 * Orthogonal to [RecoveryCompletionPolicy.markRecovered] (ADR-0038 completion success).
 * Shares Recovery authority boundary / [RecoveryCompletionPolicy.MutationHost]; not lifecycle meaning.
 */
internal object RecoveryResidencyClearPolicy {

    /**
     * Admit clear when GATE + snapshot E4 hold.
     *
     * @param iceConnected snapshot [iceState] CONNECTED (not an event requirement)
     * @param receivePathLive snapshot media receive-path fact (not an event requirement)
     * @return true iff phase transitioned [EdgeRecoveryPhase.FAILED_MEDIA_RECOVERY] → [EdgeRecoveryPhase.CONNECTED]
     */
    fun clearFailedMediaResidencyPostObligation(
        host: RecoveryCompletionPolicy.MutationHost,
        record: EdgeRecoveryRecord,
        iceConnected: Boolean,
        receivePathLive: Boolean
    ): Boolean {
        val key = record.key
        val current = host.currentRecord(key) ?: return false
        if (
            record.recoveryAttemptId != current.recoveryAttemptId ||
            record.obligationGeneration != current.obligationGeneration
        ) {
            host.log(
                "FAILED_MEDIA_RESIDENCY_CLEAR_HELD session=${key.sessionId} remote=${key.remoteModuleId} " +
                    "reason=stale_lineage factAttempt=${record.recoveryAttemptId} " +
                    "factGen=${record.obligationGeneration} currentAttempt=${current.recoveryAttemptId} " +
                    "currentGen=${current.obligationGeneration}"
            )
            return false
        }
        if (current.phase != EdgeRecoveryPhase.FAILED_MEDIA_RECOVERY) {
            host.log(
                "FAILED_MEDIA_RESIDENCY_CLEAR_HELD session=${key.sessionId} remote=${key.remoteModuleId} " +
                    "reason=phase_not_failed_media_recovery phase=${current.phase}"
            )
            return false
        }
        if (current.obligationClosedAtMs == null) {
            host.log(
                "FAILED_MEDIA_RESIDENCY_CLEAR_HELD session=${key.sessionId} remote=${key.remoteModuleId} " +
                    "reason=obligation_still_open phase=${current.phase}"
            )
            return false
        }
        // Successor / supersede leaves FAILED_MEDIA_RECOVERY; phase gate above is the no-successor check.
        if (!iceConnected || !receivePathLive) {
            host.log(
                "FAILED_MEDIA_RESIDENCY_CLEAR_HELD session=${key.sessionId} remote=${key.remoteModuleId} " +
                    "reason=e4_snapshot_unsatisfied iceConnected=$iceConnected " +
                    "receivePathLive=$receivePathLive closeReason=${current.obligationCloseReason}"
            )
            return false
        }

        val closeReasonBefore = current.obligationCloseReason
        val oldPhase = current.phase
        current.phase = EdgeRecoveryPhase.CONNECTED
        host.logPhaseTransition(
            current,
            oldPhase,
            current.phase,
            "FAILED_MEDIA_RESIDENCY_CLEARED"
        )
        host.log(
            "FAILED_MEDIA_RESIDENCY_CLEARED session=${key.sessionId} remote=${key.remoteModuleId} " +
                "attempt=${current.recoveryAttemptId} obligationGen=${current.obligationGeneration} " +
                "iceConnected=$iceConnected receivePathLive=$receivePathLive " +
                "closeReason=$closeReasonBefore writer=ResidencyClearPolicy"
        )
        host.notifyAttemptLineageObservation(current, "failed_media_residency_cleared")
        host.notifyChanged(key.sessionId)
        return true
    }
}
