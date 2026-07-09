package com.talkback.core.session

/**
 * Pure Conference runtime availability projection (RO-M2 PR-2 / ADR-0020).
 * No side effects.
 *
 * Phase rules (ADR-0020):
 * - mediaRecovering -> RECOVERING (connectivity; overrides)
 * - not accepted -> IDLE
 * - accepted, MEETING_START not TERMINAL=READY -> CONNECTING
 * - Host established (Local Conference Ready) -> ACTIVE; remoteMediaCount ignored (H1)
 * - Participant established + authority reachable -> ACTIVE (P1)
 * - else -> CONNECTING
 *
 * Forbidden: remoteMediaCount == 0 => CONNECTING for established host.
 */
object ConferenceRuntimeProjector {

    data class Input(
        val transitionTerminalReady: Boolean,
        val connectedRemoteMediaCount: Int,
        val sessionAccepted: Boolean,
        val awaitingAdditionalParticipants: Boolean,
        val mediaRecovering: Boolean = false,
        val isConferenceHost: Boolean = false,
        val authorityReachable: Boolean = false
    )

    fun project(input: Input): ConferenceRuntimeState {
        val phase = when {
            input.mediaRecovering -> ConferenceRuntimePhase.RECOVERING
            !input.sessionAccepted -> ConferenceRuntimePhase.IDLE
            !input.transitionTerminalReady -> ConferenceRuntimePhase.CONNECTING
            input.isConferenceHost -> ConferenceRuntimePhase.ACTIVE
            input.authorityReachable -> ConferenceRuntimePhase.ACTIVE
            else -> ConferenceRuntimePhase.CONNECTING
        }
        return ConferenceRuntimeState(
            phase = phase,
            mediaRecovering = input.mediaRecovering,
            awaitingAdditionalParticipants = input.awaitingAdditionalParticipants,
            transitionTerminalReady = input.transitionTerminalReady,
            connectedRemoteMediaCount = input.connectedRemoteMediaCount
        )
    }
}