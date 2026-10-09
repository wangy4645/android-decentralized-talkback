package com.talkback.core.webrtc

import java.nio.ByteBuffer

/** Receives PCM from the process-local microphone capture path. */
fun interface LocalOutboundPcmSink {
    fun onPcm(
        audioData: ByteBuffer,
        bitsPerSample: Int,
        sampleRate: Int,
        numberOfChannels: Int,
        numberOfFrames: Int
    )
}
