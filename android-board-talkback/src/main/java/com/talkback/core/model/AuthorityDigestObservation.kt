package com.talkback.core.model

/**
 * ADR-0036 Phase 2.4: provenance-bound authority digest observation.
 *
 * TopologyDigest alone is insufficient - a sticky cache entry can outlive the
 * membership generation that produced it. Observation metadata lets recovery
 * invalidate stale comparisons without weakening the convergence predicate.
 */
data class AuthorityDigestObservation(
    val channelId: String,
    val authorityId: String,
    val digest: TopologyDigest,
    val observedAtMs: Long,
    val source: AuthorityDigestSource,
    val membershipContext: String
)

enum class AuthorityDigestSource {
    HELLO,
    MEMBERSHIP_SNAPSHOT_APPLY,
    TEST_SEED
}

object AuthorityDigestFreshness {
    fun isStaleReplacement(previous: TopologyDigest?, next: TopologyDigest): Boolean =
        previous != null &&
            (previous.rosterEpoch != next.rosterEpoch || previous.memberHash != next.memberHash)

    fun formatInvalidated(
        channelId: String,
        previous: AuthorityDigestObservation,
        next: AuthorityDigestObservation
    ): String =
        "STALE_AUTHORITY_DIGEST_INVALIDATED channel=$channelId " +
            "previousEpoch=${previous.digest.rosterEpoch} previousHash=${previous.digest.memberHash} " +
            "previousSource=${previous.source.name} previousContext=${previous.membershipContext} " +
            "previousAuthority=${previous.authorityId} " +
            "newEpoch=${next.digest.rosterEpoch} newHash=${next.digest.memberHash} " +
            "newSource=${next.source.name} newContext=${next.membershipContext} " +
            "newAuthority=${next.authorityId}"
}