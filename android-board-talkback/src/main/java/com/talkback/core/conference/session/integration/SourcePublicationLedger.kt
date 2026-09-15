package com.talkback.core.conference.session.integration

import java.util.concurrent.ConcurrentHashMap

data class SourcePublicationIdentity(
    val conferenceIdHex: String,
    val sourceGeneration: Long,
    val factDigestHex: String,
    val peerModuleId: String,
)

/**
 * Per-peer SOURCE publication ledger keyed by generation + fact digest (B1-6 byte-identical replay).
 */
class SourcePublicationLedger {
    private val published =
        ConcurrentHashMap<String, MutableSet<SourcePublicationIdentity>>()

    fun isPublished(
        sessionId: String,
        identity: SourcePublicationIdentity,
    ): Boolean = published[sessionId]?.contains(identity) == true

    fun markPublished(
        sessionId: String,
        identity: SourcePublicationIdentity,
    ) {
        published.getOrPut(sessionId) { ConcurrentHashMap.newKeySet() }.add(identity)
    }

    fun publishedIdentities(sessionId: String): Set<SourcePublicationIdentity> =
        published[sessionId]?.toSet() ?: emptySet()

    fun clearSession(sessionId: String) {
        published.remove(sessionId)
    }
}
