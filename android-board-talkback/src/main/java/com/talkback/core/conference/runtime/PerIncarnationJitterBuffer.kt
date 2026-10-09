package com.talkback.core.conference.runtime

import com.talkback.core.conference.transport.SourceMixPlayoutAlignment

/**
 * Per-incarnation jitter / reorder state (Profile 03 Q3).
 *
 * C-E2B03-01: [MediaJitterConstants.MAX_REORDER_PACKETS] bounds outstanding
 * out-of-order displacement only — not total buffer storage capacity.
 *
 * C-E2B03-02: LATE_FOR_PLAYOUT is P03-only; never classified as SRTP_REPLAY_TOO_OLD.
 */
class PerIncarnationJitterBuffer(
    val sourceIdentity: String,
    val incarnationId: Long,
) {
    private val bySlot = sortedMapOf<Long, AdmittedMediaFrame>()
    private var nextExpectedSlot: Long? = null
    /** F9.2 — fixed source↔mix alignment; established once at first QUEUED. */
    private var mixPlayoutAlignment: SourceMixPlayoutAlignment? = null
    private var executable: Boolean = true

    fun size(): Int = bySlot.size

    fun isExecutable(): Boolean = executable

    /** C-E2B03-03: queued work becomes non-executable immediately. */
    fun invalidateForHardFence() {
        executable = false
        bySlot.clear()
        mixPlayoutAlignment = null
    }

    fun mixPlayoutAlignment(): SourceMixPlayoutAlignment? = mixPlayoutAlignment

    /** One-shot at first QUEUED; [mixReferenceSlot] and [sourceReferenceSlot] must be co-temporal. */
    fun establishMixPlayoutAlignment(
        mixReferenceSlot: Long,
        sourceReferenceSlot: Long,
    ) {
        if (mixPlayoutAlignment != null) return
        mixPlayoutAlignment =
            SourceMixPlayoutAlignment(
                mixReferenceSlot = mixReferenceSlot,
                sourceReferenceSlot = sourceReferenceSlot,
            )
    }

    fun sourceSlotForSharedMixPlayout(sharedMixPlayoutSlot: Long): Long =
        mixPlayoutAlignment?.sourceSlotForMix(sharedMixPlayoutSlot) ?: sharedMixPlayoutSlot

    fun admit(frame: AdmittedMediaFrame, nowMs: Long): FrameAdmitDisposition {
        require(frame.sourceIdentity == sourceIdentity)
        require(frame.incarnationId == incarnationId)
        if (!executable) return FrameAdmitDisposition.FENCED_NON_EXECUTABLE

        if (nowMs > frame.usefulDeadlineMs()) {
            return FrameAdmitDisposition.LATE_FOR_PLAYOUT
        }

        val expected = nextExpectedSlot
        if (expected != null && frame.mediaSlot > expected) {
            // Outstanding out-of-order displacement only (C-E2B03-01).
            // Contiguous in-order appends (no holes below this slot) are not capped at 4 total.
            val hasHoleBelow =
                (expected until frame.mediaSlot).any { slot -> !bySlot.containsKey(slot) }
            if (hasHoleBelow) {
                val displacement = frame.mediaSlot - expected
                if (displacement > MediaJitterConstants.MAX_REORDER_PACKETS) {
                    return FrameAdmitDisposition.REORDER_DISPLACEMENT_EXCEEDED
                }
            }
        }

        // Contiguous / in-window frames may accumulate beyond 4 total (C-E2B03-01).
        bySlot[frame.mediaSlot] = frame
        when {
            expected == null -> nextExpectedSlot = frame.mediaSlot
            frame.mediaSlot < expected -> nextExpectedSlot = frame.mediaSlot
        }
        return FrameAdmitDisposition.QUEUED
    }

    /**
     * Peek whether [slot] is past its useful deadline without a packet.
     */
    fun isSlotLostAtDeadline(slot: Long, slotMediaTimeMs: Long, nowMs: Long): Boolean {
        if (bySlot.containsKey(slot)) return false
        return nowMs > slotMediaTimeMs + MediaJitterConstants.MAX_PLAYOUT_DELAY_MS
    }

    fun takeFrame(slot: Long): AdmittedMediaFrame? {
        if (!executable) return null
        return bySlot.remove(slot)
    }

    fun peekFrame(slot: Long): AdmittedMediaFrame? = bySlot[slot]

    fun nextExpected(): Long? = nextExpectedSlot

    fun advanceExpectedTo(slot: Long) {
        nextExpectedSlot = slot
    }

    fun bufferedSlots(): List<Long> = ArrayList(bySlot.keys)

    fun earliestBufferedSlot(): Long? = bySlot.keys.firstOrNull()

    fun latestBufferedSlot(): Long? = bySlot.keys.lastOrNull()

    /**
     * Promotion transition: discard buffered frames already past playout deadline.
     * Retains in-window frames and valid reorder state; does not clear the whole queue.
     */
    fun promotionDiscardLateForPlayout(currentPlayoutTimeMs: Long): PromotionFenceBufferResult {
        if (!executable || bySlot.isEmpty()) {
            return PromotionFenceBufferResult(staleDiscarded = 0, oldestRetainedAgeMs = null)
        }
        val staleSlots =
            bySlot.entries
                .filter { currentPlayoutTimeMs > it.value.usefulDeadlineMs() }
                .map { it.key }
        if (staleSlots.isEmpty()) {
            val oldest = bySlot.values.minByOrNull { it.mediaTimeMs }
            return PromotionFenceBufferResult(
                staleDiscarded = 0,
                oldestRetainedAgeMs = oldest?.let { currentPlayoutTimeMs - it.mediaTimeMs },
            )
        }
        var maxRemovedSlot: Long? = null
        for (slot in staleSlots) {
            bySlot.remove(slot)
            if (maxRemovedSlot == null || slot > maxRemovedSlot) {
                maxRemovedSlot = slot
            }
        }
        val minRemaining = bySlot.keys.minOrNull()
        nextExpectedSlot =
            when {
                minRemaining != null -> minRemaining
                maxRemovedSlot != null ->
                    maxOf(nextExpectedSlot ?: 0L, maxRemovedSlot + 1)
                else -> nextExpectedSlot
            }
        val oldestRetained = bySlot.values.minByOrNull { it.mediaTimeMs }
        return PromotionFenceBufferResult(
            staleDiscarded = staleSlots.size,
            oldestRetainedAgeMs = oldestRetained?.let { currentPlayoutTimeMs - it.mediaTimeMs },
        )
    }

    /**
     * Soak/harness only — not Profile 03 admit semantics.
     * Drop buffered slots strictly below [liveSlot] and set expected to the live edge.
     * @return number of buffered frames discarded
     */
    fun soakDiscardBelowAndResync(liveSlot: Long): Int {
        val stale = bySlot.keys.filter { it < liveSlot }
        for (slot in stale) {
            bySlot.remove(slot)
        }
        nextExpectedSlot = liveSlot
        return stale.size
    }
}
