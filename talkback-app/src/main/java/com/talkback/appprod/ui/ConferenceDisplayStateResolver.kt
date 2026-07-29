package com.talkback.appprod.ui

import com.talkback.core.session.ConferenceRuntimePhase

/**
 * Unified Conference UI display projection.
 *
 * Decouples lifecycle (session exists), media availability ([ConferenceConnectivityFacts.channelReady]),
 * and membership completion (hint-only).
 */
enum class ConferenceDisplayPhase {
    INACTIVE,
    AWAITING_REJOIN,
    MEDIA_CONNECTING,
    RECOVERING,
    LIVE
}

enum class ConferenceStatusPillKind {
    INACTIVE,
    CONNECTING,
    RECOVERING,
    RECONNECT_FAILED,
    LIVE,
    MUTED,
    POOR_NETWORK
}

data class ConferenceLifecycleFacts(
    val conferenceActive: Boolean,
    val conferenceMode: Boolean = false,
    val sessionActive: Boolean = false,
    val runtimePhase: ConferenceRuntimePhase? = null
)

data class ConferenceConnectivityFacts(
    val channelReady: Boolean,
    val channelConnecting: Boolean = false,
    val reconnecting: Boolean = false,
    val reconnectFailed: Boolean = false,
    val awaitingRejoin: Boolean = false
)

data class ConferenceMembershipFacts(
    val awaitingAdditionalParticipants: Boolean = false,
    val connectingParticipantHint: String? = null
)

data class ConferenceDisplayState(
    val phase: ConferenceDisplayPhase,
    /** Media live: conference runtime exists and channel media is ready. */
    val live: Boolean,
    val showConnectingPanel: Boolean,
    val showLivePanel: Boolean,
    /** Secondary hint only — never downgrades [live]. */
    val membershipHintVisible: Boolean,
    val membershipHint: String? = null,
    val timerEligible: Boolean,
    val statusPill: ConferenceStatusPillKind,
    val inProgress: Boolean,
    val mediaConnecting: Boolean,
    val recovering: Boolean
)

object ConferenceDisplayStateResolver {

    fun resolve(
        lifecycle: ConferenceLifecycleFacts,
        connectivity: ConferenceConnectivityFacts,
        membership: ConferenceMembershipFacts = ConferenceMembershipFacts(),
        muted: Boolean = false,
        poorNetwork: Boolean = false
    ): ConferenceDisplayState {
        val awaitingRejoin = connectivity.awaitingRejoin ||
            (lifecycle.conferenceMode && !lifecycle.conferenceActive)
        val mediaConnecting = lifecycle.conferenceActive &&
            !connectivity.channelReady &&
            !awaitingRejoin
        val recovering = lifecycle.conferenceActive &&
            connectivity.channelReady &&
            (
                connectivity.reconnecting ||
                    connectivity.reconnectFailed ||
                    lifecycle.runtimePhase == ConferenceRuntimePhase.RECOVERING
                )
        val live = lifecycle.conferenceActive && connectivity.channelReady && !awaitingRejoin

        val phase = when {
            awaitingRejoin -> ConferenceDisplayPhase.AWAITING_REJOIN
            !lifecycle.conferenceActive -> ConferenceDisplayPhase.INACTIVE
            live && recovering -> ConferenceDisplayPhase.RECOVERING
            live -> ConferenceDisplayPhase.LIVE
            mediaConnecting -> ConferenceDisplayPhase.MEDIA_CONNECTING
            lifecycle.conferenceActive -> ConferenceDisplayPhase.MEDIA_CONNECTING
            else -> ConferenceDisplayPhase.INACTIVE
        }

        val statusPill = when {
            awaitingRejoin -> ConferenceStatusPillKind.CONNECTING
            !lifecycle.conferenceActive -> ConferenceStatusPillKind.INACTIVE
            connectivity.reconnectFailed && recovering -> ConferenceStatusPillKind.RECONNECT_FAILED
            recovering -> ConferenceStatusPillKind.RECOVERING
            mediaConnecting -> ConferenceStatusPillKind.CONNECTING
            muted && live -> ConferenceStatusPillKind.MUTED
            poorNetwork && live -> ConferenceStatusPillKind.POOR_NETWORK
            live -> ConferenceStatusPillKind.LIVE
            else -> ConferenceStatusPillKind.CONNECTING
        }

        val membershipHintVisible = live && membership.awaitingAdditionalParticipants

        return ConferenceDisplayState(
            phase = phase,
            live = live,
            showConnectingPanel = awaitingRejoin || mediaConnecting,
            showLivePanel = live,
            membershipHintVisible = membershipHintVisible,
            membershipHint = membership.connectingParticipantHint,
            timerEligible = live,
            statusPill = statusPill,
            inProgress = live,
            mediaConnecting = mediaConnecting,
            recovering = recovering
        )
    }

    fun fromTalkUiState(state: TalkUiState): ConferenceDisplayState = resolve(
        lifecycle = ConferenceLifecycleFacts(
            conferenceActive = state.conferenceActive,
            conferenceMode = state.conferenceMode,
            sessionActive = state.sessionActive,
            runtimePhase = state.meeting.runtimePhase
        ),
        connectivity = ConferenceConnectivityFacts(
            channelReady = state.channelReady,
            channelConnecting = state.channelConnecting,
            reconnecting = state.conferenceReconnecting,
            reconnectFailed = state.conferenceReconnectFailed,
            awaitingRejoin = state.conferenceMode && !state.conferenceActive
        ),
        membership = ConferenceMembershipFacts(
            awaitingAdditionalParticipants = state.meeting.awaitingAdditionalParticipants,
            connectingParticipantHint = state.meeting.connectingParticipantHint
        ),
        muted = state.conferenceMuted,
        poorNetwork = state.meeting.networkLabel == "Poor" || state.networkLabel == "Poor"
    )
}
