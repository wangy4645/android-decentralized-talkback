package com.talkback.core.session

import com.talkback.core.model.EndpointAddress
import com.talkback.core.signaling.PeerTarget

data class ConferenceControlPeer(
    val target: PeerTarget,
    val remote: EndpointAddress
)

/**
 * Thread-safe copy of hangup/leave targets so P0 session-close can send
 * without waiting on coordinator SDP work.
 */
data class ConferenceControlSnapshot(
    val sessionId: String,
    val isHost: Boolean,
    val local: EndpointAddress,
    val peers: List<ConferenceControlPeer>,
    val remainingMemberKeys: List<String>,
    val channelId: String,
    val initiatorModuleId: String,
    val floorAuthorityModuleId: String,
    val rosterEpoch: Long
)
