package com.talkback.core.conference.session

import com.talkback.core.conference.runtime.AdmittedMediaFrame
import com.talkback.core.conference.runtime.AdmittedMediaSource
import com.talkback.core.conference.runtime.FrameAdmitDisposition
import com.talkback.core.conference.runtime.MediaExecutionPipeline
import com.talkback.core.conference.runtime.MediaJitterConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class SessionMediaLiveEdgeAlignerTest {
    @Test
    fun alignLiveEdge_resyncsEmptyGapBeyondReorderWindow() {
        val pipeline = MediaExecutionPipeline()
        val source = AdmittedMediaSource(sourceIdentity = "M01", incarnationId = 1L)
        pipeline.install(source)
        val anchorMs = System.currentTimeMillis()
        val baseSlot = 20_480L
        val first =
            pipeline.admitFrame(
                AdmittedMediaFrame(
                    sourceIdentity = "M01",
                    incarnationId = 1L,
                    mediaSlot = baseSlot,
                    mediaTimeMs = anchorMs,
                    arrivalMs = anchorMs,
                ),
                anchorMs,
            )
        assertEquals(FrameAdmitDisposition.QUEUED, first)
        pipeline.pullSlot("M01", 1L, baseSlot, anchorMs, anchorMs + 20L)

        val liveSlot = baseSlot + 10
        val liveWallMs = anchorMs + 10 * MediaJitterConstants.MEDIA_SLOT_MS
        val rejected =
            pipeline.admitFrame(
                AdmittedMediaFrame(
                    sourceIdentity = "M01",
                    incarnationId = 1L,
                    mediaSlot = liveSlot,
                    mediaTimeMs = liveWallMs,
                    arrivalMs = liveWallMs,
                ),
                liveWallMs,
            )
        assertEquals(FrameAdmitDisposition.REORDER_DISPLACEMENT_EXCEEDED, rejected)

        val aligned =
            SessionMediaLiveEdgeAligner.maybeAlignLiveEdgeOnReorder(
                pipeline = pipeline,
                sourceIdentity = "M01",
                incarnationId = 1L,
                liveSlot = liveSlot,
                mediaTimeMs = liveWallMs,
                arrivalMs = liveWallMs,
                nowMs = liveWallMs,
            )
        assertNotNull(aligned)
        assertEquals(FrameAdmitDisposition.QUEUED, aligned!!.disposition)
        assertEquals(liveSlot, pipeline.nextExpectedSlot("M01", 1L))
    }

    @Test
    fun alignLiveEdge_resyncsStaleBacklogBlockingReorder() {
        val pipeline = MediaExecutionPipeline()
        val source = AdmittedMediaSource(sourceIdentity = "M02", incarnationId = 1L)
        pipeline.install(source)
        val anchorMs = 50_000L
        val baseSlot = 25_058L
        for (offset in 0 until 40) {
            val slot = baseSlot + offset
            assertEquals(
                FrameAdmitDisposition.QUEUED,
                pipeline.admitFrame(
                    AdmittedMediaFrame(
                        sourceIdentity = "M02",
                        incarnationId = 1L,
                        mediaSlot = slot,
                        mediaTimeMs = anchorMs,
                        arrivalMs = anchorMs,
                    ),
                    anchorMs,
                ),
            )
        }
        assertEquals(40, pipeline.jitterSize("M02", 1L))

        val liveSlot = baseSlot + 80
        val liveWallMs = anchorMs + 80 * MediaJitterConstants.MEDIA_SLOT_MS
        val rejected =
            pipeline.admitFrame(
                AdmittedMediaFrame(
                    sourceIdentity = "M02",
                    incarnationId = 1L,
                    mediaSlot = liveSlot,
                    mediaTimeMs = liveWallMs,
                    arrivalMs = liveWallMs,
                ),
                liveWallMs,
            )
        assertEquals(FrameAdmitDisposition.REORDER_DISPLACEMENT_EXCEEDED, rejected)

        val aligned =
            SessionMediaLiveEdgeAligner.maybeAlignLiveEdgeOnReorder(
                pipeline = pipeline,
                sourceIdentity = "M02",
                incarnationId = 1L,
                liveSlot = liveSlot,
                mediaTimeMs = liveWallMs,
                arrivalMs = liveWallMs,
                nowMs = liveWallMs,
            )
        assertNotNull(aligned)
        assertEquals(FrameAdmitDisposition.QUEUED, aligned!!.disposition)
        assertEquals(40, aligned.discarded)
        assertEquals(liveSlot, pipeline.nextExpectedSlot("M02", 1L))
        assertEquals(1, pipeline.jitterSize("M02", 1L))
    }

    @Test
    fun alignLiveEdge_resyncsAgedHoleAfterPrefixWithoutRequiringStaleDeadline() {
        // B5 field shape: bySlot≈8 at expected…latest, live gap≈10, prefix not yet past deadline.
        val pipeline = MediaExecutionPipeline()
        pipeline.install(AdmittedMediaSource(sourceIdentity = "M04", incarnationId = 1L))
        val anchorMs = System.currentTimeMillis()
        val base = 20_501L
        for (offset in 0 until 8) {
            val t = anchorMs + offset * MediaJitterConstants.MEDIA_SLOT_MS
            assertEquals(
                FrameAdmitDisposition.QUEUED,
                pipeline.admitFrame(
                    AdmittedMediaFrame(
                        sourceIdentity = "M04",
                        incarnationId = 1L,
                        mediaSlot = base + offset,
                        mediaTimeMs = t,
                        arrivalMs = t,
                    ),
                    t,
                ),
            )
        }
        val liveSlot = base + 10L
        val liveWall = anchorMs + 10 * MediaJitterConstants.MEDIA_SLOT_MS
        assertEquals(
            FrameAdmitDisposition.REORDER_DISPLACEMENT_EXCEEDED,
            pipeline.admitFrame(
                AdmittedMediaFrame(
                    sourceIdentity = "M04",
                    incarnationId = 1L,
                    mediaSlot = liveSlot,
                    mediaTimeMs = liveWall,
                    arrivalMs = liveWall,
                ),
                liveWall,
            ),
        )
        // Prefix still inside useful deadline — old staleBacklog path would skip; B6 must recover.
        assertEquals(
            false,
            pipeline.allBufferedPastUsefulDeadline("M04", 1L, liveWall),
        )
        val aligned =
            SessionMediaLiveEdgeAligner.maybeAlignLiveEdgeOnReorder(
                pipeline = pipeline,
                sourceIdentity = "M04",
                incarnationId = 1L,
                liveSlot = liveSlot,
                mediaTimeMs = liveWall,
                arrivalMs = liveWall,
                nowMs = liveWall,
            )
        assertNotNull(aligned)
        assertEquals(FrameAdmitDisposition.QUEUED, aligned!!.disposition)
        assertEquals(8, aligned.discarded)
        assertEquals(liveSlot, pipeline.nextExpectedSlot("M04", 1L))
        assertEquals(1, pipeline.jitterSize("M04", 1L))
    }
}
