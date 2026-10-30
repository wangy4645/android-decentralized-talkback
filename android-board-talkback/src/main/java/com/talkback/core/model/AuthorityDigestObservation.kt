package com.talkback.core.model

/**
 * ADR-0036 Phase 2.4: provenance-bound authority digest observation.
 *
 * TopologyDigest alone is insufficient - a sticky cache entry can outlive the
 * membership generation that produced it. Observation metadata lets recovery
 * invalidate stale comparisons without weakening the convergence predicate.
 *
 * GROUP and CONFERENCE observations are stored per [plane]; their rosterEpoch
 * values are not comparable across planes.
 */
data class AuthorityDigestObservation(
    val channelId: String,
    val authorityId: String,
    val digest: TopologyDigest,
    val observedAtMs: Long,
    val source: AuthorityDigestSource,
    val membershipContext: String,
    val plane: MembershipAuthorityPlane,
    val sessionFamilyId: String? = null
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

    /**
     * Cross-plane and cross-session-family digests are not comparable.
     * Callers must still write same-plane observations into the matching slot;
     * this predicate only decides whether the prior observation was a stale
     * generation of the *same* plane and session family.
     */
    fun isStaleReplacement(
        previous: TopologyDigest?,
        next: TopologyDigest,
        previousPlane: MembershipAuthorityPlane?,
        nextPlane: MembershipAuthorityPlane,
        previousSessionFamilyId: String? = null,
        nextSessionFamilyId: String? = null
    ): Boolean {
        if (previous == null) return false
        if (previousPlane != null && previousPlane != nextPlane) return false
        if (!previousSessionFamilyId.isNullOrBlank() &&
            !nextSessionFamilyId.isNullOrBlank() &&
            previousSessionFamilyId != nextSessionFamilyId
        ) {
            return false
        }
        return isStaleReplacement(previous, next)
    }

    fun formatInvalidated(
        channelId: String,
        previous: AuthorityDigestObservation,
        next: AuthorityDigestObservation
    ): String =
        "STALE_AUTHORITY_DIGEST_INVALIDATED channel=$channelId " +
            "previousEpoch=${previous.digest.rosterEpoch} previousHash=${previous.digest.memberHash} " +
            "previousSource=${previous.source.name} previousContext=${previous.membershipContext} " +
            "previousAuthority=${previous.authorityId} previousPlane=${previous.plane.name} " +
            "previousFamily=${previous.sessionFamilyId ?: "-"} " +
            "newEpoch=${next.digest.rosterEpoch} newHash=${next.digest.memberHash} " +
            "newSource=${next.source.name} newContext=${next.membershipContext} " +
            "newAuthority=${next.authorityId} newPlane=${next.plane.name} " +
            "newFamily=${next.sessionFamilyId ?: "-"}"
}
