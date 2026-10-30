package com.talkback.core.session

/**
 * GROUP outbound trickle ICE hold. Queues local candidates only when the producing
 * PeerConnection identity is known and still live, then replays after LIVE GPLB binding.
 * Old generation/hash queues never migrate onto a new PC.
 */
class GroupOutboundIceHold(
    private val maxQueuedPerIdentity: Int = 64,
) {
    enum class Disposition {
        QUEUED,
        REPLAYED,
        STALE_DROPPED,
        DUPLICATE_DROPPED,
        IDENTITY_REJECTED,
        SEND_FAILED,
        SEND_NOW,
    }

    data class PcIdentity(
        val pcGeneration: Long,
        val pcHash: Int,
    )

    sealed class Gate {
        data class SendNow(val offerLineageId: String) : Gate()
        data object Hold : Gate()
        data class Drop(val disposition: Disposition) : Gate()
    }

    data class QueuedCandidate(
        val identity: PcIdentity,
        val candidate: String,
        val fingerprint: String,
    )

    private data class Key(
        val sessionId: String,
        val peer: String,
        val pcGeneration: Long,
        val pcHash: Int,
    )

    private val queues = LinkedHashMap<Key, MutableList<QueuedCandidate>>()
    private val delivered = HashSet<String>()

    @Synchronized
    fun gate(
        liveOfferLineageId: String?,
        identity: PcIdentity?,
        pcStillLive: Boolean,
        iceClosed: Boolean,
    ): Gate {
        if (!liveOfferLineageId.isNullOrBlank()) {
            return Gate.SendNow(liveOfferLineageId)
        }
        if (identity == null) {
            return Gate.Drop(Disposition.IDENTITY_REJECTED)
        }
        if (!pcStillLive || iceClosed) {
            return Gate.Drop(Disposition.STALE_DROPPED)
        }
        return Gate.Hold
    }

    @Synchronized
    fun enqueue(
        sessionId: String,
        peer: String,
        identity: PcIdentity,
        candidate: String,
    ): Disposition {
        val fingerprint = fingerprintOf(candidate)
        val deliveredKey = deliveredKey(sessionId, peer, identity, fingerprint)
        val key = keyOf(sessionId, peer, identity)
        val existing = queues[key]
        if (deliveredKey in delivered || existing?.any { it.fingerprint == fingerprint } == true) {
            return Disposition.DUPLICATE_DROPPED
        }
        if ((existing?.size ?: 0) >= maxQueuedPerIdentity) {
            return Disposition.SEND_FAILED
        }
        queues.getOrPut(key) { mutableListOf() }.add(
            QueuedCandidate(identity, candidate, fingerprint),
        )
        return Disposition.QUEUED
    }

    @Synchronized
    fun dropMismatched(
        sessionId: String,
        peer: String,
        liveIdentity: PcIdentity,
    ): List<QueuedCandidate> {
        val staleKeys = queues.keys.filter {
            it.sessionId == sessionId &&
                it.peer == peer &&
                (it.pcGeneration != liveIdentity.pcGeneration || it.pcHash != liveIdentity.pcHash)
        }
        return staleKeys.flatMap { queues.remove(it).orEmpty() }
    }

    @Synchronized
    fun takeForReplay(
        sessionId: String,
        peer: String,
        liveIdentity: PcIdentity,
    ): List<QueuedCandidate> {
        val key = keyOf(sessionId, peer, liveIdentity)
        val items = queues.remove(key).orEmpty()
        items.forEach { delivered.add(deliveredKey(sessionId, peer, liveIdentity, it.fingerprint)) }
        return items
    }

    @Synchronized
    fun invalidatePeer(sessionId: String, peer: String): List<QueuedCandidate> {
        val keys = queues.keys.filter { it.sessionId == sessionId && it.peer == peer }
        return keys.flatMap { queues.remove(it).orEmpty() }
    }

    @Synchronized
    fun invalidateSession(sessionId: String): List<QueuedCandidate> {
        val keys = queues.keys.filter { it.sessionId == sessionId }
        return keys.flatMap { queues.remove(it).orEmpty() }
    }

    @Synchronized
    fun queuedCount(sessionId: String, peer: String, identity: PcIdentity): Int =
        queues[keyOf(sessionId, peer, identity)]?.size ?: 0

    companion object {
        fun identity(pcGeneration: Long?, pcHash: Int?): PcIdentity? {
            if (pcGeneration == null || pcHash == null) return null
            return PcIdentity(pcGeneration, pcHash)
        }

        fun fingerprintOf(candidate: String): String = candidate.trim()

        private fun keyOf(sessionId: String, peer: String, identity: PcIdentity): Key =
            Key(sessionId, peer, identity.pcGeneration, identity.pcHash)

        private fun deliveredKey(
            sessionId: String,
            peer: String,
            identity: PcIdentity,
            fingerprint: String,
        ): String = "$sessionId|$peer|${identity.pcGeneration}|${identity.pcHash}|$fingerprint"
    }
}
