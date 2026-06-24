package com.talkback.core.session

import com.talkback.core.qos.IceConnectivity
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-channel peer mesh reconnect governor: backoff, ICE jitter suppression, and intent coalescing.
 */
class GroupMeshReconciler {
    enum class PeerMeshState {
        IDLE,
        INVITING,
        CONNECTING,
        CONNECTED,
        BACKOFF
    }

    data class PeerRecord(
        var state: PeerMeshState = PeerMeshState.IDLE,
        var lastAttemptMs: Long = 0L,
        var backoffMs: Long = INITIAL_BACKOFF_MS,
        var consecutiveFailures: Int = 0,
        /** When ICE entered CHECKING/NEW; 0 = not currently settling. */
        var checkingSinceMs: Long = 0L
    )

    private val peersByChannel = ConcurrentHashMap<String, ConcurrentHashMap<String, PeerRecord>>()

    fun peerState(channelId: String, peerModuleId: String): PeerMeshState =
        record(channelId, peerModuleId).state

    fun canOfferJoin(channelId: String, peerModuleId: String, iceState: String?): Boolean {
        if (shouldSuppressDuringChecking(channelId, peerModuleId, iceState)) return false
        val record = record(channelId, peerModuleId)
        val now = System.currentTimeMillis()
        if (record.state == PeerMeshState.BACKOFF && now - record.lastAttemptMs < record.backoffMs) {
            return false
        }
        return true
    }

    fun canReconnect(channelId: String, peerModuleId: String, iceState: String?): Boolean {
        if (IceConnectivity.isConnected(iceState)) return false
        val record = record(channelId, peerModuleId)
        val now = System.currentTimeMillis()
        if (record.state == PeerMeshState.CONNECTING &&
            now - record.lastAttemptMs < MIN_RECONNECT_OFFER_MS
        ) {
            return false
        }
        return canOfferJoin(channelId, peerModuleId, iceState)
    }

    /** Receiver-side guard: ignore rapid ICE-restart GROUP_JOIN while peer ICE is settling. */
    fun canAcceptIceRestart(channelId: String, peerModuleId: String, iceState: String?): Boolean {
        if (shouldSuppressDuringChecking(channelId, peerModuleId, iceState)) return false
        val record = record(channelId, peerModuleId)
        val now = System.currentTimeMillis()
        if (now - record.lastAttemptMs < MIN_ICE_RESTART_ACCEPT_MS) return false
        if (record.state == PeerMeshState.BACKOFF && now - record.lastAttemptMs < record.backoffMs) {
            return false
        }
        return true
    }

    fun markIceRestartAccepted(channelId: String, peerModuleId: String) {
        markReconnectAttempt(channelId, peerModuleId)
    }

    fun markJoinOffered(channelId: String, peerModuleId: String) {
        val record = record(channelId, peerModuleId)
        record.state = PeerMeshState.INVITING
        record.lastAttemptMs = System.currentTimeMillis()
    }

    fun markReconnectAttempt(channelId: String, peerModuleId: String) {
        val record = record(channelId, peerModuleId)
        record.state = PeerMeshState.CONNECTING
        record.lastAttemptMs = System.currentTimeMillis()
    }

    fun markIceChecking(channelId: String, peerModuleId: String) {
        val record = record(channelId, peerModuleId)
        if (record.state == PeerMeshState.IDLE || record.state == PeerMeshState.INVITING) {
            record.state = PeerMeshState.CONNECTING
        }
        if (record.checkingSinceMs == 0L) {
            record.checkingSinceMs = System.currentTimeMillis()
        }
    }

    fun markConnected(channelId: String, peerModuleId: String) {
        val record = record(channelId, peerModuleId)
        record.state = PeerMeshState.CONNECTED
        record.backoffMs = INITIAL_BACKOFF_MS
        record.consecutiveFailures = 0
        record.checkingSinceMs = 0L
    }

    fun markDisconnected(channelId: String, peerModuleId: String) {
        val record = record(channelId, peerModuleId)
        record.state = PeerMeshState.BACKOFF
        record.consecutiveFailures += 1
        record.backoffMs = minOf(
            MAX_BACKOFF_MS,
            INITIAL_BACKOFF_MS shl minOf(record.consecutiveFailures, 4)
        )
        record.lastAttemptMs = System.currentTimeMillis()
        record.checkingSinceMs = 0L
    }

    fun clearChannel(channelId: String) {
        peersByChannel.remove(channelId)
    }

    fun clearPeer(channelId: String, peerModuleId: String) {
        peersByChannel[channelId]?.remove(peerModuleId)
    }

    private fun record(channelId: String, peerModuleId: String): PeerRecord =
        peersByChannel
            .getOrPut(channelId) { ConcurrentHashMap() }
            .getOrPut(peerModuleId) { PeerRecord() }

    /**
     * Suppress mesh reconnect while ICE is settling, but only for a bounded window so a
     * wedged CHECKING state can still accept an ICE-restart offer after [CHECKING_STUCK_MS].
     */
    private fun shouldSuppressDuringChecking(
        channelId: String,
        peerModuleId: String,
        iceState: String?
    ): Boolean {
        if (iceState != "CHECKING" && iceState != "NEW") {
            record(channelId, peerModuleId).checkingSinceMs = 0L
            return false
        }
        val record = record(channelId, peerModuleId)
        val now = System.currentTimeMillis()
        if (record.checkingSinceMs == 0L) {
            record.checkingSinceMs = now
        }
        return now - record.checkingSinceMs < CHECKING_STUCK_MS
    }

    companion object {
        private const val INITIAL_BACKOFF_MS = 3_000L
        private const val MAX_BACKOFF_MS = 30_000L
        private const val MIN_ICE_RESTART_ACCEPT_MS = 3_000L
        private const val MIN_RECONNECT_OFFER_MS = 5_000L

        /** Exposed for tests. */
        const val CHECKING_STUCK_MS = 6_000L
    }
}
