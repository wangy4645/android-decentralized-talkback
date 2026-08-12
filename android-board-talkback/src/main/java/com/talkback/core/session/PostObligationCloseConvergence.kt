package com.talkback.core.session

/**
 * #175 post-obligation close convergence: fresh snapshot read + observability markers.
 * Admission actions (successor / residency clear) remain separate policy writers.
 */
internal object PostObligationCloseConvergence {

    fun readEdgeSnapshot(
        record: EdgeRecoveryRecord,
        lifecycleActive: Boolean,
        iceConnected: Boolean,
        receivePathLive: Boolean,
        mediaUnavailable: Boolean,
        membership: String
    ): PostObligationCloseEdgeSnapshot =
        PostObligationCloseEdgeSnapshot(
            sessionId = record.key.sessionId,
            remoteModuleId = record.key.remoteModuleId,
            obligationGen = record.obligationGeneration,
            attempt = record.recoveryAttemptId,
            iceConnected = iceConnected,
            receivePathLive = receivePathLive,
            mediaUnavailable = mediaUnavailable,
            phase = record.phase,
            lifecycleActive = lifecycleActive,
            membership = membership
        )

    fun logPostObligationCloseEval(
        log: (String) -> Unit,
        snapshot: PostObligationCloseEdgeSnapshot
    ) {
        log(
            "RECOVERY_POST_OBLIGATION_CLOSE_EVAL session=${snapshot.sessionId} " +
                "edge=${snapshot.remoteModuleId} obligationGen=${snapshot.obligationGen} " +
                "attempt=${snapshot.attempt} iceConnected=${snapshot.iceConnected} " +
                "receivePathLive=${snapshot.receivePathLive} " +
                "mediaUnavailable=${snapshot.mediaUnavailable} phase=${snapshot.phase} " +
                "lifecycleActive=${snapshot.lifecycleActive} membership=${snapshot.membership} " +
                "result=${snapshot.result}"
        )
    }

    fun logPostCloseAdmissionDecision(
        log: (String) -> Unit,
        snapshot: PostObligationCloseEdgeSnapshot,
        decision: PostObligationCloseAdmissionOutcome,
        reason: String,
        trigger: String
    ) {
        log(
            "RECOVERY_POST_CLOSE_ADMISSION_DECISION session=${snapshot.sessionId} " +
                "edge=${snapshot.remoteModuleId} decision=$decision reason=$reason trigger=$trigger"
        )
    }
}
