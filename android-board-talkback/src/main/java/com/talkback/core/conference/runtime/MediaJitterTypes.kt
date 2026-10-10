package com.talkback.core.conference.runtime

/**
 * Profile 03 Q3/Q4 frozen constants (E2b-03).
 */
object MediaJitterConstants {
    const val MAX_PLAYOUT_DELAY_MS: Long = 120L
    /** B5 — if nowMs − slotMediaTimeMs exceeds this, treat media time as wrong clock domain. */
    const val WALL_MEDIA_TIME_SKEW_LIMIT_MS: Long = 3_600_000L
    const val MAX_REORDER_PACKETS: Int = 4
    const val MAX_CONSECUTIVE_PLC_FRAMES: Int = 5
    /** Nominal media slot duration for fixture clocks (20 ms @ 50 pps). */
    const val MEDIA_SLOT_MS: Long = 20L
}

enum class FrameAdmitDisposition {
    QUEUED,
    LATE_FOR_PLAYOUT,
    /** Out-of-order displacement beyond MaxReorderPackets (≠ LATE; ≠ P02 replay). */
    REORDER_DISPLACEMENT_EXCEEDED,
    NOT_ADMITTED_INCARNATION,
    FENCED_NON_EXECUTABLE,
    /** MaxJitterSources cap — allocator returned REJECT_NEW (≠ admission bypass). */
    JITTER_CAP_REJECTED,
}

enum class SlotPullDisposition {
    DECODE_FRAME,
    PLC_SYNTHESIS,
    SILENCE_GAP,
    /** Queued work present but incarnation fenced — must not decode/PLC. */
    FENCED_SKIP,
    EMPTY,
}

/**
 * Post-P02 admitted media frame input to Profile 03 jitter (no Opus bytes required).
 */
data class AdmittedMediaFrame(
    val sourceIdentity: String,
    val incarnationId: Long,
    val mediaSlot: Long,
    /** Authoritative media timeline instant for this slot (fixture/media-time). */
    val mediaTimeMs: Long,
    val arrivalMs: Long,
) {
    fun usefulDeadlineMs(): Long = mediaTimeMs + MediaJitterConstants.MAX_PLAYOUT_DELAY_MS
}
