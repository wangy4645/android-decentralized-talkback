package com.talkback.appprod.ui

import android.content.Context
import com.talkback.app.TalkbackSessionSnapshot
import com.talkback.core.session.ConferencePresenceProjection
import com.talkback.core.session.CppMembership

internal data class ConferenceRosterRowPlan(
    val moduleId: String,
    val endpointStatus: EndpointStatus,
    val isLocal: Boolean,
)

/** Presence-driven conference roster rows; status from visible facts with CPP projection fallback. */
internal object ConferenceRosterEndpointList {

    fun joinedModuleIds(presence: ConferencePresenceProjection): List<String> =
        presence.participants
            .asSequence()
            .filter { it.membership == CppMembership.JOINED }
            .map { it.moduleId }
            .toList()

    fun resolveRowStatus(
        visible: MeetingPresenceDisplay.ParticipantPresentationState?,
        projection: MeetingPresenceDisplay.ParticipantPresentationState?,
        indirectJoined: Boolean,
    ): EndpointStatus {
        val chosen = visible ?: projection
        if (chosen == null) {
            return if (indirectJoined) EndpointStatus.JOINED else EndpointStatus.CONNECTING
        }
        val status = chosen.endpointStatus
        if (indirectJoined && status == EndpointStatus.CONNECTING) {
            return EndpointStatus.JOINED
        }
        return status
    }

    fun planRows(
        context: Context,
        session: TalkbackSessionSnapshot,
        localModuleId: String,
        speakingModuleId: String?,
        conferenceMuted: Boolean,
        mediaUnavailablePeer: (String) -> Boolean,
    ): List<ConferenceRosterRowPlan> {
        val presence = session.conferencePresenceProjection ?: return emptyList()
        val visibleParticipants = session.visibleParticipants
        val indirectSpokes = joinedIndirectSpokes(session, localModuleId)
        val failureModuleIds = ConferencePresenceUiFuse.classifiedFailureModuleIds(visibleParticipants)
        val participantFacts = visibleParticipants.map { participant ->
            MeetingPresenceDisplay.ParticipantPresentationFacts(
                sessionId = session.sessionId,
                moduleId = participant.moduleId,
                isLocal = participant.isLocal,
                displayState = participant.displayState,
                isRecoveringPeer = participant.moduleId in presence.recoveringPeers &&
                    participant.moduleId !in failureModuleIds,
                mediaUnavailablePeer = mediaUnavailablePeer(participant.moduleId),
                speaking = speakingModuleId != null &&
                    speakingModuleId.equals(participant.moduleId, ignoreCase = true),
            )
        }
        val visibleUi = MeetingPresenceDisplay.renderConferencePresence(
            context = context,
            presence = presence,
            participantFacts = participantFacts,
            localCaptureBlocked = conferenceMuted,
        )
        val visibleByModule = visibleUi.participantStates.associateBy { it.moduleId }
        val projectionUi = MeetingPresenceDisplay.renderFromProjection(
            context = context,
            projection = presence,
            localModuleId = localModuleId,
            speakingModuleId = speakingModuleId,
            localCaptureBlocked = conferenceMuted,
        )
        val projectionByModule = projectionUi.participantStates.associateBy { it.moduleId }
        return joinedModuleIds(presence).map { moduleId ->
            val visible = visibleByModule[moduleId]
            val projection = projectionByModule[moduleId]
            val status = resolveRowStatus(visible, projection, moduleId in indirectSpokes)
            val isLocal = visible?.isLocal ?: projection?.isLocal
                ?: moduleId.equals(localModuleId, ignoreCase = true)
            ConferenceRosterRowPlan(moduleId, status, isLocal)
        }
    }
}