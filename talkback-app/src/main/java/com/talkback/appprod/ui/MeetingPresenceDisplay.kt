package com.talkback.appprod.ui

import com.talkback.core.session.ConferenceMembershipLifecycle
import com.talkback.core.session.ConferenceParticipantDisplayState
import com.talkback.core.session.ConferencePresenceProjection
import com.talkback.appprod.ui.LocalReachability.ParticipantPresenceState
import com.talkback.appprod.ui.LocalReachability.toMembershipState

/**
 * View-only presence display rules (ADR-0025 R30-F / R30-I → ADR-0028 R30-J).
 * Header uses roster membership; hints and avatars use [LocalReachability] only.
 */
object MeetingPresenceDisplay {

    @Volatile
    var receivePathLivenessProvider: ReceivePathLivenessProvider = NoOpReceivePathLivenessProvider

    fun participantCountLabel(joinedCount: Int): String = "$joinedCount Participants"

    enum class ParticipantAvailabilityKind {
        NONE,
        JOINING,
        RECONNECTING,
        CAPTURE_BLOCKED
    }

    data class ParticipantPresentationFacts(
        val sessionId: String,
        val moduleId: String,
        val isLocal: Boolean,
        val membership: ConferenceMembershipLifecycle = ConferenceMembershipLifecycle.JOINED,
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
        val availabilityKind: ParticipantAvailabilityKind,
        val reachability: LocalReachability.Result
    )

    internal fun resolveLocalReachability(facts: ParticipantPresentationFacts): LocalReachability.Result {
        if (facts.isLocal) {
            return LocalReachability.Result(ParticipantPresenceState.ONLINE)
        }
        return LocalReachability.resolve(
            membership = facts.membership.toMembershipState(),
            receivePathLive = receivePathLivenessProvider.receivePathLive(
                facts.sessionId,
                facts.moduleId
            ),
            recovering = facts.isRecoveringPeer,
            mediaUnavailable = facts.mediaUnavailablePeer,
            everConnected = facts.everConnected
        )
    }


    fun resolveParticipantPresentation(
        facts: ParticipantPresentationFacts
    ): ParticipantPresentationState {
        val reachability = resolveLocalReachability(facts)
        if (facts.isLocal) {
            val status = if (facts.speaking) EndpointStatus.SPEAKING else EndpointStatus.ONLINE
            val kind = if (facts.captureBlocked) {
                ParticipantAvailabilityKind.CAPTURE_BLOCKED
            } else {
                ParticipantAvailabilityKind.NONE
            }
            return ParticipantPresentationState(facts.moduleId, isLocal = true, status, kind, reachability)
        }
        val receivePathLive = reachability.state == ParticipantPresenceState.ONLINE
        if (facts.speaking && receivePathLive) {
            return ParticipantPresentationState(
                facts.moduleId,
                isLocal = false,
                EndpointStatus.SPEAKING,
                ParticipantAvailabilityKind.NONE,
                reachability
            )
        }
        return when (reachability.state) {
            ParticipantPresenceState.LEFT,
            ParticipantPresenceState.OFFLINE ->
                ParticipantPresentationState(
                    facts.moduleId,
                    isLocal = false,
                    EndpointStatus.OFFLINE,
                    ParticipantAvailabilityKind.NONE,
                    reachability
                )
            ParticipantPresenceState.RECONNECTING ->
                ParticipantPresentationState(
                    facts.moduleId,
                    isLocal = false,
                    EndpointStatus.RECONNECTING,
                    ParticipantAvailabilityKind.RECONNECTING,
                    reachability
                )
            ParticipantPresenceState.JOINING ->
                ParticipantPresentationState(
                    facts.moduleId,
                    isLocal = false,
                    EndpointStatus.CONNECTING,
                    ParticipantAvailabilityKind.JOINING,
                    reachability
                )
            ParticipantPresenceState.ONLINE ->
                ParticipantPresentationState(
                    facts.moduleId,
                    isLocal = false,
                    if (facts.speaking) EndpointStatus.SPEAKING else EndpointStatus.ONLINE,
                    ParticipantAvailabilityKind.NONE,
                    reachability
                )
        }
    }

    internal fun availabilityKindFromReachability(
        reachability: LocalReachability.Result
    ): ParticipantAvailabilityKind =
        when (reachability.state) {
            ParticipantPresenceState.JOINING -> ParticipantAvailabilityKind.JOINING
            ParticipantPresenceState.RECONNECTING -> ParticipantAvailabilityKind.RECONNECTING
            else -> ParticipantAvailabilityKind.NONE
        }

    internal fun aggregateHintFromReachabilities(
        reachabilities: List<Pair<String, LocalReachability.Result>>,
        localCaptureBlocked: Boolean
    ): String? {
        if (localCaptureBlocked) {
            return "Microphone unavailable"
        }
        val joining = reachabilities.filter { (_, r) ->
            r.state == ParticipantPresenceState.JOINING
        }
        val reconnecting = reachabilities.filter { (_, r) ->
            r.state == ParticipantPresenceState.RECONNECTING
        }
        return when {
            joining.size == 1 -> "${joining.single().first} joining..."
            joining.size > 1 -> "${joining.size} joining..."
            reconnecting.size == 1 -> "${reconnecting.single().first} reconnecting..."
            reconnecting.size > 1 -> "${reconnecting.size} reconnecting..."
            else -> null
        }
    }

    fun aggregateAvailabilityHint(
        states: List<ParticipantPresentationState>,
        localCaptureBlocked: Boolean
    ): String? =
        aggregateHintFromReachabilities(
            reachabilities = states
                .filterNot { it.isLocal }
                .map { it.moduleId to it.reachability },
            localCaptureBlocked = localCaptureBlocked
        )

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
