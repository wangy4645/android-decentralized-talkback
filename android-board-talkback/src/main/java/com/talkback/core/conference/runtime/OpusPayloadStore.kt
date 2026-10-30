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

    /**
     * Production TX bitrate (2026-10-27 five-device field: 16 kbps SILK-WB sounded hollow).
     * 24 kbps puts Opus VOIP in hybrid mode (SILK + CELT above 8 kHz). CBR so every 20 ms payload is
     * exactly 60 B, inside the ADR-0058 frozen wire cap (`MAX_OPUS_PAYLOAD_OCTETS` = 72) — VBR peaks would
     * not be. Must satisfy TX_BITRATE_BPS * 0.020 / 8 <= 72.
     */
    const val TX_BITRATE_BPS: Int = 24_000
    const val TX_COMPLEXITY: Int = 5
    const val TX_USE_CBR: Boolean = true
}
