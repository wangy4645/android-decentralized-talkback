package com.talkback.core.media

import com.talkback.core.model.ModuleId
import com.talkback.core.qos.IceConnectivity
import com.talkback.core.session.ConferenceParticipantManager
import com.talkback.core.session.InviteState
import com.talkback.core.session.MediaState
import com.talkback.core.session.ParticipantLifecycleTracer
import com.talkback.core.session.SessionType
import com.talkback.core.session.TalkbackSession

/**
 * RO-7: sole writer for [com.talkback.core.session.ParticipantState.media] on GROUP/UNICAST sessions.
 * Conference media is applied via [ConferenceParticipantManager] from this runtime only.
 */
object MediaRuntime {

    fun onIceStateChanged(
        session: TalkbackSession,
        moduleId: String,
        iceState: String,
        conferenceManager: ConferenceParticipantManager?,
        nowMs: Long = System.currentTimeMillis()
    ) {
        if (moduleId == session.local.moduleId.value) return
        when (session.type) {
            SessionType.CONFERENCE -> {
                val manager = conferenceManager ?: return
                if (!manager.containsParticipant(session.id, moduleId)) return
                applyConferenceMedia(session.id, moduleId, iceState, manager, session.local.moduleId, nowMs)
            }
            else -> {
                if (!session.participants.containsKey(moduleId)) return
                applyGroupMedia(session, moduleId, iceState, nowMs)
            }
        }
    }

    fun syncSessionParticipantMedia(
        session: TalkbackSession,
        moduleIds: Collection<String>,
        iceStateFor: (String) -> String?,
        conferenceManager: ConferenceParticipantManager?,
        nowMs: Long = System.currentTimeMillis()
    ) {
        moduleIds.forEach { moduleId ->
            val ice = iceStateFor(moduleId) ?: "NEW"
            onIceStateChanged(session, moduleId, ice, conferenceManager, nowMs)
        }
    }

    internal fun mediaStateFromIce(ice: String?, fallback: MediaState): MediaState = when {
        IceConnectivity.isConnected(ice) -> MediaState.CONNECTED
        IceConnectivity.isNegotiating(ice) -> MediaState.CONNECTING
        ice == "DISCONNECTED" -> MediaState.RECONNECTING
        ice == "FAILED" -> MediaState.FAILED
        else -> fallback
    }

    private fun applyConferenceMedia(
        sessionId: String,
        moduleId: String,
        iceState: String,
        manager: ConferenceParticipantManager,
        localModuleId: ModuleId,
        nowMs: Long
    ) {
        ParticipantLifecycleTracer.recordTimeline(
            sessionId = sessionId,
            target = moduleId,
            stage = ParticipantLifecycleTracer.Stage.ICE,
            reason = "ice=$iceState",
            source = ParticipantLifecycleTracer.Source.MediaRuntime,
            manager = manager,
            localModuleId = localModuleId
        )
        if (IceConnectivity.isConnected(iceState)) {
            manager.onMediaConnected(sessionId, moduleId)
            return
        }
        val current = manager.participant(sessionId, moduleId).media
        manager.updateMediaState(
            sessionId,
            moduleId,
            mediaStateFromIce(iceState, current)
        )
        manager.participant(sessionId, moduleId).lastMediaChangeMs = nowMs
    }

    private fun applyGroupMedia(
        session: TalkbackSession,
        moduleId: String,
        iceState: String,
        nowMs: Long
    ) {
        val participant = session.participant(moduleId)
        participant.media = mediaStateFromIce(iceState, participant.media)
        participant.lastMediaChangeMs = nowMs
        if (IceConnectivity.isConnected(iceState)) {
            if (participant.invite == InviteState.INVITING || participant.invite == InviteState.RINGING) {
                participant.invite = InviteState.ACCEPTED
            }
        }
    }
}
