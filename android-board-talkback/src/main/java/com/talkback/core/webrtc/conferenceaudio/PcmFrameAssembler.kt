package com.talkback.core.webrtc.conferenceaudio

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Accumulates variable-size mic PCM into canonical 10 ms [PcmFrame]s. */
class PcmFrameAssembler(
    private val format: ConferencePcmFormat = ConferencePcmFormat.CANONICAL
) {
    private val pending = ArrayList<Short>(format.samplesPerFrame)

    fun append(
        audioData: ByteBuffer,
        bitsPerSample: Int,
        sampleRate: Int,
        numberOfChannels: Int,
        numberOfFrames: Int,
        onFrame: (PcmFrame) -> Unit
    ) {
        if (bitsPerSample != 16 || sampleRate != format.sampleRateHz || numberOfChannels != format.channels) {
            return
        }
        val dup = audioData.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        repeat(numberOfFrames * numberOfChannels) {
            if (!dup.hasRemaining()) return
            pending.add(dup.short)
            if (pending.size >= format.samplesPerFrame) {
                val samples = ShortArray(format.samplesPerFrame)
                for (i in samples.indices) {
                    samples[i] = pending.removeAt(0)
                }
                onFrame(PcmFrame(samples, format))
            }
        }
    }
}
