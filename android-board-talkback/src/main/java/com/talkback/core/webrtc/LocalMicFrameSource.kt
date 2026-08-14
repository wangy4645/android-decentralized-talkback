package com.talkback.core.webrtc

/**
 * ADR-0056 Phase 1a-5 — process-local microphone PCM source.
 * [release] must stop delivery to the sink.
 */
fun interface LocalMicFrameSource {
    fun acquire(sink: LocalOutboundPcmSink): () -> Unit
}

/** Production tap via shared WebRTC AudioRecord callback. */
object ProcessLocalMicFrameSource : LocalMicFrameSource {
    override fun acquire(sink: LocalOutboundPcmSink): () -> Unit =
        WebRtcSharedFactory.addLocalOutboundSink(sink)
}
