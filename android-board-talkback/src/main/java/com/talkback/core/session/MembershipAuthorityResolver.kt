package com.talkback.core.session

import com.talkback.core.model.TopologyDigest

/** ADR-0022 Q7-2: recovery membership convergence context (channel authority domain). */
data class RecoveryMembershipContext(
    val channelId: String,
    val conferenceSessionId: String?,
    val localMembershipView: TopologyDigest,
    val isLocalMembershipAuthority: Boolean = false
)

/**
 * ADR-0022 Q7: sole producer of `membershipEpochConverged` for control reconciliation.
 * Consumes authority observation facts only (INV-Q7-004); does not own topology state.
 */
interface MembershipAuthorityResolver {

    fun resolveAuthorityDigest(channelId: String): TopologyDigest?

    fun isMembershipEpochConverged(context: RecoveryMembershipContext): Boolean

    /** Q7-DIAG-0: same predicate as [isMembershipEpochConverged] with trace-ready outcome. */
    fun evaluateMembershipConvergence(
        context: RecoveryMembershipContext,
        localGroupSessionId: String?
    ): MembershipAuthorityResolveOutcome
}

/** Q7-3-A: reads `lastSeenAuthorityDigestByChannel` observation cache (INV-Q7-005). */
class DefaultMembershipAuthorityResolver(
    private val readAuthorityDigest: (String) -> TopologyDigest?
) : MembershipAuthorityResolver {

    override fun resolveAuthorityDigest(channelId: String): TopologyDigest? =
        readAuthorityDigest(channelId)

    override fun isMembershipEpochConverged(context: RecoveryMembershipContext): Boolean =
        evaluateMembershipConvergence(context, null).converged

    override fun evaluateMembershipConvergence(
        context: RecoveryMembershipContext,
        localGroupSessionId: String?
    ): MembershipAuthorityResolveOutcome {
        val resolverImpl = this::class.simpleName ?: "MembershipAuthorityResolver"
        val local = context.localMembershipView
        val authorityDigest = resolveAuthorityDigest(context.channelId)
        val reason: String
        val converged: Boolean = when {
            context.isLocalMembershipAuthority -> {
                reason = "LOCAL_IS_MEMBERSHIP_AUTHORITY"
                true
            }
            authorityDigest == null -> {
                reason = "AUTHORITY_DIGEST_MISSING"
                false
            }
            local.rosterEpoch != authorityDigest.rosterEpoch -> {
                reason = "EPOCH_MISMATCH"
                false
            }
            local.memberHash != authorityDigest.memberHash -> {
                reason = "HASH_MISMATCH"
                false
            }
            else -> {
                reason = "ALIGNED"
                true
            }
        }
        return MembershipAuthorityResolveOutcome(
            channelId = context.channelId,
            conferenceSessionId = context.conferenceSessionId,
            resolverImpl = resolverImpl,
            authorityDigestSource = MembershipAuthorityResolveTrace.SOURCE_LAST_SEEN_AUTHORITY_DIGEST,
            localGroupSessionId = localGroupSessionId,
            localDigest = local,
            authorityDigest = authorityDigest,
            converged = converged,
            reason = reason
        )
    }
}