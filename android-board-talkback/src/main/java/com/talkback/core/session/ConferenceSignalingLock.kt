package com.talkback.core.session

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlinx.coroutines.runBlocking

data class ConferenceSignalKey(
    val sessionId: String,
    val peerId: String
)

/** ADR-0052 PR-C3: per-edge conference signaling mutation owner labels. */
enum class ConferenceSignalOwner {
    INITIAL_ANSWER,
    ICE_RESTART,
    NORMAL_NEGOTIATION,
    REMOTE_REATTACH,
    MESH_JOIN
}

/**
 * ADR-0052 PR-C3: one signaling mutation owner per CONFERENCE edge `(sessionId, peerId)`.
 * Protects offer/answer/commit/send only — not ICE gathering or network wait.
 */
class ConferenceSignalingLockRegistry(
    private val logSink: (String) -> Unit = { message ->
        android.util.Log.i("Talkback", message)
    }
) {
    private val locks = ConcurrentHashMap<ConferenceSignalKey, ReentrantLock>()
    private val holders = ConcurrentHashMap<ConferenceSignalKey, String>()

    suspend fun <T> withConferenceSignalLock(
        key: ConferenceSignalKey,
        owner: String,
        block: suspend () -> T
    ): T {
        val lock = locks.getOrPut(key) { ReentrantLock() }
        if (lock.isLocked && !lock.isHeldByCurrentThread) {
            logSink(
                "CONFERENCE_SIGNAL_LOCK_WAIT session=${key.sessionId} peer=${key.peerId} " +
                    "owner=$owner holder=${holders[key] ?: "UNKNOWN"}"
            )
        }
        val startedAtMs = System.currentTimeMillis()
        lock.lock()
        val outermost = lock.holdCount == 1
        if (outermost) {
            holders[key] = owner
            logSink(
                "CONFERENCE_SIGNAL_LOCK_ACQUIRE session=${key.sessionId} peer=${key.peerId} owner=$owner"
            )
        }
        return try {
            block()
        } finally {
            if (lock.holdCount == 1) {
                val durationMs = System.currentTimeMillis() - startedAtMs
                holders.remove(key)
                logSink(
                    "CONFERENCE_SIGNAL_LOCK_RELEASE session=${key.sessionId} peer=${key.peerId} " +
                        "owner=$owner durationMs=$durationMs"
                )
            }
            lock.unlock()
        }
    }

    fun <T> withConferenceSignalLockBlocking(
        key: ConferenceSignalKey,
        owner: ConferenceSignalOwner,
        block: () -> T
    ): T = runBlocking {
        withConferenceSignalLock(key, owner.name, block)
    }

    fun removeSession(sessionId: String) {
        locks.keys.removeIf { it.sessionId == sessionId }
        holders.keys.removeIf { it.sessionId == sessionId }
    }

    internal fun resetForTest() {
        locks.clear()
        holders.clear()
    }
}
