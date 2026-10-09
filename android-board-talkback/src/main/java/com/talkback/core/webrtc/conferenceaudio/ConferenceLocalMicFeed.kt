package com.talkback.core.webrtc.conferenceaudio

import com.talkback.core.model.ModuleId
import com.talkback.core.session.GroupMediaTopology
import com.talkback.core.session.SessionType
import com.talkback.core.session.TalkbackSession
import com.talkback.core.webrtc.LocalMicFrameSource
import com.talkback.core.webrtc.LocalOutboundPcmSink
import java.util.concurrent.ConcurrentHashMap

/**
 * ADR-0056 Phase 1a-5 — anchor local mic → [ConferenceAudioBus.pushLocalMicrophoneFrame].
 * Gating uses [TalkbackSession.anchorModuleId] only (not conference host / initiator).
 */
class ConferenceLocalMicFeed(
    private val frameSource: LocalMicFrameSource,
    private val pushFrame: (sessionId: String, frame: PcmFrame) -> Unit,
    private val busDiagnostics: (sessionId: String) -> ConferenceAudioBusDiagnostics?,
    private val observability: ConferenceAudioPathObservability
) {
    private data class Binding(
        val sessionId: String,
        val endpointId: String,
        val releaseSource: () -> Unit
    )

    private val bindings = ConcurrentHashMap<String, Binding>()

    fun isFeeding(sessionId: String): Boolean = bindings.containsKey(sessionId)

    fun syncSession(session: TalkbackSession, localModuleId: ModuleId, busActive: Boolean) {
        val shouldFeed = shouldFeedAnchorMic(session, localModuleId, busActive)
        if (!shouldFeed) {
            clearSession(session.id)
            publishSnapshot(session, localMicActive = false)
            return
        }
        if (bindings.containsKey(session.id)) {
            publishSnapshot(session, localMicActive = true)
            return
        }
        val assembler = PcmFrameAssembler()
        val release = frameSource.acquire(
            LocalOutboundPcmSink { buffer, bits, rate, channels, frames ->
                if (bindings[session.id] == null) return@LocalOutboundPcmSink
                assembler.append(buffer, bits, rate, channels, frames) { frame ->
                    pushFrame(session.id, frame)
                }
            }
        )
        bindings[session.id] = Binding(
            sessionId = session.id,
            endpointId = session.local.endpointId.value,
            releaseSource = release
        )
        publishSnapshot(session, localMicActive = true)
    }

    fun clearSession(sessionId: String) {
        bindings.remove(sessionId)?.releaseSource?.invoke()
    }

    fun publishInjectionFailure(
        session: TalkbackSession,
        targetModuleId: String,
        failure: PcmInjectionFailure
    ) {
        publishSnapshot(
            session = session,
            localMicActive = bindings.containsKey(session.id),
            injectionFailure = true,
            failureReason = failure,
            targetModuleId = targetModuleId
        )
    }

    private fun shouldFeedAnchorMic(
        session: TalkbackSession,
        localModuleId: ModuleId,
        busActive: Boolean
    ): Boolean {
        if (!busActive) return false
        if (session.type != SessionType.CONFERENCE || !session.accepted) return false
        if (session.muted) return false
        if (session.mediaTopology != GroupMediaTopology.ANCHOR) return false
        val anchor = session.anchorModuleId ?: return false
        return anchor == localModuleId
    }

    private fun publishSnapshot(
        session: TalkbackSession,
        localMicActive: Boolean,
        injectionFailure: Boolean = false,
        failureReason: PcmInjectionFailure? = null,
        targetModuleId: String? = null
    ) {
        val diagnostics = busDiagnostics(session.id)
        val localId = localModuleIdValue(session)
        val anchorId = session.anchorModuleId?.value ?: ""
        val mode = ParticipantMediaModePolicy.resolve(localId, anchorId, localId)
        observability.publish(
            ConferenceAudioPathFact(
                conferenceId = session.id,
                endpointId = session.local.endpointId.value,
                participantMediaMode = mode,
                localMicActive = localMicActive,
                muted = session.muted,
                mixerSourceCount = diagnostics?.mixerSourceCount ?: 0,
                injectionPortOpen = diagnostics?.injectionPortOpen ?: false,
                injectionFailure = injectionFailure,
                failureReason = failureReason,
                topologyMode = session.mediaTopology.name,
                anchorModuleId = session.anchorModuleId?.value,
                targetModuleId = targetModuleId
            )
        )
    }

    private fun localModuleIdValue(session: TalkbackSession): String =
        session.local.moduleId.value
}

data class ConferenceAudioBusDiagnostics(
    val participantMediaMode: ParticipantMediaMode,
    val mixerSourceCount: Int,
    val injectionPortOpen: Boolean,
    val activeTargetCount: Int
)
