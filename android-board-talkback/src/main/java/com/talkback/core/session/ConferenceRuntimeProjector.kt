package com.talkback.core.session

/**
 * Pure Conference runtime availability projection (RO-M2 PR-2 / ADR-0020, ADR-0021 R15/R24).
 * No side effects.
 *
 * Phase rules (ADR-0020 + ADR-0021 뿯½4):
 * - authority mediaRecovering -> RECOVERING (session-level; overrides)
 * - edgeRecovering -> phase stays ACTIVE; conferenceDegraded=true
 * - edgeRecoveryFailed (R24-A) -> phase stays ACTIVE; conferenceDegraded=true; edgeRecovering=false
 * - not accepted -> IDLE
 * - accepted, MEETING_START not TERMINAL_READY -> CONNECTING
 * - Host established (Local Conference Ready) -> ACTIVE; remoteMediaCount ignored (H1)
 * - Participant established + authority reachable -> ACTIVE (H1)
 * - else -> CONNECTING
 *
 * Forbidden: remoteMediaCount == 0 => CONNECTING for established host.
 * Forbidden: FAILED_MEDIA_RECOVERY residency projecting as CONNECTING (R24-A).
 */
object ConferenceRuntimeProjector {
    data class Input(
        val transitionTerminalReady: Boolean,
        val connectedRemoteMediaCount: Int,
        val sessionAccepted: Boolean,
        val awaitingAdditionalParticipants: Boolean,
        val mediaRecovering: Boolean = false,
        val edgeRecovering: Boolean = false,
        val edgeRecoveryFailed: Boolean = false,
        val isConferenceHost: Boolean = false,
        val authorityReachable: Boolean = false
    )

    fun project(input: Input): ConferenceRuntimeState {
        val phase = when {
            input.mediaRecovering -> ConferenceRuntimePhase.RECOVERING
            !input.sessionAccepted -> ConferenceRuntimePhase.IDLE
            !input.transitionTerminalReady -> ConferenceRuntimePhase.CONNECTING
            input.isConferenceHost -> ConferenceRuntimePhase.ACTIVE
            input.edgeRecovering -> ConferenceRuntimePhase.ACTIVE
            input.edgeRecoveryFailed -> ConferenceRuntimePhase.ACTIVE
            input.authorityReachable -> ConferenceRuntimePhase.ACTIVE
            else -> ConferenceRuntimePhase.CONNECTING
        }
        val conferenceDegraded = input.edgeRecovering || input.edgeRecoveryFailed
        return ConferenceRuntimeState(
            phase = phase,
            // Session-level media repair only — do not fold edge failure into this flag (R24).
            mediaRecovering = input.mediaRecovering,
            edgeRecovering = input.edgeRecovering,
            conferenceDegraded = conferenceDegraded,
            awaitingAdditionalParticipants = input.awaitingAdditionalParticipants,
            transitionTerminalReady = input.transitionTerminalReady,
            connectedRemoteMediaCount = input.connectedRemoteMediaCount
        )
    }
}
