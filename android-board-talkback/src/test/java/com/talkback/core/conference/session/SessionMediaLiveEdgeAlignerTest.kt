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
}
