package com.talkback.core.conference.session.integration

/**
 * Publication obligation identity for MEDIA_KEY_PACKAGE (P1-C).
 *
 * Exactly-once applies to this identity, not to ciphertext bytes.
 */
data class MediaKeyPackagePublicationIdentity(
    val conferenceIdHex: String,
    val mediaKeyEpoch: Long,
    val recipientModuleId: String,
    val recipientEstablishmentKeyVersion: Long,
    val membershipFactDigestHex: String,
)

/**
 * Tracks PUBLISHED identities per session. BUILT != PUBLISHED.
 */
class MediaKeyPackagePublicationLedger {
    private val publishedBySession =
        java.util.concurrent.ConcurrentHashMap<String, MutableSet<MediaKeyPackagePublicationIdentity>>()

    fun isPublished(
        sessionId: String,
        identity: MediaKeyPackagePublicationIdentity,
    ): Boolean = publishedBySession[sessionId]?.contains(identity) == true

    fun markPublished(
        sessionId: String,
        identity: MediaKeyPackagePublicationIdentity,
    ) {
        publishedBySession
            .getOrPut(sessionId) { java.util.concurrent.ConcurrentHashMap.newKeySet() }
            .add(identity)
    }

    fun publishedIdentities(sessionId: String): Set<MediaKeyPackagePublicationIdentity> =
        publishedBySession[sessionId]?.toSet() ?: emptySet()

    fun clearSession(sessionId: String) {
        publishedBySession.remove(sessionId)
    }
}

enum class MediaKeyPackageOriginOutcome {
    EMITTED,
    REPLAYED,
    SKIPPED_NOT_AUTHORITY,
    SKIPPED_NOT_OWNER,
    SKIPPED_NO_CREATION,
    SKIPPED_NO_MATERIAL,
    SKIPPED_NO_PENDING_PUBLICATION,
    ORIGIN_SOURCE_GAP,
}

/** P1-C field C-F7 negative fixture emit outcome (separate from positive publication ledger). */
enum class MediaKeyPackageNegativeFixtureOutcome {
    EMITTED,
    SEND_FAILED,
    SKIPPED_NOT_AUTHORITY,
    SKIPPED_NOT_OWNER,
    SKIPPED_NO_CREATION,
    SKIPPED_NO_MATERIAL,
    SKIPPED_NO_BUILDER,
    SKIPPED_LOOKUP,
    BUILD_REJECTED,
    OVERRIDE_REJECTED,
}

