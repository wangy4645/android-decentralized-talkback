package com.talkback.core.conference.session

import com.talkback.core.conference.runtime.MediaExecutionPipeline
import com.talkback.core.conference.runtime.MediaJitterConstants
import com.talkback.core.conference.runtime.SlotPullDisposition
import com.talkback.core.conference.session.JitterSlotDomainSnapshot

internal object SourcePullEmptyDiagnostics {
    data class PullObservation(
        val sourceIdentity: String,
        val incarnationId: Long,
        val pullSlot: Long?,
        val slotMediaTimeMs: Long,
        val pullResult: SlotPullDisposition?,
        val emptyReason: SourcePullEmptyReason,
        val queueDepth: Int,
        val nextExpectedSlot: Long?,
        val earliestBufferedSlot: Long?,
        val latestBufferedSlot: Long?,
        val deferred: Boolean,
        val decodeEligible: Boolean,
    )

    fun classifySkippedTopKMember(
        sourceIdentity: String,
        incarnationId: Long,
        sessionId: String,
        perSourceSlotsNonEmpty: Boolean,
        pullSlot: Long?,
        slotMediaTimeMs: Long,
        pipeline: MediaExecutionPipeline,
    ): PullObservation {
        val snap = pipeline.jitterSlotDomainSnapshot(sourceIdentity, incarnationId)
        val deferred =
            PlayoutTopKDeferral.isSourceDeferred(sessionId, sourceIdentity, incarnationId)
        val reason =
            when {
                deferred -> SourcePullEmptyReason.DEFERRED
                perSourceSlotsNonEmpty -> SourcePullEmptyReason.NOT_SELECTED_FOR_MIX_PULL
                pullSlot == null -> SourcePullEmptyReason.NO_PULL_SLOT
                else -> SourcePullEmptyReason.NOT_SELECTED_FOR_MIX_PULL
            }
        return observation(
            sourceIdentity,
            incarnationId,
            pullSlot,
            slotMediaTimeMs,
            null,
            reason,
            snap,
            deferred,
            decodeEligible = false,
        )
    }

    fun classifyAfterPull(
        sourceIdentity: String,
        incarnationId: Long,
        sessionId: String,
        pullSlot: Long,
        slotMediaTimeMs: Long,
        nowMs: Long,
        disposition: SlotPullDisposition,
        peekedBeforePull: com.talkback.core.conference.runtime.AdmittedMediaFrame?,
        pipeline: MediaExecutionPipeline,
        decodeEligible: Boolean,
    ): PullObservation {
        if (disposition == SlotPullDisposition.DECODE_FRAME) {
            val snap = pipeline.jitterSlotDomainSnapshot(sourceIdentity, incarnationId)
            return observation(
                sourceIdentity,
                incarnationId,
                pullSlot,
                slotMediaTimeMs,
                disposition,
                SourcePullEmptyReason.REAL,
                snap,
                PlayoutTopKDeferral.isSourceDeferred(sessionId, sourceIdentity, incarnationId),
                decodeEligible,
            )
        }
        val snap = pipeline.jitterSlotDomainSnapshot(sourceIdentity, incarnationId)
        val deferred =
            PlayoutTopKDeferral.isSourceDeferred(sessionId, sourceIdentity, incarnationId)
        val reason =
            when (disposition) {
                SlotPullDisposition.FENCED_SKIP ->
                    SourcePullEmptyReason.MEMBERSHIP_OR_BINDING_MISMATCH
                SlotPullDisposition.PLC_SYNTHESIS,
                SlotPullDisposition.SILENCE_GAP,
                -> SourcePullEmptyReason.PULL_PLC
                SlotPullDisposition.EMPTY ->
                    classifyEmptyPull(
                        peekedBeforePull = peekedBeforePull,
                        snap = snap,
                        pullSlot = pullSlot,
                        slotMediaTimeMs = slotMediaTimeMs,
                        nowMs = nowMs,
                        decodeEligible = decodeEligible,
                        deferred = deferred,
                    )
                SlotPullDisposition.DECODE_FRAME -> SourcePullEmptyReason.REAL
            }
        return observation(
            sourceIdentity,
            incarnationId,
            pullSlot,
            slotMediaTimeMs,
            disposition,
            reason,
            snap,
            deferred,
            decodeEligible,
        )
    }

    private fun classifyEmptyPull(
        peekedBeforePull: com.talkback.core.conference.runtime.AdmittedMediaFrame?,
        snap: JitterSlotDomainSnapshot?,
        pullSlot: Long,
        slotMediaTimeMs: Long,
        nowMs: Long,
        decodeEligible: Boolean,
        deferred: Boolean,
    ): SourcePullEmptyReason {
        if (deferred) return SourcePullEmptyReason.DEFERRED
        if (peekedBeforePull != null) {
            if (nowMs > peekedBeforePull.usefulDeadlineMs()) {
                return SourcePullEmptyReason.SLOT_MISMATCH_OR_LATE
            }
            if (!decodeEligible) return SourcePullEmptyReason.NOT_DECODE_ELIGIBLE
        }
        if (snap == null || snap.bySlotSize == 0) {
            return SourcePullEmptyReason.QUEUE_EMPTY
        }
        if (nowMs <= slotMediaTimeMs + MediaJitterConstants.MAX_PLAYOUT_DELAY_MS) {
            return SourcePullEmptyReason.SLOT_WAITING
        }
        val earliest = snap.earliestBufferedSlot
        val next = snap.nextExpectedSlot
        if (earliest != null && next != null && earliest > pullSlot) {
            return SourcePullEmptyReason.SLOT_MISMATCH_OR_LATE
        }
        return SourcePullEmptyReason.SLOT_MISMATCH_OR_LATE
    }

    private fun observation(
        sourceIdentity: String,
        incarnationId: Long,
        pullSlot: Long?,
        slotMediaTimeMs: Long,
        disposition: SlotPullDisposition?,
        reason: SourcePullEmptyReason,
        snap: JitterSlotDomainSnapshot?,
        deferred: Boolean,
        decodeEligible: Boolean,
    ): PullObservation =
        PullObservation(
            sourceIdentity = sourceIdentity,
            incarnationId = incarnationId,
            pullSlot = pullSlot,
            slotMediaTimeMs = slotMediaTimeMs,
            pullResult = disposition,
            emptyReason = reason,
            queueDepth = snap?.bySlotSize ?: 0,
            nextExpectedSlot = snap?.nextExpectedSlot,
            earliestBufferedSlot = snap?.earliestBufferedSlot,
            latestBufferedSlot = snap?.latestBufferedSlot,
            deferred = deferred,
            decodeEligible = decodeEligible,
        )
}
