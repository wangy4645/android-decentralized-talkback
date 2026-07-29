package com.talkback.appprod.ui

import com.talkback.core.session.ConferenceMembershipLifecycle
import com.talkback.core.session.ConferenceParticipantDisplayState
import com.talkback.core.session.ConferencePresenceProjection
import com.talkback.appprod.ui.LocalReachability.ParticipantPresenceState
import com.talkback.appprod.ui.LocalReachability.toMembershipState
import com.talkback.appprod.ui.UserVisibleConnectivityProjection.UserVisibleConnectivityState

/**
 * View-only presence display (ADR-0025 / ADR-0028 / ADR-0034).
 *
 * Connectivity copy (pill / avatar connectivity semantics) is owned by
 * [UserVisibleConnectivityProjection] — not `recoveringPeers` / lifecycle booleans.
 * [LocalReachability] remains for membership LEFT / initial JOINING paths.
 */
object MeetingPresenceDisplay {

    @Volatile
    var receivePathLivenessProvider: ReceivePathLivenessProvider = NoOpReceivePathLivenessProvider

    fun participantCountLabel(joinedCount: Int): String = "$joinedCount Participants"

    enum class ParticipantAvailabilityKind {
        NONE,
        JOINING,
        SYNCING,
        DEGRADED,
        RECONNECTING,
        CAPTURE_BLOCKED
    }

    data class ParticipantPresentationFacts(
        val sessionId: String,
        val moduleId: String,
        val isLocal: Boolean,
        val membership: ConferenceMembershipLifecycle = ConferenceMembershipLifecycle.JOINED,
        val displayState: ConferenceParticipantDisplayState,
        val isRecoveringPeer: Boolean,
        val mediaUnavailablePeer: Boolean,
        val speaking: Boolean,
        val captureBlocked: Boolean = false,
        /** Coarse control sync (negotiation deferred / obligation open). Diagnostic → axis only. */
        val controlSyncPending: Boolean = false,
        /** Coarse peer-edge / control degradation (not media loss). */
        val controlDegraded: Boolean = false
    )

    data class ParticipantPresentationState(
        val moduleId: String,
        val isLocal: Boolean,
        val endpointStatus: EndpointStatus,
        val availabilityKind: ParticipantAvailabilityKind,
        val reachability: LocalReachability.Result,
        val visibleConnectivity: UserVisibleConnectivityState? = null
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
            mediaEverLive = receivePathLivenessProvider.mediaEverLive(
                facts.sessionId,
                facts.moduleId
            )
        )
    }

    internal fun resolveVisibleConnectivity(
        facts: ParticipantPresentationFacts
    ): UserVisibleConnectivityState? {
        if (facts.isLocal) return UserVisibleConnectivityState.CONNECTED
        if (facts.membership.toMembershipState() == LocalReachability.MembershipState.LEFT) {
            return null
        }
        val receivePathLive = receivePathLivenessProvider.receivePathLive(
            facts.sessionId,
            facts.moduleId
        )
        val mediaEverLive = receivePathLivenessProvider.mediaEverLive(
            facts.sessionId,
            facts.moduleId
        )
        if (
            UserVisibleConnectivityProjection.isInitialJoinPath(
                receivePathLive = receivePathLive,
                mediaEverLive = mediaEverLive,
                recovering = facts.isRecoveringPeer,
                mediaUnavailable = facts.mediaUnavailablePeer
            )
        ) {
            return null
        }
        return UserVisibleConnectivityProjection.project(
            UserVisibleConnectivityProjection.deriveAxes(
                receivePathLive = receivePathLive,
                mediaEverLive = mediaEverLive,
                recovering = facts.isRecoveringPeer,
                mediaUnavailable = facts.mediaUnavailablePeer,
                controlDegraded = facts.controlDegraded,
                controlSyncPending = facts.controlSyncPending
            )
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
            return ParticipantPresentationState(
                facts.moduleId,
                isLocal = true,
                status,
                kind,
                reachability,
                UserVisibleConnectivityState.CONNECTED
            )
        }
        if (
            reachability.state == ParticipantPresenceState.LEFT ||
            reachability.state == ParticipantPresenceState.OFFLINE
        ) {
            return ParticipantPresentationState(
                facts.moduleId,
                isLocal = false,
                EndpointStatus.OFFLINE,
                ParticipantAvailabilityKind.NONE,
                reachability,
                visibleConnectivity = null
            )
        }

        val connectivity = resolveVisibleConnectivity(facts)
        if (connectivity == null) {
            // Initial join / membership path — LocalReachability JOINING.
            return ParticipantPresentationState(
                facts.moduleId,
                isLocal = false,
                EndpointStatus.CONNECTING,
                ParticipantAvailabilityKind.JOINING,
                reachability,
                visibleConnectivity = null
            )
        }

        if (facts.speaking && connectivity == UserVisibleConnectivityState.CONNECTED) {
            return ParticipantPresentationState(
                facts.moduleId,
                isLocal = false,
                EndpointStatus.SPEAKING,
                ParticipantAvailabilityKind.NONE,
                reachability,
                connectivity
            )
        }

        val (status, kind) = when (connectivity) {
            UserVisibleConnectivityState.RECONNECTING ->
                EndpointStatus.RECONNECTING to ParticipantAvailabilityKind.RECONNECTING
            UserVisibleConnectivityState.DEGRADED ->
                EndpointStatus.DEGRADED to ParticipantAvailabilityKind.DEGRADED
            UserVisibleConnectivityState.SYNCING ->
                EndpointStatus.SYNCING to ParticipantAvailabilityKind.SYNCING
            UserVisibleConnectivityState.CONNECTED ->
                (if (facts.speaking) EndpointStatus.SPEAKING else EndpointStatus.ONLINE) to
                    ParticipantAvailabilityKind.NONE
        }
        return ParticipantPresentationState(
            facts.moduleId,
            isLocal = false,
            status,
            kind,
            reachability,
            connectivity
        )
    }

    fun aggregateAvailabilityHint(
        states: List<ParticipantPresentationState>,
        localCaptureBlocked: Boolean
    ): String? {
        if (localCaptureBlocked) {
            return "Microphone unavailable"
        }
        val joining = states.filter { !it.isLocal && it.availabilityKind == ParticipantAvailabilityKind.JOINING }
        if (joining.isNotEmpty()) {
            return when (joining.size) {
                1 -> "${joining.single().moduleId} joining..."
                else -> "${joining.size} joining..."
            }
        }
        val connectivityPeers = states.mapNotNull { state ->
            if (state.isLocal) return@mapNotNull null
            val c = state.visibleConnectivity ?: return@mapNotNull null
            state.moduleId to c
        }
        return UserVisibleConnectivityProjection.formatMeetingHint(
            UserVisibleConnectivityProjection.aggregateMeetingConnectivity(connectivityPeers)
        )
    }

    fun renderConferencePresence(
        presence: ConferencePresenceProjection,
        participantFacts: List<ParticipantPresentationFacts>,
        localCaptureBlocked: Boolean = false
    ): ConferencePresenceUi {
        val states = participantFacts.map(::resolveParticipantPresentation)
        val avatarStatuses = states.associate { it.moduleId to it.endpointStatus }
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
