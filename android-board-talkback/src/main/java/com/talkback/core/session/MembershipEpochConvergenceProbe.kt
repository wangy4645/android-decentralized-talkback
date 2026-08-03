package com.talkback.core.session

/**
 * ADR-0022 Appendix E.18: membership epoch authority probe for control reconciliation.
 *
 * Production Coordinator wires [WiredMembershipEpochProbe]; tests may inject fakes.
 */
internal interface MembershipEpochConvergenceProbe {

    fun probe(
        record: EdgeRecoveryRecord,
        channelId: String,
        conferenceSessionId: String
    ): MembershipEpochProbeResult
}

/**
 * ADR-0022 E.18.1: compile-closure sentinel when no wired probe is injected.
 * Returns [MembershipEpochProbeResult.Unwired] — does not claim membership checked.
 */
internal object DefaultOpenMembershipAuthoritySentinel : MembershipEpochConvergenceProbe {

    override fun probe(
        record: EdgeRecoveryRecord,
        channelId: String,
        conferenceSessionId: String
    ): MembershipEpochProbeResult =
        MembershipEpochProbeResult.Unwired("DEFAULT_OPEN_SENTINEL_NOT_WIRED")
}