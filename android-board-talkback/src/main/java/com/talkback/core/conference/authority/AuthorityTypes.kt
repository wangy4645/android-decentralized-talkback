package com.talkback.core.conference.authority

import com.talkback.core.conference.wire.WireKeyContext
import com.talkback.core.conference.wire.WireSourceBinding

/**
 * Already-verified Profile 01 facts (E2b-07).
 * Cryptographic Fact verification is NOT claimed here — injectable seam only.
 */
data class SourceAuthorizationFact(
    val sourceIdentity: String,
    val incarnationId: Long,
    val ssrc: Int,
    val sourceAdmissionKey48: ByteArray,
    val mediaKeyEpoch: Long,
    val current: Boolean = true,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SourceAuthorizationFact) return false
        return sourceIdentity == other.sourceIdentity &&
            incarnationId == other.incarnationId &&
            ssrc == other.ssrc &&
            mediaKeyEpoch == other.mediaKeyEpoch &&
            current == other.current &&
            sourceAdmissionKey48.contentEquals(other.sourceAdmissionKey48)
    }

    override fun hashCode(): Int {
        var r = sourceIdentity.hashCode()
        r = 31 * r + incarnationId.hashCode()
        r = 31 * r + ssrc
        r = 31 * r + sourceAdmissionKey48.contentHashCode()
        r = 31 * r + mediaKeyEpoch.hashCode()
        r = 31 * r + current.hashCode()
        return r
    }
}

data class MediaKeyContextFact(
    val mediaKeyEpoch: Long,
    val masterKey: ByteArray,
    val masterSalt: ByteArray,
    val keyContextHint64: ByteArray,
    val current: Boolean = true,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MediaKeyContextFact) return false
        return mediaKeyEpoch == other.mediaKeyEpoch &&
            current == other.current &&
            masterKey.contentEquals(other.masterKey) &&
            masterSalt.contentEquals(other.masterSalt) &&
            keyContextHint64.contentEquals(other.keyContextHint64)
    }

    override fun hashCode(): Int {
        var r = mediaKeyEpoch.hashCode()
        r = 31 * r + masterKey.contentHashCode()
        r = 31 * r + masterSalt.contentHashCode()
        r = 31 * r + keyContextHint64.contentHashCode()
        r = 31 * r + current.hashCode()
        return r
    }
}

/** Derived P02 capability — not a P01 authority fact. */
data class DerivedWireCapability(
    val mediaKeyEpoch: Long,
    val key: WireKeyContext,
    val binding: WireSourceBinding,
    val sourceIdentity: String,
    val incarnationId: Long,
)

/**
 * C-E2B07-01: injectable verified-fact seam.
 * MUST NOT accept packet-derived data as verified.
 */
interface VerifiedFactSeam {
    fun installSource(fact: SourceAuthorizationFact)
    fun installKey(fact: MediaKeyContextFact)
    fun currentSources(): List<SourceAuthorizationFact>
    fun currentKeys(): List<MediaKeyContextFact>
}

class InjectableVerifiedFactSeam : VerifiedFactSeam {
    private val sources = linkedMapOf<String, SourceAuthorizationFact>()
    private val keys = linkedMapOf<Long, MediaKeyContextFact>()

    override fun installSource(fact: SourceAuthorizationFact) {
        sources[fact.sourceIdentity] = fact
    }

    override fun installKey(fact: MediaKeyContextFact) {
        keys[fact.mediaKeyEpoch] = fact
    }

    override fun currentSources(): List<SourceAuthorizationFact> =
        sources.values.filter { it.current }

    override fun currentKeys(): List<MediaKeyContextFact> =
        keys.values.filter { it.current }

    fun allSources(): Map<String, SourceAuthorizationFact> = sources.toMap()

    fun allKeys(): Map<Long, MediaKeyContextFact> = keys.toMap()

    fun markSourceNonCurrent(sourceIdentity: String, incarnationId: Long) {
        val cur = sources[sourceIdentity] ?: return
        if (cur.incarnationId == incarnationId) {
            sources[sourceIdentity] = cur.copy(current = false)
        }
    }

    fun markKeyNonCurrent(mediaKeyEpoch: Long) {
        val cur = keys[mediaKeyEpoch] ?: return
        keys[mediaKeyEpoch] = cur.copy(current = false)
    }
}
