package com.talkback.core.conference.session

import com.talkback.core.conference.runtime.AdmittedMediaFrame
import com.talkback.core.conference.runtime.FrameAdmitDisposition
import com.talkback.core.conference.runtime.MediaExecutionPipeline
import com.talkback.core.conference.runtime.MediaJitterConstants
import com.talkback.core.conference.transport.PerIncarnationIngressTimelineRegistry

/**
 * RCA5-B2 — align jitter live edge when an empty RTP gap exceeds the reorder window.
 *
 * Does not alter Profile03 reorder cap or playout deadline; only re-anchors
 * [PerIncarnationJitterBuffer.nextExpectedSlot] to a late live packet after the
 * playout window has elapsed when either (a) the gap below the live slot is empty, or
 * (b) buffered frames are entirely stale and sit below an empty gap to the live slot
 * (field M02-style backlog without widening MAX_REORDER_PACKETS).
 *
 * RCA5-B5 — when [LATE_FOR_PLAYOUT] repeats against an empty (or fully stale) buffer,
 * re-anchor Layer A media time and the per-source RTP cursor to the arriving live slot
 * so the source can re-enter QUEUED without widening the global deadline.
 *
 * RCA5-B6 — when REORDER hits an aged hole after a contiguous buffered prefix, slide or
 * resync the cursor so live frames can QUEUED again (without raising MAX_REORDER_PACKETS).
 */
object SessionMediaLiveEdgeAligner {
    data class AlignResult(
        val discarded: Long,
        val disposition: FrameAdmitDisposition,
    )

    /**
     * B5 — break LATE deadlock: empty/stale jitter + Layer A timeline stuck behind wall
     * (or cursor ahead of arriving RTP) so every new frame fails usefulDeadline forever.
     */
    fun maybeRecoverOnLateForPlayout(
        pipeline: MediaExecutionPipeline,
        ingressTimeline: PerIncarnationIngressTimelineRegistry,
        sourceIdentity: String,
        incarnationId: Long,
        liveSlot: Long?,
        arrivalMs: Long,
        nowMs: Long,
    ): AlignResult? {
        if (liveSlot == null) return null
        val buffered = pipeline.jitterSize(sourceIdentity, incarnationId)
        val allStale =
            buffered > 0 &&
                pipeline.allBufferedPastUsefulDeadline(sourceIdentity, incarnationId, nowMs)
        if (buffered > 0 && !allStale) {
            return null
        }
        // Empty or fully-stale buffer: re-anchor Layer A + RTP cursor to arriving live slot
        // (field M01←M02 @16:22:26 — next stuck ahead, every ingress LATE forever).
        ingressTimeline.clear(sourceIdentity, incarnationId)
        val discarded =
            pipeline.soakResyncJitterToLiveEdge(
                sourceIdentity = sourceIdentity,
                incarnationId = incarnationId,
                liveSlot = liveSlot,
            )
        val freshMediaTimeMs = arrivalMs
        val retry =
            pipeline.admitFrame(
                AdmittedMediaFrame(
                    sourceIdentity = sourceIdentity,
                    incarnationId = incarnationId,
                    mediaSlot = liveSlot,
                    mediaTimeMs = freshMediaTimeMs,
                    arrivalMs = arrivalMs,
                ),
                nowMs,
            )
        if (retry != FrameAdmitDisposition.QUEUED) {
            return null
        }
        return AlignResult(
            discarded = discarded.toLong(),
            disposition = retry,
        )
    }

    fun maybeAlignLiveEdgeOnReorder(
        pipeline: MediaExecutionPipeline,
        sourceIdentity: String,
        incarnationId: Long,
        liveSlot: Long?,
        mediaTimeMs: Long,
        arrivalMs: Long,
        nowMs: Long,
    ): AlignResult? {
        if (liveSlot == null) return null
        val expected = pipeline.nextExpectedSlot(sourceIdentity, incarnationId) ?: return null
        if (liveSlot <= expected) return null
        val displacement = liveSlot - expected
        if (displacement <= MediaJitterConstants.MAX_REORDER_PACKETS) return null

        val gapAgeMs = displacement * MediaJitterConstants.MEDIA_SLOT_MS
        if (gapAgeMs <= MediaJitterConstants.MAX_PLAYOUT_DELAY_MS) return null

        val emptyGapFromExpected =
            gapEmptyFromExpected(pipeline, sourceIdentity, incarnationId, expected, liveSlot)
        val latest = pipeline.latestBufferedSlot(sourceIdentity, incarnationId)
        val gapEmptyAfterLatest =
            latest == null ||
                pipeline.gapEmptyBetweenLatestBufferedAnd(
                    sourceIdentity,
                    incarnationId,
                    liveSlot,
                )
        val staleBacklogGap =
            !emptyGapFromExpected &&
                gapEmptyAfterLatest &&
                pipeline.allBufferedPastUsefulDeadline(sourceIdentity, incarnationId, nowMs)
        // B6 — contiguous prefix at expected…latest with an aged hole to live: live packets
        // were permanently REORDER'd while prefix sat unconsumed (B5 field: bySlot=3–8, gap≈21).
        val agedHoleAfterPrefix =
            !emptyGapFromExpected &&
                latest != null &&
                latest < liveSlot &&
                gapEmptyAfterLatest &&
                gapAgeMs > MediaJitterConstants.MAX_PLAYOUT_DELAY_MS

        if (!emptyGapFromExpected && !staleBacklogGap && !agedHoleAfterPrefix) {
            return null
        }

        // Jump to live edge (discard below live). Sliding only to latest+1 would admit live but
        // strand the prefix below nextExpected — unpullable — so always resync to liveSlot.
        val discarded =
            pipeline
                .soakResyncJitterToLiveEdge(
                    sourceIdentity = sourceIdentity,
                    incarnationId = incarnationId,
                    liveSlot = liveSlot,
                ).toLong()
        val retry =
            pipeline.admitFrame(
                AdmittedMediaFrame(
                    sourceIdentity = sourceIdentity,
                    incarnationId = incarnationId,
                    mediaSlot = liveSlot,
                    mediaTimeMs = mediaTimeMs,
                    arrivalMs = arrivalMs,
                ),
                nowMs,
            )
        return AlignResult(
            discarded = discarded,
            disposition = retry,
        )
    }

    private fun gapEmptyFromExpected(
        pipeline: MediaExecutionPipeline,
        sourceIdentity: String,
        incarnationId: Long,
        expected: Long,
        liveSlot: Long,
    ): Boolean {
        var slot = expected
        while (slot < liveSlot) {
            if (pipeline.hasBufferedFrame(sourceIdentity, incarnationId, slot)) {
                return false
            }
            slot += 1
        }
        return true
    }
}
