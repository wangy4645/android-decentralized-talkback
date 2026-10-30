package com.talkback.core.conference.session.integration

import java.util.concurrent.ConcurrentHashMap

/**
 * Peer-side pending READY ACK. Survives a single [notifySupplementReady] and retries
 * after CHANNEL_TEXT rate-limit / send failure. Local send success is not Host ACKED.
 */
class Profile01PeerHeadSupplementAckPending {
    data class Pending(
        val snapshot: Profile01AuthoritativeHeadSnapshot,
        val peerModuleId: String,
        val attemptCount: Int = 0,
        val lastAttemptMs: Long? = null,
    )

    private val bySession = ConcurrentHashMap<String, Pending>()

    fun noteInstalled(snapshot: Profile01AuthoritativeHeadSnapshot, peerModuleId: String): Pending {
        val pending = Pending(snapshot = snapshot, peerModuleId = peerModuleId)
        bySession[snapshot.sessionId] = pending
        return pending
    }

    fun current(sessionId: String): Pending? = bySession[sessionId]

    fun markAttempt(sessionId: String, nowMs: Long): Pending? {
        val current = bySession[sessionId] ?: return null
        val updated =
            current.copy(
                attemptCount = current.attemptCount + 1,
                lastAttemptMs = nowMs,
            )
        bySession[sessionId] = updated
        return updated
    }

    fun replaceIfNewerHead(snapshot: Profile01AuthoritativeHeadSnapshot, peerModuleId: String): Pending {
        val existing = bySession[snapshot.sessionId]
        if (existing != null && existing.snapshot.headDigest == snapshot.headDigest) {
            return existing
        }
        return noteInstalled(snapshot, peerModuleId)
    }

    fun dropUnlessCurrentHeadReady(
        sessionId: String,
        liveHeadDigest: String?,
        currentHeadSupplementInstalled: Boolean,
    ): Pending? {
        val current = bySession[sessionId] ?: return null
        if (!currentHeadSupplementInstalled ||
            liveHeadDigest == null ||
            current.snapshot.headDigest != liveHeadDigest
        ) {
            bySession.remove(sessionId)
            return null
        }
        return current
    }

    fun clearSession(sessionId: String) {
        bySession.remove(sessionId)
    }
}
