package com.talkback.core.conference.session.integration

/**
 * Tracks post-bind MEDIA_KEY_PACKAGE delivery completion per publication identity (P1-C-R1).
 *
 * Separate from [MediaKeyPackagePublicationLedger]: initial transport send accepted
 * does not imply the recipient could consume the package.
 */
class MediaKeyPackagePostBindPublicationLedger {
    private val deliveredBySession =
        java.util.concurrent.ConcurrentHashMap<String, MutableSet<MediaKeyPackagePublicationIdentity>>()

    fun isPostBindDelivered(
        sessionId: String,
        identity: MediaKeyPackagePublicationIdentity,
    ): Boolean = deliveredBySession[sessionId]?.contains(identity) == true

    fun markPostBindDelivered(
        sessionId: String,
        identity: MediaKeyPackagePublicationIdentity,
    ) {
        deliveredBySession
            .getOrPut(sessionId) { java.util.concurrent.ConcurrentHashMap.newKeySet() }
            .add(identity)
    }

    fun deliveredIdentities(sessionId: String): Set<MediaKeyPackagePublicationIdentity> =
        deliveredBySession[sessionId]?.toSet() ?: emptySet()

    fun clearSession(sessionId: String) {
        deliveredBySession.remove(sessionId)
    }
}

enum class MediaKeyPackagePostBindRepublishOutcome {
    EMITTED,
    SKIPPED_ALREADY_DELIVERED,
    SKIPPED_NOT_AUTHORITY,
    SKIPPED_NOT_OWNER,
    SKIPPED_NO_CREATION,
    SKIPPED_NO_MATERIAL,
    SKIPPED_NO_BUILDER,
    SKIPPED_NO_PRE_BIND_PUBLICATION,
    SKIPPED_LOOKUP,
    SEND_FAILED,
    BUILD_REJECTED,
}
