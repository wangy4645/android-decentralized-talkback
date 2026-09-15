package com.talkback.core.conference.session

import com.talkback.core.conference.runtime.AdmittedMediaFrame
import com.talkback.core.conference.runtime.FrameAdmitDisposition
import com.talkback.core.conference.runtime.MediaExecutionPipeline
import com.talkback.core.conference.runtime.MediaJitterConstants

/**
 * RCA5-B2 — align jitter live edge when an empty RTP gap exceeds the reorder window.
 *
 * Does not alter Profile03 reorder cap or playout deadline; only re-anchors
 * [PerIncarnationJitterBuffer.nextExpectedSlot] to a late live packet after the
 * playout window has elapsed with no buffered fill.
 */
object SessionMediaLiveEdgeAligner {
    data class AlignResult(
        val discarded: Long,
        val disposition: FrameAdmitDisposition,
    )

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

        var gapEmpty = true
        var slot = expected
        while (slot < liveSlot) {
            if (pipeline.hasBufferedFrame(sourceIdentity, incarnationId, slot)) {
                gapEmpty = false
                break
            }
            slot += 1
        }
        if (!gapEmpty) return null

        val gapAgeMs = displacement * MediaJitterConstants.MEDIA_SLOT_MS
        if (gapAgeMs <= MediaJitterConstants.MAX_PLAYOUT_DELAY_MS) return null

        val discarded =
            pipeline.soakResyncJitterToLiveEdge(
                sourceIdentity = sourceIdentity,
                incarnationId = incarnationId,
                liveSlot = liveSlot,
            )
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
            discarded = discarded.toLong(),
            disposition = retry,
        )
    }
}
