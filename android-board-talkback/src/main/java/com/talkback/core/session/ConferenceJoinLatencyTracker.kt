package com.talkback.core.session

import com.talkback.core.util.TalkbackLog

class ConferenceJoinLatencyTracker {

    data class PeerTrack(
        var pcCreatedAtMs: Long? = null,
        var iceCheckingAtMs: Long? = null,
        var iceConnectedAtMs: Long? = null
    )

    data class SessionTrack(
        var inviteAcceptedAtMs: Long? = null,
        var sessionCreatedAtMs: Long? = null,
        var firstRemoteMediaAtMs: Long? = null,
        var meshRoundStartedAtMs: Long? = null,
        var lastJoinedCount: Int = 0,
        var lastEmittedJoinedCount: Int = 0,
        var role: String = "participant",
        var channelId: String? = null,
        val peerMarkers: MutableMap<String, PeerTrack> = linkedMapOf()
    )

    private val sessions = linkedMapOf<String, SessionTrack>()
    private var logSink: ((String) -> Unit)? = null

    internal fun resetForTest(sink: ((String) -> Unit)? = null) {
        sessions.clear()
        logSink = sink
    }

    fun removeSession(sessionId: String) {
        sessions.remove(sessionId)
    }

    fun onInviteAccepted(sessionId: String, channelId: String?, role: String, nowMs: Long = System.currentTimeMillis()) {
        val track = sessions.getOrPut(sessionId) { SessionTrack() }
        if (track.inviteAcceptedAtMs == null) {
            track.inviteAcceptedAtMs = nowMs
            track.role = role
        }
        if (channelId != null) track.channelId = channelId
        logMarker(sessionId, track, "T0_INVITE_ACCEPTED", null, nowMs)
    }

    fun onSessionCreated(sessionId: String, nowMs: Long = System.currentTimeMillis()) {
        val track = sessions.getOrPut(sessionId) { SessionTrack() }
        if (track.sessionCreatedAtMs == null) {
            track.sessionCreatedAtMs = nowMs
            logMarker(sessionId, track, "T1_SESSION_CREATED", null, nowMs)
        }
    }

    fun onPeerConnectionCreated(sessionId: String, peerModuleId: String, nowMs: Long = System.currentTimeMillis()) {
        val peer = peerTrack(sessionId, peerModuleId)
        if (peer.pcCreatedAtMs == null) {
            peer.pcCreatedAtMs = nowMs
            logMarker(sessionId, sessions[sessionId]!!, "T3_PEER_PC_CREATED", peerModuleId, nowMs)
        }
    }

    fun onPeerIceChecking(sessionId: String, peerModuleId: String, nowMs: Long = System.currentTimeMillis()) {
        val track = sessions[sessionId] ?: return
        val peer = peerTrack(sessionId, peerModuleId)
        if (peer.iceCheckingAtMs == null) {
            peer.iceCheckingAtMs = nowMs
            logMarker(sessionId, track, "T4_ICE_CHECKING", peerModuleId, nowMs)
        }
    }

    fun onPeerIceConnected(sessionId: String, peerModuleId: String, nowMs: Long = System.currentTimeMillis()) {
        val track = sessions[sessionId] ?: return
        val peer = peerTrack(sessionId, peerModuleId)
        if (peer.iceConnectedAtMs == null) {
            peer.iceConnectedAtMs = nowMs
            logMarker(sessionId, track, "T5_ICE_CONNECTED", peerModuleId, nowMs)
        }
        if (track.firstRemoteMediaAtMs == null) {
            track.firstRemoteMediaAtMs = nowMs
            logMarker(sessionId, track, "T2_FIRST_REMOTE_MEDIA", peerModuleId, nowMs)
        }
    }

    fun onJoinedCountChanged(sessionId: String, joinedCount: Int, channelId: String?, nowMs: Long = System.currentTimeMillis()) {
        val track = sessions.getOrPut(sessionId) { SessionTrack() }
        if (channelId != null) track.channelId = channelId
        if (joinedCount > track.lastJoinedCount) {
            track.meshRoundStartedAtMs = nowMs
            logMarker(sessionId, track, "ROSTER_JOINED", joinedCount.toString(), nowMs)
        }
        track.lastJoinedCount = joinedCount
    }

    fun onFullMeshReached(sessionId: String, joinedCount: Int, connectedCount: Int, nowMs: Long = System.currentTimeMillis()) {
        if (joinedCount < 2 || connectedCount < joinedCount) return
        val track = sessions[sessionId] ?: return
        if (track.lastEmittedJoinedCount >= joinedCount) return
        track.lastEmittedJoinedCount = joinedCount
        emitSummary(sessionId, track, joinedCount, connectedCount, nowMs)
        logMarker(sessionId, track, "T6_FULL_MESH", null, nowMs)
    }

    internal fun sessionTrack(sessionId: String): SessionTrack? = sessions[sessionId]

    private fun peerTrack(sessionId: String, peerModuleId: String): PeerTrack =
        sessions.getOrPut(sessionId) { SessionTrack() }.peerMarkers.getOrPut(peerModuleId) { PeerTrack() }

    private fun emitSummary(sessionId: String, track: SessionTrack, joinedCount: Int, connectedCount: Int, nowMs: Long) {
        val t0 = track.inviteAcceptedAtMs
        val t1 = track.sessionCreatedAtMs
        val t2 = track.firstRemoteMediaAtMs
        val meshT0 = track.meshRoundStartedAtMs ?: t0
        val message = buildString {
            append("CONFERENCE_JOIN_LATENCY")
            append(" session=").append(sessionId)
            track.channelId?.let { append(" ch=").append(it) }
            append(" role=").append(track.role)
            append(" joined=").append(joinedCount)
            append(" connected=").append(connectedCount)
            if (t0 != null && t1 != null) append(" sessionCreateMs=").append(t1 - t0)
            if (t0 != null && t2 != null) append(" firstMediaMs=").append(t2 - t0)
            if (meshT0 != null) append(" fullMeshMs=").append(nowMs - meshT0)
            if (track.peerMarkers.isNotEmpty()) {
                append(" peers=")
                append(track.peerMarkers.entries.joinToString(";") { (peer, markers) ->
                    buildString {
                        append(peer)
                        markers.pcCreatedAtMs?.let { append(":pc=").append(it - (meshT0 ?: it)) }
                        markers.iceCheckingAtMs?.let { append(":chk=").append(it - (meshT0 ?: it)) }
                        markers.iceConnectedAtMs?.let { append(":conn=").append(it - (meshT0 ?: it)) }
                    }
                })
            }
        }
        log(message)
    }

    private fun logMarker(sessionId: String, track: SessionTrack, marker: String, peer: String?, nowMs: Long) {
        val meshT0 = track.meshRoundStartedAtMs ?: track.inviteAcceptedAtMs
        val delta = meshT0?.let { nowMs - it }
        val message = buildString {
            append("CONFERENCE_JOIN_LATENCY")
            append(" marker=").append(marker)
            append(" session=").append(sessionId)
            track.channelId?.let { append(" ch=").append(it) }
            peer?.let { append(" peer=").append(it) }
            delta?.let { append(" sinceMeshRoundMs=").append(it) }
        }
        log(message)
    }

    private fun log(message: String) {
        val sink = logSink
        if (sink != null) sink(message) else TalkbackLog.i(message)
    }
}