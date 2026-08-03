package com.talkback.core.session

/**
 * ADR-0022 E.18.2: resolver-backed membership epoch probe for production Coordinator wiring.
 *
 * Maps resolver outcomes to explicit probe results:
 * - authority digest missing (non-authority local) → [MembershipEpochProbeResult.Unwired]
 * - authority answered with epoch/hash mismatch → [MembershipEpochProbeResult.Checked] converged=false
 */
internal class WiredMembershipEpochProbe(
    private val resolver: MembershipAuthorityResolver,
    private val resolveContext: (channelId: String, conferenceSessionId: String) -> RecoveryMembershipContext?,
    private val resolveAuthorityId: (RecoveryMembershipContext) -> String,
    private val onResolveTrace: (MembershipAuthorityResolveOutcome) -> Unit = {}
) : MembershipEpochConvergenceProbe {

    override fun probe(
        record: EdgeRecoveryRecord,
        channelId: String,
        conferenceSessionId: String
    ): MembershipEpochProbeResult {
        val context = resolveContext(channelId, conferenceSessionId)
            ?: return MembershipEpochProbeResult.Unwired("MEMBERSHIP_CONTEXT_UNAVAILABLE")
        val outcome = resolver.evaluateMembershipConvergence(context, conferenceSessionId)
        onResolveTrace(outcome)
        if (!context.isLocalMembershipAuthority && outcome.authorityDigest == null) {
            return MembershipEpochProbeResult.Unwired(outcome.reason)
        }
        val localEpoch = context.localMembershipView.rosterEpoch
        val authorityEpoch = outcome.authorityDigest?.rosterEpoch ?: localEpoch
        return MembershipEpochProbeResult.Checked(
            authorityId = resolveAuthorityId(context),
            expectedEpoch = authorityEpoch,
            observedEpoch = localEpoch,
            converged = outcome.converged
        )
    }
}