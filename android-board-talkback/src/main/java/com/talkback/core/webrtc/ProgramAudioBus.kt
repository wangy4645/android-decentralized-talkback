package com.talkback.core.webrtc

import com.talkback.core.model.ModuleId
import com.talkback.core.session.GroupMediaTopology
import com.talkback.core.session.TalkbackSession
import com.talkback.core.util.TalkbackLog
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

/**
 * Anchor-side relay bus: taps the floor holder inbound PCM and fans out to listener PCs.
 */
class ProgramAudioBus(
    private val groupEngine: (String) -> WebRtcAudioEngine?
) {
    private val inboundTapBySession = ConcurrentHashMap<String, InboundAudioTap>()

    fun updateFloorHolder(
        session: TalkbackSession,
        localModuleId: ModuleId,
        floorHolderModuleId: String?,
        activeRemoteModuleIds: Set<String> = emptySet()
    ) {
        if (session.mediaTopology != GroupMediaTopology.ANCHOR) {
            clear(session.id)
            return
        }
        val anchorId = session.anchorModuleId ?: return
        if (anchorId != localModuleId) {
            clear(session.id)
            return
        }
        val holder = floorHolderModuleId?.takeIf { it.isNotBlank() }
        applyRelayRouting(session, localModuleId, holder, activeRemoteModuleIds)
    }

    fun clear(sessionId: String) {
        inboundTapBySession.remove(sessionId)?.release()
    }

    private fun applyRelayRouting(
        session: TalkbackSession,
        localModuleId: ModuleId,
        floorHolderModuleId: String?,
        activeRemoteModuleIds: Set<String>
    ) {
        val localId = localModuleId.value
        val remoteIds = if (activeRemoteModuleIds.isEmpty()) {
            session.remotePeersByModule.keys
        } else {
            session.remotePeersByModule.keys.filter { it in activeRemoteModuleIds }
        }
        inboundTapBySession.remove(session.id)?.release()

        if (floorHolderModuleId == null) {
            remoteIds.forEach { groupEngine(it)?.setProgramRelayMode(ProgramRelayMode.MICROPHONE) }
            return
        }

        if (floorHolderModuleId == localId) {
            remoteIds.forEach { groupEngine(it)?.setProgramRelayMode(ProgramRelayMode.MICROPHONE) }
            return
        }

        val floorEngine = groupEngine(floorHolderModuleId)
        if (floorEngine == null) {
            TalkbackLog.w(
                "ProgramAudioBus: floor holder engine missing $floorHolderModuleId, " +
                    "falling back listeners to MICROPHONE"
            )
            remoteIds.forEach { groupEngine(it)?.setProgramRelayMode(ProgramRelayMode.MICROPHONE) }
            return
        }

        val tap = InboundAudioTap(floorEngine) { buffer, bits, rate, channels, frames ->
            remoteIds.forEach { remoteId ->
                if (remoteId == floorHolderModuleId) return@forEach
                groupEngine(remoteId)?.feedProgramPcm(buffer, bits, rate, channels, frames)
            }
        }
        inboundTapBySession[session.id] = tap

        remoteIds.forEach { remoteId ->
            val mode = if (remoteId == floorHolderModuleId) {
                ProgramRelayMode.MICROPHONE
            } else {
                ProgramRelayMode.PROGRAM
            }
            groupEngine(remoteId)?.setProgramRelayMode(mode)
        }
    }

    private class InboundAudioTap(
        private val engine: WebRtcAudioEngine,
        onPcm: (ByteBuffer, Int, Int, Int, Int) -> Unit
    ) {
        private val sink = object : InboundPcmSink {
            override fun onPcm(
                audioData: ByteBuffer,
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
