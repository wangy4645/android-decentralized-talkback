package com.talkback.core.session

/**
 * ADR-0022 Appendix E.18: membership epoch authority probe for control reconciliation.
 *
 * DefaultOpenMembershipAuthoritySentinel is compile-closure only; not wired authority.
 * PR-D replaces the default with a resolver-backed probe.
 */
internal interface MembershipEpochConvergenceProbe {

    fun isConverged(channelId: String, conferenceSessionId: String): Boolean

    fun emitUnwiredObservationIfNeeded(
        record: EdgeRecoveryRecord,
        channelId: String,
        conferenceSessionId: String,
        onLog: (String) -> Unit
    ) = Unit
}

/**
 * ADR-0022 E.18.1: named default-open sentinel replacing anonymous default-open lambda.
 * Behavior unchanged (returns true); emits auditable unwired fact per evaluation.
 */
internal object DefaultOpenMembershipAuthoritySentinel : MembershipEpochConvergenceProbe {

    override fun isConverged(channelId: String, conferenceSessionId: String): Boolean = true

    override fun emitUnwiredObservationIfNeeded(
        record: EdgeRecoveryRecord,
        channelId: String,
        conferenceSessionId: String,
        onLog: (String) -> Unit
    ) {
        onLog(formatUnwiredFact(record, channelId, conferenceSessionId))
    }

    internal fun formatUnwiredFact(
        record: EdgeRecoveryRecord,
        channelId: String,
        conferenceSessionId: String
    ): String =
        "CONTROL_RECONCILIATION_MEMBERSHIP_UNWIRED " +
            "session=${record.key.sessionId} remote=${record.key.remoteModuleId} " +
            "channelId=$channelId conferenceSessionId=$conferenceSessionId " +
            "recoveryAttemptId=${record.recoveryAttemptId} " +
            "obligationGeneration=${record.obligationGeneration}"
}
