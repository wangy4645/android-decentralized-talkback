package com.talkback.appprod.ui

import com.talkback.core.session.ConferenceParticipantDisplayState

/**
 * Maps conference participant projection facts to UI endpoint status (ADR-0025 R30-F P0-C).
 * Pure function — no roster/ICE/HELLO inference.
 */
object ConferenceEndpointStatusMapper {

    fun map(
        displayState: ConferenceParticipantDisplayState,
        speaking: Boolean,
        isRecoveringPeer: Boolean,
        wasEverConnected: Boolean = false,
        mediaUnavailablePeer: Boolean = false
    ): EndpointStatus {
        val base = when (displayState) {
            ConferenceParticipantDisplayState.VISIBLE_LOCAL,
            ConferenceParticipantDisplayState.VISIBLE_CONNECTED ->
                if (speaking) EndpointStatus.SPEAKING else EndpointStatus.ONLINE
            ConferenceParticipantDisplayState.VISIBLE_CONNECTING ->
                if (wasEverConnected || mediaUnavailablePeer) {
                    EndpointStatus.RECONNECTING
                } else {
                    EndpointStatus.CONNECTING
                }
            ConferenceParticipantDisplayState.VISIBLE_RECONNECTING,
            ConferenceParticipantDisplayState.VISIBLE_FAILED ->
                EndpointStatus.RECONNECTING
        }
        if (isRecoveringPeer && base != EndpointStatus.SPEAKING) {
            return EndpointStatus.RECONNECTING
        }
        return base
    }

    fun signalBarsFor(status: EndpointStatus): Int = when (status) {
        EndpointStatus.OFFLINE -> 0
        EndpointStatus.CONNECTING,
        EndpointStatus.RECONNECTING -> 1
        else -> 3
    }
}
