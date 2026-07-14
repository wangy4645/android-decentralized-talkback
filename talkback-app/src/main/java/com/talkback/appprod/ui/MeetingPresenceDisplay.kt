package com.talkback.appprod.ui

import com.talkback.core.session.ConferenceParticipantDisplayState
import com.talkback.core.session.ConferencePresenceProjection

/**
 * View-only presence display rules (ADR-0025 R30-F / R30-I).
 * Header uses roster membership; hints and avatars use user-perceived availability only.
 */
object MeetingPresenceDisplay {

    fun participantCountLabel(joinedCount: Int): String = "$joinedCount Participants"

    enum class ParticipantAvailabilityKind {
        NONE,
        JOINING,
        RECONNECTING,
        CAPTURE_BLOCKED
    }

    /**
     * Single source for header hint and avatar badge (R30-I).
     * Conference receive readiness comes from participant projection display state,
     * not GROUP floor playback gate (`remotePlaybackEnabledForModule` is NO_FLOOR_OWNER in meeting).
     */
    data class ParticipantPresentationFacts(
        val moduleId: String,
        val isLocal: Boolean,
        val displayState: ConferenceParticipantDisplayState,
        val everConnected: Boolean,
        val isRecoveringPeer: Boolean,
        val mediaUnavailablePeer: Boolean,
        val speaking: Boolean,
        val captureBlocked: Boolean = false
    )

    data class ParticipantPresentationState(
        val moduleId: String,
        val isLocal: Boolean,
        val endpointStatus: EndpointStatus,
        val availabilityKind: ParticipantAvailabilityKind
    )

    internal fun receiveReady(facts: ParticipantPresentationFacts): Boolean =
        ParticipantDisplayStateMapper.playbackReady(facts.toDisplayMapperInput())

    private fun ParticipantPresentationFacts.toDisplayMapperInput() =
        ParticipantDisplayStateMapper.Input(
            displayState = displayState,
            everConnected = everConnected,
            mediaUnavailable = mediaUnavailablePeer,
            recovering = isRecoveringPeer,
            isLocal = isLocal
        )

    fun resolveParticipantPresentation(
        facts: ParticipantPresentationFacts
    ): ParticipantPresentationState {
        val playbackReady = receiveReady(facts)
        if (facts.isLocal) {
            val status = if (facts.speaking) EndpointStatus.SPEAKING else EndpointStatus.ONLINE
            val kind = if (facts.captureBlocked) {
                ParticipantAvailabilityKind.CAPTURE_BLOCKED
            } else {
                ParticipantAvailabilityKind.NONE
            }
            return ParticipantPresentationState(facts.moduleId, isLocal = true, status, kind)
        }
        if (facts.speaking && playbackReady) {
            return ParticipantPresentationState(
                facts.moduleId,
                isLocal = false,
                EndpointStatus.SPEAKING,
                ParticipantAvailabilityKind.NONE
            )
        }
        return when (ParticipantDisplayStateMapper.map(facts.toDisplayMapperInput())) {
            ParticipantDisplayStateMapper.ParticipantDisplayState.LEFT ->
                ParticipantPresentationState(
                    facts.moduleId,
                    isLocal = false,
                    EndpointStatus.OFFLINE,
                    ParticipantAvailabilityKind.NONE
                )
            ParticipantDisplayStateMapper.ParticipantDisplayState.RECONNECTING ->
                ParticipantPresentationState(
                    facts.moduleId,
                    isLocal = false,
                    EndpointStatus.RECONNECTING,
                    ParticipantAvailabilityKind.RECONNECTING
                )
            ParticipantDisplayStateMapper.ParticipantDisplayState.JOINING ->
                ParticipantPresentationState(
                    facts.moduleId,
                    isLocal = false,
                    EndpointStatus.CONNECTING,
                    ParticipantAvailabilityKind.JOINING
                )
            ParticipantDisplayStateMapper.ParticipantDisplayState.ONLINE ->
                ParticipantPresentationState(
                    facts.moduleId,
                    isLocal = false,
                    if (facts.speaking) EndpointStatus.SPEAKING else EndpointStatus.ONLINE,
                    ParticipantAvailabilityKind.NONE
                )
        }
    }

    fun aggregateAvailabilityHint(
        states: List<ParticipantPresentationState>,
        localCaptureBlocked: Boolean
    ): String? {
        if (localCaptureBlocked) {
            return "Microphone unavailable"
        }
        val joining = states.filter {
            !it.isLocal && it.availabilityKind == ParticipantAvailabilityKind.JOINING
        }
        val reconnecting = states.filter {
            !it.isLocal && it.availabilityKind == ParticipantAvailabilityKind.RECONNECTING
        }
        return when {
            joining.size == 1 -> "${joining.single().moduleId} joining..."
            joining.size > 1 -> "${joining.size} joining..."
            reconnecting.size == 1 -> "${reconnecting.single().moduleId} reconnecting..."
            reconnecting.size > 1 -> "${reconnecting.size} reconnecting..."
            else -> null
        }
    }

    fun renderConferencePresence(
        presence: ConferencePresenceProjection,
        participantFacts: List<ParticipantPresentationFacts>,
        localCaptureBlocked: Boolean = false
    ): ConferencePresenceUi {
        val states = participantFacts.map(::resolveParticipantPresentation)
        val avatarStatuses = states.associate { it.moduleId to it.endpointStatus }
        val endpoints = states.map { state ->
            EndpointUiItem(
                key = "${state.moduleId}-presentation",
                displayLabel = state.moduleId,
                status = state.endpointStatus,
                signalBars = ConferenceEndpointStatusMapper.signalBarsFor(state.endpointStatus),
                isLocal = state.isLocal
            )
        }
        return ConferencePresenceUi(
            headerLabel = participantCountLabel(presence.joinedCount),
            connectingHint = aggregateAvailabilityHint(states, localCaptureBlocked),
            avatarStatuses = avatarStatuses,
            participantStates = states
        )
    }
}

data class ConferencePresenceUi(
    val headerLabel: String,
    val connectingHint: String?,
    val avatarStatuses: Map<String, EndpointStatus>,
    val participantStates: List<MeetingPresenceDisplay.ParticipantPresentationState> = emptyList()
)
