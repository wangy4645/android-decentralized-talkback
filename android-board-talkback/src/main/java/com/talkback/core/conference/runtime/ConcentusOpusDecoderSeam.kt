package com.talkback.core.conference.runtime

import com.talkback.core.conference.wire.ConferenceWireConstants
import io.github.jaredmdobson.concentus.OpusApplication
import io.github.jaredmdobson.concentus.OpusDecoder
import io.github.jaredmdobson.concentus.OpusEncoder
import io.github.jaredmdobson.concentus.OpusSignal
import kotlin.math.PI
import kotlin.math.sin

/**
 * Real Opus decode seam using Concentus (RFC 6716).
 *
 * P2: one [OpusDecoder] per admitted pool seat (source × incarnation), synchronized;
 * physical instances are created only via [onPoolAllocated] after [LiveDecoderPool.allocate].
 */
class ConcentusOpusDecoderSeam(
    private val payloadStore: OpusPayloadStore,
) : OpusDecodeSeam,
    OpusDecoderLifecycleSeam {
    private data class DecoderEntry(
        val incarnationId: Long,
        val decoder: OpusDecoder,
    )

    private val lock = Any()
    private val decoders = linkedMapOf<String, DecoderEntry>()

    var decodeInvocationCount: Long = 0
        private set
    var decodeSuccessCount: Long = 0
        private set
    var lastDecodeDurationUs: Long = 0
        private set

    /** Harness / desk — matches [LiveDecoderPool.liveCount] when pool-bound. */
    val physicalDecoderCount: Int
        get() =
            synchronized(lock) {
                decoders.size
            }

    override fun onPoolAllocated(
        sourceIdentity: String,
        incarnationId: Long,
    ) {
        synchronized(lock) {
            val existing = decoders[sourceIdentity]
            if (existing != null && existing.incarnationId == incarnationId) {
                return
            }
            if (existing == null && decoders.size >= MediaMixConstants.MAX_LIVE_DECODERS) {
                return
            }
            decoders[sourceIdentity] =
                DecoderEntry(
                    incarnationId = incarnationId,
                    decoder = newOpusDecoder(),
                )
        }
    }

    override fun evict(
        sourceIdentity: String,
        incarnationId: Long,
    ) {
        synchronized(lock) {
            val cur = decoders[sourceIdentity]
            if (cur != null && cur.incarnationId == incarnationId) {
                decoders.remove(sourceIdentity)
            }
        }
    }

    override fun evictSourceSeat(sourceIdentity: String) {
        synchronized(lock) {
            decoders.remove(sourceIdentity)
        }
    }

    override fun clearAll() {
        synchronized(lock) {
            decoders.clear()
        }
    }

    override fun decode(
        sourceIdentity: String,
        incarnationId: Long,
        mediaSlot: Long,
        nowMs: Long,
    ): PcmFrame? {
        val opus =
            payloadStore.take(sourceIdentity, incarnationId, mediaSlot)
                ?: return null
        synchronized(lock) {
            decodeInvocationCount += 1
            val entry = decoders[sourceIdentity] ?: return null
            if (entry.incarnationId != incarnationId) {
                return null
            }
            val pcm = ShortArray(OpusCodecConstants.FRAME_SAMPLES_20MS)
            val startNs = System.nanoTime()
            return try {
                val samplesDecoded =
                    entry.decoder.decode(
                        opus,
                        0,
                        opus.size,
                        pcm,
                        0,
                        pcm.size,
                        false,
                    )
                lastDecodeDurationUs = (System.nanoTime() - startNs) / 1_000L
                if (samplesDecoded <= 0) {
                    null
                } else {
                    decodeSuccessCount += 1
                    PcmFrame(
                        samples = if (samplesDecoded == pcm.size) pcm else pcm.copyOf(samplesDecoded),
                        usableForMix = true,
                    )
                }
            } catch (_: Exception) {
                lastDecodeDurationUs = (System.nanoTime() - startNs) / 1_000L
                null
            }
        }
    }

    private fun newOpusDecoder(): OpusDecoder =
        OpusDecoder(
            OpusCodecConstants.SAMPLE_RATE_HZ,
            OpusCodecConstants.CHANNELS,
        )
}

/**
 * Pool-bound physical Opus decoder lifecycle (P2).
 */
interface OpusDecoderLifecycleSeam {
    fun onPoolAllocated(
        sourceIdentity: String,
        incarnationId: Long,
    )

    /** Aligns with [LiveDecoderPool.hardFenceRelease]. */
    fun evict(
        sourceIdentity: String,
        incarnationId: Long,
    )

    fun evictSourceSeat(sourceIdentity: String)

    fun clearAll()
}

/**
 * Encode deterministic 20 ms Opus frames for harness / Slice 3 closure tests.
 */
object OpusTestVectors {
    fun pcmTone(
        frequencyHz: Double = 440.0,
        amplitude: Short = 8_000,
    ): ShortArray {
        val pcm = ShortArray(OpusCodecConstants.FRAME_SAMPLES_20MS)
        for (i in pcm.indices) {
            val t = i.toDouble() / OpusCodecConstants.SAMPLE_RATE_HZ
            pcm[i] = (sin(2.0 * PI * frequencyHz * t) * amplitude).toInt().toShort()
        }
        return pcm
    }

    fun encodePcm(pcm: ShortArray): ByteArray {
        require(pcm.size == OpusCodecConstants.FRAME_SAMPLES_20MS)
        val encoder =
            OpusEncoder(
                OpusCodecConstants.SAMPLE_RATE_HZ,
                OpusCodecConstants.CHANNELS,
                OpusApplication.OPUS_APPLICATION_VOIP,
            )
        encoder.setSignalType(OpusSignal.OPUS_SIGNAL_VOICE)
        encoder.bitrate = 16_000
        encoder.complexity = 5
        val packet = ByteArray(OpusCodecConstants.MAX_PACKET_BYTES)
        val encoded =
            encoder.encode(
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

    fun encodeTone(frequencyHz: Double = 440.0): ByteArray = encodePcm(pcmTone(frequencyHz))
}
