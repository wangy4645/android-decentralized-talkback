package com.talkback.core.webrtc

/**
 * Profile01 multicast TX wire authority — POST_SOFTWARE_APM_PRE_ENCODER PCM only.
 */
object PostApmLocalMicFrameSource : LocalMicFrameSource {
    override fun acquire(sink: LocalOutboundPcmSink): () -> Unit = PostApmPcmDispatcher.acquire(sink)
}
