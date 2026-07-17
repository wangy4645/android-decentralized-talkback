package com.talkback.core.webrtc

import android.os.SystemClock
import com.talkback.core.model.ModuleId
import com.talkback.core.session.GroupMediaTopology
import com.talkback.core.session.SessionType
import com.talkback.core.session.TalkbackSession
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

/**
 * Media-layer fact owner for [receivePathLive] (ADR-0028 R30-J / R30-J-4).
 * Owns Tplayback debounce; projection reads the boolean only.
 */
class ReceivePathLivenessObserver(
    private val debounceMs: Long = DEFAULT_DEBOUNCE_MS,
    private val clock: () -> Long = { SystemClock.elapsedRealtime() }
) {
    private data class PeerReceiveState(
        var firstPcmAtMs: Long? = null,
        var lastPcmAtMs: Long? = null,
        var live: Boolean = false
    )

    private val stateBySessionPeer = ConcurrentHashMap<String, ConcurrentHashMap<String, PeerReceiveState>>()
    private val meshTapsBySession = ConcurrentHashMap<String, MutableList<InboundAudioTap>>()

    fun onInboundPcm(sessionId: String, remoteModuleId: String) {
        val now = clock()
        val peer = stateBySessionPeer
            .getOrPut(sessionId) { ConcurrentHashMap() }
            .getOrPut(remoteModuleId) { PeerReceiveState() }
        val lastPcm = peer.lastPcmAtMs
        if (lastPcm != null && now - lastPcm > debounceMs) {
            peer.firstPcmAtMs = now
            peer.live = false
        } else if (peer.firstPcmAtMs == null) {
            peer.firstPcmAtMs = now
        }
        peer.lastPcmAtMs = now
        val streakStart = peer.firstPcmAtMs ?: now
        if (!peer.live && now - streakStart >= debounceMs) {
            peer.live = true
        }
    }

    fun receivePathLive(sessionId: String, remoteModuleId: String): Boolean {
        val now = clock()
        val peer = stateBySessionPeer[sessionId]?.get(remoteModuleId) ?: return false
        val lastPcm = peer.lastPcmAtMs ?: return false
        if (now - lastPcm > debounceMs) {
            peer.live = false
            peer.firstPcmAtMs = null
            peer.lastPcmAtMs = null
            return false
        }
        val streakStart = peer.firstPcmAtMs
        if (!peer.live && streakStart != null && now - streakStart >= debounceMs) {
            peer.live = true
        }
        return peer.live
    }

    fun syncMeshSession(
        session: TalkbackSession,
        @Suppress("UNUSED_PARAMETER") localModuleId: ModuleId,
        engineLookup: (String) -> WebRtcAudioEngine?
    ) {
        meshTapsBySession.remove(session.id)?.forEach { it.release() }
        if (session.type != SessionType.CONFERENCE) return
        if (session.mediaTopology == GroupMediaTopology.ANCHOR) return
        val remoteIds = session.remotePeersByModule.keys
        if (remoteIds.isEmpty()) return
        val taps = mutableListOf<InboundAudioTap>()
        remoteIds.forEach { remoteId ->
            val engine = engineLookup(remoteId) ?: return@forEach
            val tap = InboundAudioTap(engine) {
                onInboundPcm(session.id, remoteId)
            }
            taps.add(tap)
        }
        if (taps.isNotEmpty()) {
            meshTapsBySession[session.id] = taps
        }
    }

    fun clearSession(sessionId: String) {
        stateBySessionPeer.remove(sessionId)
        meshTapsBySession.remove(sessionId)?.forEach { it.release() }
    }

    private class InboundAudioTap(
        private val engine: WebRtcAudioEngine,
        onPcm: () -> Unit
    ) {
        private val sink = object : InboundPcmSink {
            override fun onPcm(
                audioData: ByteBuffer,
                bitsPerSample: Int,
                sampleRate: Int,
                numberOfChannels: Int,
                numberOfFrames: Int
            ) {
                onPcm()
            }
        }

        init {
            engine.setInboundPcmSink(sink)
        }

        fun release() {
            engine.setInboundPcmSink(null)
        }
    }

    companion object {
        const val DEFAULT_DEBOUNCE_MS = 500L
    }
}
