package com.talkback.core.webrtc

import com.talkback.core.model.ModuleId
import com.talkback.core.session.TalkbackSession
import com.talkback.core.util.TalkbackLog
import com.talkback.core.webrtc.conferenceaudio.ConferenceAudioBusDiagnostics
import com.talkback.core.webrtc.conferenceaudio.ConferenceAudioRoutingView
import com.talkback.core.webrtc.conferenceaudio.ConferencePcmFormat
import com.talkback.core.webrtc.conferenceaudio.ParticipantMediaModePolicy
import com.talkback.core.webrtc.conferenceaudio.PcmFrame
import com.talkback.core.webrtc.conferenceaudio.PcmFrameCodec
import com.talkback.core.webrtc.conferenceaudio.PcmInjectionFailure
import com.talkback.core.webrtc.conferenceaudio.PcmInjectionPort
import com.talkback.core.webrtc.conferenceaudio.AudioMixer
import com.talkback.core.webrtc.conferenceaudio.WebRtcPcmInjectionPort
import java.util.concurrent.ConcurrentHashMap

/**
 * Anchor-side conference audio relay (ADR-0056 Phase 1a-4).
 * Reads immutable [ConferenceAudioRoutingView] only — does not admit topology or invoke recovery.
 */
class ConferenceAudioBus(
    private val engineLookup: (String) -> WebRtcAudioEngine?,
    private val injectionPortFactory: (WebRtcAudioEngine) -> PcmInjectionPort = { WebRtcPcmInjectionPort(it) },
    private val onInboundPcm: ((sessionId: String, sourceModuleId: String) -> Unit)? = null,
    private val onInjectionFailure: ((sessionId: String, targetModuleId: String, failure: PcmInjectionFailure) -> Unit)? = null
) {
    private data class TargetRelay(
        val targetId: String,
        val mixer: AudioMixer,
        val port: PcmInjectionPort
    )

    private data class SessionRelayState(
        val view: ConferenceAudioRoutingView,
        val targets: Map<String, TargetRelay>,
        val taps: List<InboundAudioTap>
    )

    private val sessions = ConcurrentHashMap<String, SessionRelayState>()

    /** Read-only: last applied routing view for session (audit / tests). */
    fun routingView(sessionId: String): ConferenceAudioRoutingView? = sessions[sessionId]?.view

    fun mixerStats(sessionId: String, targetModuleId: String): AudioMixer.Stats? =
        sessions[sessionId]?.targets?.get(targetModuleId)?.mixer?.currentStats

    fun diagnostics(sessionId: String): ConferenceAudioBusDiagnostics? {
        val state = sessions[sessionId] ?: return null
        val targets = state.targets.values
        if (targets.isEmpty()) return null
        val sourceCount = targets.maxOf { it.mixer.configuredSourceCount() }
        return ConferenceAudioBusDiagnostics(
            participantMediaMode = state.view.anchorLocalMode,
            mixerSourceCount = sourceCount,
            injectionPortOpen = targets.any { it.port.isOpen },
            activeTargetCount = targets.size
        )
    }

    fun isRelayActive(sessionId: String): Boolean = sessions.containsKey(sessionId)

    fun updateParticipants(session: TalkbackSession, localModuleId: ModuleId) {
        val view = ConferenceAudioRoutingView.fromSession(session, localModuleId)
        if (view == null) {
            clear(session.id)
            return
        }
        updateRouting(view)
    }

    fun updateRouting(view: ConferenceAudioRoutingView) {
        clear(view.sessionId)
        val remoteIds = view.remoteParticipantIds
        if (remoteIds.isEmpty()) return

        val includeLocalMic = ParticipantMediaModePolicy.includesLocalMicrophone(view.anchorLocalMode)
        val targets = linkedMapOf<String, TargetRelay>()
        remoteIds.forEach { targetId ->
            val engine = engineLookup(targetId) ?: return@forEach
            val mixer = AudioMixer()
            remoteIds.filter { it != targetId }.forEach { sourceId ->
                mixer.addSource(sourceId)
            }
            if (includeLocalMic) {
                mixer.addSource(ParticipantMediaModePolicy.LOCAL_MIC_SOURCE_ID)
            }
            val port = injectionPortFactory(engine)
            val open = port.open(ConferencePcmFormat.CANONICAL)
            if (open.isFailure) {
                onInjectionFailure?.invoke(
                    view.sessionId,
                    targetId,
                    PcmInjectionFailure.NOT_OPEN
                )
                return@forEach
            }
            engine.setProgramRelayMode(ProgramRelayMode.PROGRAM)
            targets[targetId] = TargetRelay(targetId, mixer, port)
        }
        if (targets.isEmpty()) return

        val taps = remoteIds.mapNotNull { sourceId ->
            val engine = engineLookup(sourceId) ?: return@mapNotNull null
            InboundAudioTap(engine) { buffer, bits, rate, channels, frames ->
                onInboundPcm?.invoke(view.sessionId, sourceId)
                val frame = PcmFrameCodec.fromInbound(buffer, bits, rate, channels, frames) ?: return@InboundAudioTap
                dispatchRemoteFrame(view.sessionId, sourceId, frame)
            }
        }

        sessions[view.sessionId] = SessionRelayState(view, targets, taps)
        TalkbackLog.i(
            "ConferenceAudioBus: MCU-lite relay ${targets.size} targets for ${view.sessionId} " +
                "mode=${view.anchorLocalMode}"
        )
    }

    /**
     * Anchor [LOCAL_AND_REMOTE]: inject local microphone PCM into all target mixers.
     * Does not mutate routing view.
     */
    fun pushLocalMicrophoneFrame(sessionId: String, frame: PcmFrame) {
        val state = sessions[sessionId] ?: return
        if (!ParticipantMediaModePolicy.includesLocalMicrophone(state.view.anchorLocalMode)) return
        state.targets.values.forEach { target ->
            target.mixer.push(ParticipantMediaModePolicy.LOCAL_MIC_SOURCE_ID, frame)
            renderTarget(sessionId, target)
        }
    }

    fun clear(sessionId: String) {
        sessions.remove(sessionId)?.let { state ->
            state.taps.forEach { it.release() }
            state.targets.values.forEach { it.port.close() }
        }
    }

    private fun dispatchRemoteFrame(sessionId: String, sourceId: String, frame: PcmFrame) {
        val state = sessions[sessionId] ?: return
        state.targets.forEach { (targetId, target) ->
            if (targetId == sourceId) return@forEach
            target.mixer.push(sourceId, frame)
            renderTarget(sessionId, target)
        }
    }

    private fun renderTarget(sessionId: String, target: TargetRelay) {
        val mixed = target.mixer.renderMixedFrame()
        val result = target.port.write(mixed)
        if (result.isFailure) {
            onInjectionFailure?.invoke(sessionId, target.targetId, PcmInjectionFailure.INJECT_FAILED)
        }
    }

    private class InboundAudioTap(
        private val engine: WebRtcAudioEngine,
        onPcm: (java.nio.ByteBuffer, Int, Int, Int, Int) -> Unit
    ) {
        private val sink = object : InboundPcmSink {
            override fun onPcm(
                audioData: java.nio.ByteBuffer,
                bitsPerSample: Int,
                sampleRate: Int,
                numberOfChannels: Int,
                numberOfFrames: Int
            ) {
                onPcm(audioData, bitsPerSample, sampleRate, numberOfChannels, numberOfFrames)
            }
        }

        init {
            engine.setInboundPcmSink(sink)
        }

        fun release() {
            engine.setInboundPcmSink(null)
        }
    }
}
