package com.talkback.core.webrtc.conferenceaudio

import java.nio.ByteBuffer
import java.nio.ByteOrder

internal object PcmFrameCodec {
    fun fromInbound(
        audioData: ByteBuffer,
        bitsPerSample: Int,
        sampleRate: Int,
        numberOfChannels: Int,
        numberOfFrames: Int,
        targetFormat: ConferencePcmFormat = ConferencePcmFormat.CANONICAL
    ): PcmFrame? {
        if (bitsPerSample != 16 || numberOfChannels != 1) return null
        if (sampleRate != targetFormat.sampleRateHz) return null
        val expectedSamples = numberOfFrames * numberOfChannels
        if (expectedSamples != targetFormat.samplesPerFrame) return null
        val dup = audioData.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val samples = ShortArray(expectedSamples)
        for (i in samples.indices) {
            if (!dup.hasRemaining()) return null
            samples[i] = dup.short
        }
        return PcmFrame(samples, targetFormat)
    }

    fun toByteBuffer(frame: PcmFrame): ByteBuffer {
        val buffer = ByteBuffer.allocate(frame.samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        frame.samples.forEach { buffer.putShort(it) }
        buffer.flip()
        return buffer
    }
}
