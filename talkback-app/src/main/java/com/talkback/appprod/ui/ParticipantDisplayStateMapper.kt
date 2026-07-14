package com.talkback.appprod.ui

import com.talkback.core.session.ConferenceMembershipLifecycle
import com.talkback.core.session.ConferenceParticipantDisplayState

/**
 * Presentation-only mapper (ADR-0025 R30-I).
 *
 * Recovery facts are diagnostic input only; they MUST NOT drive user-visible state unless
 * media availability is affected.
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
        val everConnected: Boolean,
        val mediaUnavailable: Boolean,
        /** Diagnostic only — not used for avatar/hint mapping when playback is ready. */
        val recovering: Boolean = false,
        val isLocal: Boolean = false
    )

    fun playbackReady(input: Input): Boolean {
        if (input.isLocal) return true
        if (input.mediaUnavailable) return false
        return input.displayState == ConferenceParticipantDisplayState.VISIBLE_CONNECTED ||
            input.displayState == ConferenceParticipantDisplayState.VISIBLE_LOCAL
    }

    fun map(input: Input): ParticipantDisplayState {
        if (input.isLocal) return ParticipantDisplayState.ONLINE
        if (input.membership != ConferenceMembershipLifecycle.JOINED &&
            input.membership != ConferenceMembershipLifecycle.INVITED
        ) {
            return ParticipantDisplayState.LEFT
        }
        if (playbackReady(input)) {
            return ParticipantDisplayState.ONLINE
        }
        if (input.mediaUnavailable || input.everConnected) {
            return ParticipantDisplayState.RECONNECTING
        }
        return ParticipantDisplayState.JOINING
    }
}
