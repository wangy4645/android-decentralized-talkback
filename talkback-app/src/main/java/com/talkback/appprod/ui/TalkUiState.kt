package com.talkback.appprod.ui

import com.talkback.core.session.UnicastCallPhase
import com.talkback.core.session.ChannelReadiness
import com.talkback.core.session.ConferenceRuntimePhase

enum class ConferenceEndReason {
    NONE,
    /** User tapped Leave / End meeting on this device. */
    USER_LEFT,
    /** Host hung up or session was torn down remotely. */
    REMOTE_ENDED
}

enum class EndpointStatus {
    ONLINE,
    SPEAKING,
    OFFLINE,
    /** Conference invite sent, awaiting answer. */
    INVITING,
    /** ICE/media negotiation in progress. */
    CONNECTING,
    /** Invite timed out or was not answered. */
    EXPIRED
}

data class EndpointUiItem(
    val key: String,
    val displayLabel: String,
    val status: EndpointStatus,
    val signalBars: Int,
    val isLocal: Boolean = false
)

data class IncomingMeetingInviteUi(
    val sessionId: String,
    val hostLabel: String,
    val channelTitle: String
)

data class RejoinableMeetingUi(
    val hostSessionId: String,
    val hostLabel: String,
    val channelTitle: String
)

data class MeetingUiState(
    val sessionId: String? = null,
    val memberKeys: List<String> = emptyList(),
    /** Canonical Conference UI participant count (ADR-0010 R44). */
    val visibleParticipantCount: Int = 0,
    val awaitingAdditionalParticipants: Boolean = false,
    /** Conference runtime phase from [TalkbackSessionSnapshot.conferenceRuntimeState] (RO-M2 PR-3). */
    val runtimePhase: ConferenceRuntimePhase? = null,
    val startedAtMs: Long? = null,
    val networkLabel: String = "N/A",
    val rttMs: Long? = null,
    val packetLossPercent: Double? = null,
    val codecLabel: String = "OPUS",
    val autoGain: Boolean = true,
    val noiseSuppression: Boolean = true,
    val locked: Boolean = false
)

data class CallUiState(
    val active: Boolean = false,
    val sessionId: String? = null,
    val remoteKey: String? = null,
    val remoteLabel: String? = null,
    val teamName: String? = null,
    val phase: UnicastCallPhase? = null,
    val localInitiated: Boolean = false,
    val muted: Boolean = false,
    val networkLabel: String = "N/A",
    val rttMs: Long? = null,
    val packetLossPercent: Double? = null,
    val codecLabel: String = "OPUS"
)

data class TalkUiState(
    val serviceRunning: Boolean,
    val serviceDetail: String,
    val taskProfileName: String = "",
    val rfKeyLabel: String = "",
    val channelTitle: String,
    val channelSubtitle: String,
    val onlineCount: Int,
    val floorOwner: String,
    val floorOwnerKey: String? = null,
    /** Decoupled control/media presentation of the floor. UI should prefer this. */
    val floorPresentation: FloorPresentation = FloorPresentation.Idle,
    val localEndpointKey: String = "",
    val talking: String,
    val networkLabel: String,
    val sessionActive: Boolean,
    val pttActive: Boolean,
    /** True only when this device holds the floor and is in TALK (not REQUEST_FLOOR). */
    val localTransmitting: Boolean = false,
    val channelReady: Boolean = false,
    val channelConnecting: Boolean = false,
    val channelReadiness: ChannelReadiness = ChannelReadiness.NO_SERVICE,
    /** True when peers are known but this device waits for the elected mesh host to invite. */
    val channelAwaitingHost: Boolean = false,
    val conferenceMode: Boolean = false,
    val conferenceActive: Boolean = false,
    val conferenceEndReason: ConferenceEndReason = ConferenceEndReason.NONE,
    val conferenceMuted: Boolean = false,
    val incomingMeetingInvite: IncomingMeetingInviteUi? = null,
    val rejoinableMeeting: RejoinableMeetingUi? = null,
    val conferenceRejoinInProgress: Boolean = false,
    val conferenceReconnecting: Boolean = false,
    val conferenceReconnectFailed: Boolean = false,
    /** Channel config members (static intent, ADR-0002). */
    val channelMemberModuleIds: List<String> = emptyList(),
    /** Active session roster keys (runtime membership). */
    val sessionMemberKeys: List<String> = emptyList(),
    val endpoints: List<EndpointUiItem>,
    val call: CallUiState = CallUiState(),
    val meeting: MeetingUiState = MeetingUiState()
)
