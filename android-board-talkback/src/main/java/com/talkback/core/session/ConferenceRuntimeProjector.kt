package com.talkback.core.session

/**
 * Pure Conference runtime availability projection (RO-M2 PR-2). No side effects.
 *
 * Phase rules (Grill frozen):
 * - not accepted -> IDLE
 * - accepted, MEETING_START not TERMINAL=READY -> CONNECTING
 * - READY + >=1 remote ICE CONNECTED -> ACTIVE (may coexist with awaiting=true)
 * - mediaRecovering -> RECOVERING (overrides)
 */
object ConferenceRuntimeProjector {

    data class Input(
        val transitionTerminalReady: Boolean,
        val connectedRemoteMediaCount: Int,
        val sessionAccepted: Boolean,
        val awaitingAdditionalParticipants: Boolean,
        val mediaRecovering: Boolean = false
    )

    fun project(input: Input): ConferenceRuntimeState {
        val phase = when {
            input.mediaRecovering -> ConferenceRuntimePhase.RECOVERING
            !input.sessionAccepted -> ConferenceRuntimePhase.IDLE
            !input.transitionTerminalReady -> ConferenceRuntimePhase.CONNECTING
            input.connectedRemoteMediaCount >= 1 -> ConferenceRuntimePhase.ACTIVE
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