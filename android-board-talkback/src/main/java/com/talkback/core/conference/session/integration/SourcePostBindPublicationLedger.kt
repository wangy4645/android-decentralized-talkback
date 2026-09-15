package com.talkback.core.conference.session.integration

import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks post-bind SOURCE consumption delivery per publication identity (B1-R2).
 *
 * Separate from [SourcePublicationLedger]: initial transport send accepted
 * does not imply the recipient could consume the SOURCE fact.
 */
class SourcePostBindPublicationLedger {
    private val satisfiedBySession =
        ConcurrentHashMap<String, MutableSet<SourcePostBindPublicationIdentity>>()

    fun isSatisfied(
        sessionId: String,
        identity: SourcePostBindPublicationIdentity,
    ): Boolean = satisfiedBySession[sessionId]?.contains(identity) == true

    fun markSatisfied(
        sessionId: String,
        identity: SourcePostBindPublicationIdentity,
    ) {
        satisfiedBySession
            .getOrPut(sessionId) { ConcurrentHashMap.newKeySet() }
            .add(identity)
    }

    fun satisfiedIdentities(sessionId: String): Set<SourcePostBindPublicationIdentity> =
        satisfiedBySession[sessionId]?.toSet() ?: emptySet()

    fun clearSession(sessionId: String) {
        satisfiedBySession.remove(sessionId)
    }
}

enum class SourcePostBindRepublishOutcome {
    EMITTED,
    ALREADY_SATISFIED,
    SKIPPED_NO_BUILT_SOURCE,
    SKIPPED_NO_PRE_BIND_PUBLICATION,
    SKIPPED_NOT_SELF_ORIGIN,
    SKIPPED_NOT_IN_TOPOLOGY,
    SKIPPED_STALE_IDENTITY,
    SKIPPED_STALE_MEMBERSHIP_CONTEXT,
    SEND_FAILED,
}
