package com.talkback.appprod.ui

import com.talkback.core.session.ConferenceMembershipLifecycle
import com.talkback.core.session.ConferenceParticipantDisplayState
import com.talkback.appprod.ui.LocalReachability.ParticipantPresenceState
import com.talkback.appprod.ui.LocalReachability.toMembershipState

/**
 * Presentation-only mapper (ADR-0025 R30-I → ADR-0028 R30-J thin glue).
 *
 * Avatar/hint state is owned by [LocalReachability.resolve]; this mapper delegates.
 */
object ParticipantDisplayStateMapper {

    enum class ParticipantDisplayState {
        ONLINE,
        RECONNECTING,
        JOINING,
        LEFT
    }

    data class Input(
        val membership: ConferenceMembershipLifecycle = ConferenceMembershipLifecycle.JOINED,
        val displayState: ConferenceParticipantDisplayState,
        val mediaEverLive: Boolean,
        val mediaUnavailable: Boolean,
        /** Diagnostic only — consumed inside [LocalReachability.resolve] when path not live. */
        val recovering: Boolean = false,
        val isLocal: Boolean = false,
        val receivePathLive: Boolean = false
    )

    fun map(input: Input): ParticipantDisplayState {
        if (input.isLocal) return ParticipantDisplayState.ONLINE
        return LocalReachability.resolve(
            membership = input.membership.toMembershipState(),
            receivePathLive = input.receivePathLive,
            recovering = input.recovering,
            mediaUnavailable = input.mediaUnavailable,
            mediaEverLive = input.mediaEverLive
        ).state.toDisplayState()
    }

    private fun ParticipantPresenceState.toDisplayState(): ParticipantDisplayState =
        when (this) {
            ParticipantPresenceState.ONLINE -> ParticipantDisplayState.ONLINE
            ParticipantPresenceState.RECONNECTING -> ParticipantDisplayState.RECONNECTING
            ParticipantPresenceState.JOINING -> ParticipantDisplayState.JOINING
            ParticipantPresenceState.LEFT,
            ParticipantPresenceState.OFFLINE -> ParticipantDisplayState.LEFT
        }
}
