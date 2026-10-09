package com.talkback.core.conference.transport

import com.talkback.core.conference.runtime.AdmittedMediaFrame
import com.talkback.core.conference.runtime.MediaJitterConstants
import com.talkback.core.conference.runtime.VoiceLevelObservation
import com.talkback.core.conference.wire.ConferenceWireConstants
import com.talkback.core.conference.wire.WireIngressResult

/**
 * Maps Profile 02 ingress acceptance to Profile 03 jitter / Top-K inputs.
 *
 * Phase 1: RTP sequence advances once per media slot (Profile 02 A2/A3).
 */
object WireIngressMediaMapper {
    fun voiceObservation(
        sourceIdentity: String,
        incarnationId: Long,
        voiceActiveAudioLevel: Int,
    ): VoiceLevelObservation {
        val voiceActive = (voiceActiveAudioLevel and 0x80) != 0
        val audioLevel = voiceActiveAudioLevel and 0x7F
        return VoiceLevelObservation(
            sourceIdentity = sourceIdentity,
            incarnationId = incarnationId,
            voiceActive = voiceActive,
            audioLevel = audioLevel,
        )
    }

    fun voiceObservationFromHeader(
        sourceIdentity: String,
        incarnationId: Long,
        headerAndHe: ByteArray,
    ): VoiceLevelObservation {
        require(headerAndHe.size == ConferenceWireConstants.HEADER_PLUS_HE_OCTETS) {
            "header+HE must be ${ConferenceWireConstants.HEADER_PLUS_HE_OCTETS} octets"
        }
        return voiceObservation(
            sourceIdentity = sourceIdentity,
            incarnationId = incarnationId,
            voiceActiveAudioLevel = headerAndHe[31].toInt() and 0xFF,
        )
    }

    fun mediaFrame(
        sourceIdentity: String,
        incarnationId: Long,
        accepted: WireIngressResult.Accepted,
        arrivalMs: Long,
        mediaTimeMs: Long? = null,
    ): AdmittedMediaFrame {
        val mediaSlot = accepted.sequence.toLong()
        return AdmittedMediaFrame(
            sourceIdentity = sourceIdentity,
            incarnationId = incarnationId,
            mediaSlot = mediaSlot,
            mediaTimeMs = mediaTimeMs ?: (mediaSlot * MediaJitterConstants.MEDIA_SLOT_MS),
            arrivalMs = arrivalMs,
        )
    }
}

/**
 * ADR-0058 Layer A — per-incarnation ingress RTP sequence → mediaTimeMs.
 * Must not read or write [RelativeMediaTimeline] session baseSeq.
 */
class PerIncarnationIngressTimelineRegistry {
    private data class Origin(
        val seq: Long,
        val wallMs: Long,
    )

    private val origins = linkedMapOf<String, Origin>()

    fun mediaTimeMs(
        sourceIdentity: String,
        incarnationId: Long,
        sequence: Int,
        arrivalMs: Long,
    ): Long {
        val key = key(sourceIdentity, incarnationId)
        val seq = sequence.toLong()
        val origin =
            origins.getOrPut(key) {
                Origin(seq = seq, wallMs = arrivalMs)
            }
        return origin.wallMs + (seq - origin.seq) * MediaJitterConstants.MEDIA_SLOT_MS
    }

    fun clear(
        sourceIdentity: String,
        incarnationId: Long,
    ) {
        origins.remove(key(sourceIdentity, incarnationId))
    }

    fun clearIdentity(sourceIdentity: String) {
        val prefix = "$sourceIdentity#"
        origins.keys.filter { it.startsWith(prefix) }.forEach { origins.remove(it) }
    }

    fun clearAll() {
        origins.clear()
    }

    private fun key(
        sourceIdentity: String,
        incarnationId: Long,
    ): String = "$sourceIdentity#$incarnationId"
}

/**
 * Session playout domain (ADR-0058 Layer B / F4).
 * First accepted packet defines origin; subsequent packets advance by 20 ms/slot.
 * Do not use this mapping to interpret a foreign source's RTP sequence for jitter deadline.
 */
class RelativeMediaTimeline {
    private var baseSeq: Long? = null
    private var baseArrivalMs: Long? = null

    fun anchorMediaSlot(): Long? = baseSeq

    fun anchorWallMs(): Long? = baseArrivalMs

    fun isAnchored(): Boolean = baseSeq != null && baseArrivalMs != null

    /** First packet of the session anchors playout projection; later sources must not reset it. */
    fun ensureAnchor(
        sequence: Int,
        arrivalMs: Long,
    ) {
        if (baseSeq == null) {
            baseSeq = sequence.toLong()
            baseArrivalMs = arrivalMs
        }
    }

    fun mediaTimeMs(
        sequence: Int,
        arrivalMs: Long,
    ): Long {
        val seq = sequence.toLong()
        ensureAnchor(sequence, arrivalMs)
        return baseArrivalMs!! + (seq - baseSeq!!) * MediaJitterConstants.MEDIA_SLOT_MS
    }

    /**
     * Map absolute playout tick time onto the anchored RTP media-slot domain.
     * [tickMediaTimeMs] and [anchorWallMs] must share the same wall-clock reference.
     */
    fun mediaSlotForPlayoutTickMs(tickMediaTimeMs: Long): Long? {
        val baseSlot = baseSeq ?: return null
        val anchorMs = baseArrivalMs ?: return null
        val offset =
            ((tickMediaTimeMs - anchorMs).coerceAtLeast(0L)) /
                MediaJitterConstants.MEDIA_SLOT_MS
        return baseSlot + offset
    }
}
