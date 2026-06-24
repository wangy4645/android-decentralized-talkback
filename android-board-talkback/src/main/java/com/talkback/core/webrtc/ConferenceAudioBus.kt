package com.talkback.core.webrtc

import com.talkback.core.model.ModuleId
import com.talkback.core.session.GroupMediaTopology
import com.talkback.core.session.SessionType
import com.talkback.core.session.TalkbackSession
import com.talkback.core.util.TalkbackLog
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

/**
 * Anchor-side SFU-lite for conference: tap each participant inbound PCM and fan out to others.
 */
class ConferenceAudioBus(
    private val engineLookup: (String) -> WebRtcAudioEngine?
) {
    private val tapsBySession = ConcurrentHashMap<String, MutableList<InboundAudioTap>>()

    fun updateParticipants(session: TalkbackSession, localModuleId: ModuleId) {
        if (session.type != SessionType.CONFERENCE ||
            session.mediaTopology != GroupMediaTopology.ANCHOR
        ) {
            clear(session.id)
            return
        }
        val anchorId = session.anchorModuleId ?: return
        if (anchorId != localModuleId) {
            clear(session.id)
            return
        }
        applyRelayRouting(session)
    }

    fun clear(sessionId: String) {
        tapsBySession.remove(sessionId)?.forEach { it.release() }
    }

    private fun applyRelayRouting(session: TalkbackSession) {
        tapsBySession.remove(session.id)?.forEach { it.release() }
        val remoteIds = session.remotePeersByModule.keys.toList()
        if (remoteIds.isEmpty()) return

        val taps = mutableListOf<InboundAudioTap>()
        remoteIds.forEach { sourceId ->
            val sourceEngine = engineLookup(sourceId) ?: return@forEach
            val tap = InboundAudioTap(sourceEngine) { buffer, bits, rate, channels, frames ->
                remoteIds.forEach { targetId ->
                    if (targetId == sourceId) return@forEach
                    engineLookup(targetId)?.feedProgramPcm(buffer, bits, rate, channels, frames)
                }
            }
            taps.add(tap)
        }
        if (taps.isNotEmpty()) {
            tapsBySession[session.id] = taps
        }

        remoteIds.forEach { remoteId ->
            engineLookup(remoteId)?.setProgramRelayMode(ProgramRelayMode.PROGRAM)
        }
        TalkbackLog.i("ConferenceAudioBus: relaying ${remoteIds.size} participants for ${session.id}")
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
