package com.talkback.app

import com.talkback.core.ptt.PttState
import com.talkback.core.session.MemberView
import com.talkback.core.session.SessionType
import com.talkback.core.session.UnicastCallPhase

data class TalkbackSessionSnapshot(
    val sessionId: String,
    val type: SessionType,
    val channelId: String?,
    val protocolFloorOwnerKey: String?,
    val localPttState: PttState,
    val memberKeys: List<String>,
    /** Per-member invite/media state; UI should treat media==CONNECTED as in-meeting. */
    val memberViews: List<MemberView> = emptyList(),
    /** Mesh peers with ICE CONNECTED (0 = solo in meeting, live but waiting for others). */
    val connectedRemoteCount: Int = 0,
    val callPhase: UnicastCallPhase? = null,
    val remoteKey: String? = null,
    val localInitiated: Boolean = false,
    val muted: Boolean = false
)
