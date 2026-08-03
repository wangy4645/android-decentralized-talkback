package com.talkback.core.session

import com.talkback.core.model.TopologyDigest

/** Q7-DIAG-0: observation-only membership authority resolve trace (ADR-0022 Q7). */
data class MembershipAuthorityResolveOutcome(
    val channelId: String,
    val conferenceSessionId: String?,
    val resolverImpl: String,
    val authorityDigestSource: String,
    val localGroupSessionId: String?,
    val localDigest: TopologyDigest?,
    val authorityDigest: TopologyDigest?,
    val converged: Boolean,
    val reason: String
)

object MembershipAuthorityResolveTrace {

    const val LOG_PREFIX = "MEMBERSHIP_AUTHORITY_RESOLVE_TRACE"
    const val SOURCE_LAST_SEEN_AUTHORITY_DIGEST = "lastSeenAuthorityDigestByChannel"

    fun format(outcome: MembershipAuthorityResolveOutcome): String {
        val local = outcome.localDigest
        val authority = outcome.authorityDigest
        return buildString {
            append(LOG_PREFIX)
            append(" channelId=").append(outcome.channelId)
            append(" conferenceSessionId=").append(outcome.conferenceSessionId ?: "null")
            append(" resolverImpl=").append(outcome.resolverImpl)
            append(" authorityDigestSource=").append(outcome.authorityDigestSource)
            append(" localGroupSessionId=").append(outcome.localGroupSessionId ?: "null")
            append(" localEpoch=").append(local?.rosterEpoch ?: "null")
            append(" authorityEpoch=").append(authority?.rosterEpoch ?: "null")
            append(" localHash=").append(local?.memberHash ?: "null")
            append(" authorityHash=").append(authority?.memberHash ?: "null")
            append(" result=").append(outcome.converged)
            append(" reason=").append(outcome.reason)
        }
    }

    fun emit(sink: (String) -> Unit, outcome: MembershipAuthorityResolveOutcome) {
        sink(format(outcome))
    }
}
