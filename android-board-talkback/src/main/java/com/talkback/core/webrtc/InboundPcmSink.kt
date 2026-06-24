package com.talkback.core.webrtc

import java.nio.ByteBuffer

/** Receives decoded PCM from a remote inbound audio track. */
fun interface InboundPcmSink {
    fun onPcm(
        audioData: ByteBuffer,
        bitsPerSample: Int,
        sampleRate: Int,
        numberOfChannels: Int,
        numberOfFrames: Int
    )
}
