package com.talkback.core.util

import com.talkback.core.session.EdgeRecoveryRecord
import com.talkback.core.session.MembershipEpochProbeResult

/** ADR-0022 E.18: observation-only membership probe facts for control reconciliation. */
internal object RecoveryControlReconciliationMembershipObservation {

    fun formatUnwired(
        record: EdgeRecoveryRecord,
        channelId: String?,
        conferenceSessionId: String,
        reason: String
    ): String =
        "CONTROL_RECONCILIATION_MEMBERSHIP_UNWIRED " +
            "session=${record.key.sessionId} remote=${record.key.remoteModuleId} " +
            "channelId=${channelId ?: "null"} conferenceSessionId=$conferenceSessionId " +
            "recoveryAttemptId=${record.recoveryAttemptId} " +
            "obligationGeneration=${record.obligationGeneration} " +
            "reason=$reason"

    fun formatChecked(
        record: EdgeRecoveryRecord,
        checked: MembershipEpochProbeResult.Checked
    ): String =
        "CONTROL_RECONCILIATION_MEMBERSHIP_CHECKED " +
            "session=${record.key.sessionId} remote=${record.key.remoteModuleId} " +
            "authorityId=${checked.authorityId} " +
            "expectedEpoch=${checked.expectedEpoch} observedEpoch=${checked.observedEpoch} " +
            "converged=${checked.converged} " +
            "recoveryAttemptId=${record.recoveryAttemptId} " +
            "obligationGeneration=${record.obligationGeneration}"
}