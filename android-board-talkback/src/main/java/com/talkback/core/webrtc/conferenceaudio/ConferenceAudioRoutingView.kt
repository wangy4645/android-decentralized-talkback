package com.talkback.core.webrtc.conferenceaudio

import com.talkback.core.model.ModuleId
import com.talkback.core.session.GroupMediaTopology
import com.talkback.core.session.SessionType
import com.talkback.core.session.TalkbackSession

/**
 * Immutable read-only routing input for [com.talkback.core.webrtc.ConferenceAudioBus].
 * Does not admit topology; does not mutate session state.
 */
data class ConferenceAudioRoutingView(
    val sessionId: String,
    val localModuleId: String,
    val anchorModuleId: String,
    val remoteParticipantIds: List<String>,
    val topologyMode: GroupMediaTopology
) {
    val anchorLocalMode: ParticipantMediaMode =
        ParticipantMediaModePolicy.resolve(localModuleId, anchorModuleId, localModuleId)

    fun mediaModeFor(participantModuleId: String): ParticipantMediaMode =
        ParticipantMediaModePolicy.resolve(localModuleId, anchorModuleId, participantModuleId)

    companion object {
        fun fromSession(
            session: TalkbackSession,
            localModuleId: ModuleId
        ): ConferenceAudioRoutingView? {
            if (session.type != SessionType.CONFERENCE) return null
            if (session.mediaTopology != GroupMediaTopology.ANCHOR) return null
            val anchorId = session.anchorModuleId?.value ?: return null
            if (anchorId != localModuleId.value) return null
            return ConferenceAudioRoutingView(
                sessionId = session.id,
                localModuleId = localModuleId.value,
                anchorModuleId = anchorId,
                remoteParticipantIds = session.remotePeersByModule.keys.sorted(),
                topologyMode = session.mediaTopology
            )
        }
    }
}
