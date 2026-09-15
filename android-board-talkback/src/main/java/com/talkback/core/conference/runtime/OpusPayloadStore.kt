package com.talkback.core.conference.runtime

/**
 * Post-P02 Opus plaintext staging for real decoder seam (Phase 1 Slice 3).
 * Keyed by source × incarnation × media slot — not a new authority surface.
 */
class OpusPayloadStore {
    private data class Key(
        val sourceIdentity: String,
        val incarnationId: Long,
        val mediaSlot: Long,
    )

    private val payloads = linkedMapOf<Key, ByteArray>()

    fun put(
        sourceIdentity: String,
        incarnationId: Long,
        mediaSlot: Long,
        opusPayload: ByteArray,
    ) {
        payloads[Key(sourceIdentity, incarnationId, mediaSlot)] = opusPayload.copyOf()
    }

    fun take(
        sourceIdentity: String,
        incarnationId: Long,
        mediaSlot: Long,
    ): ByteArray? = payloads.remove(Key(sourceIdentity, incarnationId, mediaSlot))

    fun peek(
        sourceIdentity: String,
        incarnationId: Long,
        mediaSlot: Long,
    ): ByteArray? = payloads[Key(sourceIdentity, incarnationId, mediaSlot)]?.copyOf()

    fun size(): Int = payloads.size
}

object OpusCodecConstants {
    const val SAMPLE_RATE_HZ: Int = MediaMixConstants.SAMPLE_RATE_HZ
    const val CHANNELS: Int = 1
    /** 20 ms @ 48 kHz mono. */
    const val FRAME_SAMPLES_20MS: Int = 960
    const val MAX_PACKET_BYTES: Int = 400
}
