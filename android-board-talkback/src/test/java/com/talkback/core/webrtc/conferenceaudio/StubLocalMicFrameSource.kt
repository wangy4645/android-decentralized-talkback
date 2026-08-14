package com.talkback.core.webrtc.conferenceaudio

import com.talkback.core.webrtc.LocalMicFrameSource
import com.talkback.core.webrtc.LocalOutboundPcmSink
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList

class StubLocalMicFrameSource : LocalMicFrameSource {
    private val sinks = CopyOnWriteArrayList<LocalOutboundPcmSink>()

    override fun acquire(sink: LocalOutboundPcmSink): () -> Unit {
        sinks.add(sink)
        return { sinks.remove(sink) }
    }

    fun emit(frame: PcmFrame) {
        val buffer = PcmFrameCodec.toByteBuffer(frame)
        val format = frame.format
        sinks.forEach { sink ->
            sink.onPcm(
                buffer.duplicate(),
                format.bitsPerSample,
                format.sampleRateHz,
                format.channels,
                frame.samples.size / format.channels
            )
        }
    }

    fun emitConstant(level: Short = 5_000) {
        emit(PcmFrame.constantLevel(level, ConferencePcmFormat.CANONICAL))
    }
}
