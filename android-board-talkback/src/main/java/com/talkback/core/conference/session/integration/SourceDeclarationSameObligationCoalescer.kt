package com.talkback.core.conference.session.integration

import com.talkback.core.conference.session.profile01.wire.Profile01WireCborDecoder
import com.talkback.core.conference.session.profile01.wire.Profile01WireConstants
import java.util.concurrent.ConcurrentHashMap

/**
 * SOURCE_FACT_9_SAME_OBLIGATION_COALESCING — pending/in-flight occupancy only.
 *
 * Key: session + peer + membershipVersion + mediaKeyEpoch + factDigest.
 * Occupied while a coordinator task for that identity is queued or running.
 * Release on END so a later legal trigger can POST again. Not a permanent digest
 * dedupe: a first pass that fails (e.g. NOT_IN_ROSTER) must remain retryable.
 */
class SourceDeclarationSameObligationCoalescer {
    data class Key(
        val sessionId: String,
        val peerModuleId: String,
        val membershipVersion: Long,
        val mediaKeyEpoch: Long,
        val factDigestHex: String,
    )

    private data class State(
        var occupied: Boolean = false,
        var coalescedCount: Long = 0L,
        var coalescedLogged: Boolean = false,
    )

    private val byKey = ConcurrentHashMap<Key, State>()

    @Synchronized
    fun tryOccupy(key: Key): Boolean {
        val state = byKey.getOrPut(key) { State() }
        if (state.occupied) {
            state.coalescedCount += 1
            return false
        }
        state.occupied = true
        return true
    }

    @Synchronized
    fun release(key: Key): Long {
        val state = byKey[key] ?: return 0L
        val skipped = state.coalescedCount
        state.occupied = false
        state.coalescedCount = 0L
        state.coalescedLogged = false
        if (skipped == 0L) {
            byKey.remove(key, state)
        }
        return skipped
    }

    @Synchronized
    fun shouldLogCoalesced(key: Key): Boolean {
        val state = byKey[key] ?: return false
        if (state.coalescedLogged) return false
        if (state.coalescedCount <= 0) return false
        state.coalescedLogged = true
        return true
    }

    @Synchronized
    fun coalescedCount(key: Key): Long = byKey[key]?.coalescedCount ?: 0L

    @Synchronized
    fun isOccupied(key: Key): Boolean = byKey[key]?.occupied == true

    @Synchronized
    fun clearSession(sessionId: String) {
        byKey.keys.removeIf { it.sessionId == sessionId }
    }

    companion object {
        fun keyOrNull(
            sessionId: String,
            peerModuleId: String,
            observed: Profile01WireCborDecoder.ObserveIdentity?,
        ): Key? {
            if (observed == null) return null
            if (observed.factType != Profile01WireConstants.FACT_TYPE_SOURCE_DECLARATION) return null
            val membershipVersion = observed.membershipVersion ?: return null
            val mediaKeyEpoch = observed.mediaKeyEpoch ?: return null
            if (observed.factDigestHex.isBlank()) return null
            return Key(
                sessionId = sessionId,
                peerModuleId = peerModuleId,
                membershipVersion = membershipVersion,
                mediaKeyEpoch = mediaKeyEpoch,
                factDigestHex = observed.factDigestHex,
            )
        }
    }
}
