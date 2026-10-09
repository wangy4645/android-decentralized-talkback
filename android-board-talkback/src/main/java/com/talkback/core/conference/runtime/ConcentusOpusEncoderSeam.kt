package com.talkback.core.conference.runtime

import com.talkback.core.conference.wire.ConferenceWireConstants
import io.github.jaredmdobson.concentus.OpusApplication
import io.github.jaredmdobson.concentus.OpusEncoder
import io.github.jaredmdobson.concentus.OpusSignal

/**
 * Production TX Opus encode seam. One encoder per source incarnation.
 * Recreate only on [release] or [sourceGeneration] change — never per 20 ms frame.
 */
class ConcentusOpusEncoderSeam {
    private var encoder: OpusEncoder? = null
    private var boundSourceGeneration: Long? = null

    val boundGenerationForHarness: Long?
        get() = boundSourceGeneration

    fun encode(
        pcm: ShortArray,
        sourceGeneration: Long,
    ): ByteArray {
        require(pcm.size == OpusCodecConstants.FRAME_SAMPLES_20MS)
        if (encoder == null || boundSourceGeneration != sourceGeneration) {
            encoder = newProductionEncoder()
            boundSourceGeneration = sourceGeneration
        }
        val live = encoder ?: error("encoder")
        val packet = ByteArray(OpusCodecConstants.MAX_PACKET_BYTES)
        val encoded =
            live.encode(
                pcm,
                0,
                OpusCodecConstants.FRAME_SAMPLES_20MS,
                packet,
                0,
                packet.size,
            )
        require(encoded > 0) { "opus encode failed" }
        require(encoded <= ConferenceWireConstants.MAX_OPUS_PAYLOAD_OCTETS) {
            "opus payload $encoded B exceeds wire max ${ConferenceWireConstants.MAX_OPUS_PAYLOAD_OCTETS} B"
        }
        return packet.copyOf(encoded)
    }

    fun release() {
        encoder = null
        boundSourceGeneration = null
    }

    companion object {
        fun newProductionEncoder(): OpusEncoder {
            val encoder =
                OpusEncoder(
                    OpusCodecConstants.SAMPLE_RATE_HZ,
                    OpusCodecConstants.CHANNELS,
                    OpusApplication.OPUS_APPLICATION_VOIP,
                )
            encoder.setSignalType(OpusSignal.OPUS_SIGNAL_VOICE)
            encoder.bitrate = 16_000
            encoder.complexity = 5
            return encoder
        }
    }
}
