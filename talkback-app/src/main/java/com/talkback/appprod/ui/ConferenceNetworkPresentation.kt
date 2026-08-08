package com.talkback.appprod.ui

import com.talkback.appprod.ui.ConferenceNetworkBannerProjection.BannerScope
import com.talkback.appprod.ui.UserVisibleConnectivityProjection.UserVisibleConnectivityState
import com.talkback.core.session.ConferenceParticipantViewState

/**
 * Single source of truth for meeting network banner / poor-network pill (P1a).
 *
 * UI must consume [State] from [TalkUiState.conferenceNetworkPresentation] — not
 * [com.talkback.core.session.ConferenceNetworkIndicatorProjector] mesh ICE aggregates.
 */
object ConferenceNetworkPresentation {

    data class State(
        val bannerScope: BannerScope = BannerScope.NONE
    ) {
        val showBanner: Boolean
            get() = bannerScope != BannerScope.NONE

        val showPoorNetworkStatusPill: Boolean
            get() = showBanner
    }

    fun resolve(
        conferenceLive: Boolean,
        localLanOnline: Boolean,
        joinedParticipantCount: Int,
        peerConnectivity: List<Pair<String, UserVisibleConnectivityState>>
    ): State = State(
        bannerScope = ConferenceNetworkBannerProjection.resolve(
            conferenceLive = conferenceLive,
            localLanOnline = localLanOnline,
            joinedParticipantCount = joinedParticipantCount,
            peerConnectivity = peerConnectivity
        )
    )

    fun peerConnectivityFromParticipants(
        sessionId: String,
        participants: List<ConferenceParticipantViewState>,
        speakingModuleId: String?,
        isRecoveringPeer: (String) -> Boolean,
        isMediaUnavailablePeer: (String) -> Boolean
    ): List<Pair<String, UserVisibleConnectivityState>> =
        participants
            .asSequence()
            .filter { !it.isLocal }
            .mapNotNull { participant ->
                val facts = MeetingPresenceDisplay.ParticipantPresentationFacts(
                    sessionId = sessionId,
                    moduleId = participant.moduleId,
                    isLocal = false,
                    displayState = participant.displayState,
                    isRecoveringPeer = isRecoveringPeer(participant.moduleId),
                    mediaUnavailablePeer = isMediaUnavailablePeer(participant.moduleId),
                    speaking = speakingModuleId != null &&
                        speakingModuleId.equals(participant.moduleId, ignoreCase = true),
                    captureBlocked = false
                )
                val connectivity = MeetingPresenceDisplay.resolveVisibleConnectivity(facts)
                    ?: return@mapNotNull null
                participant.moduleId to connectivity
            }
            .toList()
}
