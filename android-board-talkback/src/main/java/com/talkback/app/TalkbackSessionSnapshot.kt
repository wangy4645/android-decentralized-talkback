package com.talkback.app

import com.talkback.core.ptt.PttState
import com.talkback.core.session.ConferenceParticipantViewState
import com.talkback.core.session.ConferencePresenceProjection
import com.talkback.core.session.ConferenceRuntimeState
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
    /** Channel config members frozen at session create (ADR-0002); distinct from active session roster. */
    val channelMemberModuleIds: List<String> = emptyList(),
    /** Conference roster projection (control plane / debug). Not the UI participant list. */
    val memberViews: List<MemberView> = emptyList(),
    /**
     * Canonical Conference UI participant list (ADR-0010 R44).
     * Drives avatar row, participant count, and speaking views for Conference.
     */
    val visibleParticipants: List<ConferenceParticipantViewState> = emptyList(),
    /** [visibleParticipants] with [ConferenceParticipantViewState.countsTowardParticipantTotal]. */
    val visibleParticipantCount: Int = 0,
    /** Primary meeting size (ADR-0020 P2); excludes pending invitees. */
    val joinedParticipantCount: Int = 0,
    val pendingInviteeCount: Int = 0,
    /** True when roster still has invitees not yet visible in the meeting UI. */
    val awaitingAdditionalParticipants: Boolean = false,
    /**
     * Canonical Conference runtime availability projection (RO-M2 PR-2).
     * Meeting UI must consume this in PR-3; null for non-CONFERENCE sessions.
     */
    val conferenceRuntimeState: ConferenceRuntimeState? = null,
    /**
     * Canonical Conference presence projection (ADR-0022 R27′).
     * Meeting UI MUST use joined/connected/recovering from here — not roster size or ICE.
     */
    val conferencePresenceProjection: ConferencePresenceProjection? = null,
    /** ICE-direct mesh peer count; diagnostics only — must not drive Conference UI. */
    val meshConnectedPeerCount: Int = 0,
    /**
     * Mesh connectivity diagnostic (GROUP/UNICAST legacy field name).
     * Conference UI must use [visibleParticipantCount] instead.
     */
    val connectedRemoteCount: Int = 0,
    val callPhase: UnicastCallPhase? = null,
    val remoteKey: String? = null,
    val localInitiated: Boolean = false,
    val muted: Boolean = false
)
