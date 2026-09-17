package com.talkback.core.conference.session.integration.cutover

import java.util.concurrent.ConcurrentHashMap

/**
 * Execution-layer latch for anchor RX suspension during RC1 cutover.
 * Authority remains [AudibleOwnershipController]; this registry only materializes suspend/clear.
 */
internal object ReplacementCutoverAnchorSuspensionRegistry {
    private val suspendedSessions = ConcurrentHashMap.newKeySet<String>()

    fun suspend(sessionId: String) {
        suspendedSessions.add(sessionId)
    }

    fun restore(sessionId: String) {
        suspendedSessions.remove(sessionId)
    }

    fun clearOnSessionTeardown(sessionId: String) {
        suspendedSessions.remove(sessionId)
    }

    fun contains(sessionId: String): Boolean = suspendedSessions.contains(sessionId)
}
