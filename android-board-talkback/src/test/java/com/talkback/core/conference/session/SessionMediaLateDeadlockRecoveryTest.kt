package com.talkback.core.conference.session

import com.talkback.core.conference.runtime.AdmittedMediaFrame
import com.talkback.core.conference.runtime.AdmittedMediaSource
import com.talkback.core.conference.runtime.FrameAdmitDisposition
import com.talkback.core.conference.runtime.MediaExecutionPipeline
import com.talkback.core.conference.runtime.MediaJitterConstants
import com.talkback.core.conference.runtime.SlotPullDisposition
import com.talkback.core.conference.transport.PerIncarnationIngressTimelineRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** RCA5-B5 — LATE_FOR_PLAYOUT must not permanently lock out an empty per-source cursor. */
class SessionMediaLateDeadlockRecoveryTest {
    @Test
    fun lateDeadlock_reanchorsWhenCursorAheadOfArrivingRtp() {
        val pipeline = MediaExecutionPipeline()
        val timeline = PerIncarnationIngressTimelineRegistry()
        pipeline.install(AdmittedMediaSource(sourceIdentity = "M02", incarnationId = 1L))

        val wall = 1_700_000_000_000L
        val fantasyCursor = 23_819L
        // Seed jitter + Layer A at a high seq, then pull it away so cursor sits on empty buffer.
        assertEquals(
            FrameAdmitDisposition.QUEUED,
            pipeline.admitFrame(
                AdmittedMediaFrame(
                    sourceIdentity = "M02",
                    incarnationId = 1L,
                    mediaSlot = fantasyCursor,
                    mediaTimeMs = wall,
                    arrivalMs = wall,
                ),
                wall,
            ),
        )
        timeline.mediaTimeMs("M02", 1L, fantasyCursor.toInt(), wall)
        pipeline.pullSlot("M02", 1L, fantasyCursor, wall, wall + 20L)
        assertEquals(fantasyCursor + 1L, pipeline.nextExpectedSlot("M02", 1L))
        assertEquals(0, pipeline.jitterSize("M02", 1L))

        val liveSlot = 22_258L
        val arrival = wall + 60_000L
        val staleMediaTime =
            timeline.mediaTimeMs("M02", 1L, liveSlot.toInt(), arrival)
        assertTrue(arrival > staleMediaTime + MediaJitterConstants.MAX_PLAYOUT_DELAY_MS)

        val late =
            pipeline.admitFrame(
                AdmittedMediaFrame(
                    sourceIdentity = "M02",
                    incarnationId = 1L,
                    mediaSlot = liveSlot,
                    mediaTimeMs = staleMediaTime,
                    arrivalMs = arrival,
                ),
                arrival,
            )
        assertEquals(FrameAdmitDisposition.LATE_FOR_PLAYOUT, late)

        val recovered =
            SessionMediaLiveEdgeAligner.maybeRecoverOnLateForPlayout(
                pipeline = pipeline,
                ingressTimeline = timeline,
                sourceIdentity = "M02",
                incarnationId = 1L,
                liveSlot = liveSlot,
                arrivalMs = arrival,
                nowMs = arrival,
            )
        assertNotNull(recovered)
        assertEquals(FrameAdmitDisposition.QUEUED, recovered!!.disposition)
        assertEquals(liveSlot, pipeline.nextExpectedSlot("M02", 1L))
        assertEquals(1, pipeline.jitterSize("M02", 1L))

        // Subsequent live slot must QUEUED under re-anchored Layer A.
        val nextArrival = arrival + 20L
        val nextMedia =
            timeline.mediaTimeMs("M02", 1L, (liveSlot + 1).toInt(), nextArrival)
        assertEquals(
            FrameAdmitDisposition.QUEUED,
            pipeline.admitFrame(
                AdmittedMediaFrame(
                    sourceIdentity = "M02",
                    incarnationId = 1L,
                    mediaSlot = liveSlot + 1,
                    mediaTimeMs = nextMedia,
                    arrivalMs = nextArrival,
                ),
                nextArrival,
            ),
        )
    }

    @Test
    fun lateOnFreshBuffer_doesNotStealPlayableFrames() {
        val pipeline = MediaExecutionPipeline()
        val timeline = PerIncarnationIngressTimelineRegistry()
        pipeline.install(AdmittedMediaSource(sourceIdentity = "M03", incarnationId = 1L))
        val wall = System.currentTimeMillis()
        assertEquals(
            FrameAdmitDisposition.QUEUED,
            pipeline.admitFrame(
                AdmittedMediaFrame(
                    sourceIdentity = "M03",
                    incarnationId = 1L,
                    mediaSlot = 10_000L,
                    mediaTimeMs = wall,
                    arrivalMs = wall,
                ),
                wall,
            ),
        )
        // Still inside useful deadline — recovery must not discard playable buffer.
        val lateOld =
            SessionMediaLiveEdgeAligner.maybeRecoverOnLateForPlayout(
                pipeline = pipeline,
                ingressTimeline = timeline,
                sourceIdentity = "M03",
                incarnationId = 1L,
                liveSlot = 9_000L,
                arrivalMs = wall + 50L,
                nowMs = wall + 50L,
            )
        assertNull(lateOld)
        assertEquals(1, pipeline.jitterSize("M03", 1L))
    }

    @Test
    fun emptyPull_withSlotIndexMediaTime_doesNotAdvanceCursor() {
        val pipeline = MediaExecutionPipeline()
        pipeline.install(AdmittedMediaSource(sourceIdentity = "M02", incarnationId = 1L))
        val wall = System.currentTimeMillis()
        assertEquals(
            FrameAdmitDisposition.QUEUED,
            pipeline.admitFrame(
                AdmittedMediaFrame(
                    sourceIdentity = "M02",
                    incarnationId = 1L,
                    mediaSlot = 20_000L,
                    mediaTimeMs = wall,
                    arrivalMs = wall,
                ),
                wall,
            ),
        )
        pipeline.pullSlot("M02", 1L, 20_000L, wall, wall + 20L)
        val before = pipeline.nextExpectedSlot("M02", 1L)
        val pull =
            pipeline.pullSlot(
                sourceIdentity = "M02",
                incarnationId = 1L,
                slot = 20_001L,
                slotMediaTimeMs = 20_001L * MediaJitterConstants.MEDIA_SLOT_MS,
                nowMs = wall + 40L,
            )
        assertEquals(SlotPullDisposition.EMPTY, pull)
        assertEquals(before, pipeline.nextExpectedSlot("M02", 1L))
    }
}
