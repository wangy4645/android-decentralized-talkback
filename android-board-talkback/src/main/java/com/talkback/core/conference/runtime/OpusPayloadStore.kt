package com.talkback.core.conference.runtime

/**
 * Post-P02 Opus plaintext staging for real decoder seam (Phase 1 Slice 3).
 * Keyed by source × incarnation × media slot — not a new authority surface.
 *
 * P3: writes only from pipeline after jitter [FrameAdmitDisposition.QUEUED];
 * consumption gated by [SlotPullDisposition.DECODE_FRAME] at mix time.
 */
class OpusPayloadStore {
    private data class Key(
        val sourceIdentity: String,
        val incarnationId: Long,
        val mediaSlot: Long,
    )

    private val lock = Any()
    private val payloads = linkedMapOf<Key, ByteArray>()

    fun put(
        sourceIdentity: String,
        incarnationId: Long,
        mediaSlot: Long,
        opusPayload: ByteArray,
    ) {
        synchronized(lock) {
            payloads[Key(sourceIdentity, incarnationId, mediaSlot)] = opusPayload.copyOf()
        }
    }

    fun take(
        sourceIdentity: String,
        incarnationId: Long,
        mediaSlot: Long,
    ): ByteArray? =
        synchronized(lock) {
            payloads.remove(Key(sourceIdentity, incarnationId, mediaSlot))
        }

    fun peek(
        sourceIdentity: String,
        incarnationId: Long,
        mediaSlot: Long,
    ): ByteArray? =
        synchronized(lock) {
            payloads[Key(sourceIdentity, incarnationId, mediaSlot)]?.copyOf()
        }

    fun size(): Int =
        synchronized(lock) {
            payloads.size
        }

    fun evictIncarnation(
        sourceIdentity: String,
        incarnationId: Long,
    ) {
        synchronized(lock) {
            payloads.keys.removeAll { key ->
                key.sourceIdentity == sourceIdentity && key.incarnationId == incarnationId
            }
        }
    }

    fun evictSourceIdentity(sourceIdentity: String) {
        synchronized(lock) {
            payloads.keys.removeAll { key -> key.sourceIdentity == sourceIdentity }
        }
    }

    fun clearAll() {
        synchronized(lock) {
            payloads.clear()
        }
    }
}

object OpusCodecConstants {
    const val SAMPLE_RATE_HZ: Int = MediaMixConstants.SAMPLE_RATE_HZ
    const val CHANNELS: Int = 1
    /** 20 ms @ 48 kHz mono. */
    const val FRAME_SAMPLES_20MS: Int = 960
    const val MAX_PACKET_BYTES: Int = 400
}
