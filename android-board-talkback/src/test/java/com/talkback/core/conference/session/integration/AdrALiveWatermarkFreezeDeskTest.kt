package com.talkback.core.conference.session.integration

import com.talkback.core.conference.runtime.AdmittedMediaFrame
import com.talkback.core.conference.runtime.AdmittedMediaSource
import com.talkback.core.conference.runtime.FrameAdmitDisposition
import com.talkback.core.conference.runtime.MediaExecutionPipeline
import com.talkback.core.conference.runtime.MediaJitterConstants
import com.talkback.core.conference.session.AdrALiveWatermarkFreeze
import com.talkback.core.conference.session.SessionMediaLiveEdgeAligner
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ADR-A production-path desk regression (aligner wired).
 *
 * Gates:
 * 1) burst: futures +10..+16 must not QIW.
 * 2) sparse fill: after hole filled, freeze clears.
 * 3) no-hole mild reorder: freeze not engaged.
 * 4) raw stale (>IN_WINDOW): still RESYNC_STALE.
 * 5) field#2 seam: next buffered + hole in [next, live) → still freeze (hasHoleBelow).
 */
class AdrALiveWatermarkFreezeDeskTest {
    @Before
    fun enableAdrA() {
        AdrALiveWatermarkFreeze.resetForTest()
        AdrALiveWatermarkFreeze.enabled = true
        SessionMediaLiveEdgeAligner.a1QiwTimeoutResyncEnabled = false
        SessionMediaLiveEdgeAligner.cQiwLagDebtConvergenceEnabled = false
        SessionMediaLiveEdgeAligner.q3QiwDeferredPinEnabled = false
    }

    @After
    fun restore() {
        AdrALiveWatermarkFreeze.resetForTest()
        AdrALiveWatermarkFreeze.enabled = false
        SessionMediaLiveEdgeAligner.a1QiwTimeoutResyncEnabled = false
        SessionMediaLiveEdgeAligner.cQiwLagDebtConvergenceEnabled = false
        SessionMediaLiveEdgeAligner.q3QiwDeferredPinEnabled = false
    }

    @Test
    fun burstLoss_futuresDoNotQiw_dispNotAmplified() {
        val pipeline = MediaExecutionPipeline()
        pipeline.install(AdmittedMediaSource(sourceIdentity = "M01", incarnationId = 1L))
        val t0 = 1_000_000L
        val base = 20_480L
        assertEquals(FrameAdmitDisposition.QUEUED, pipeline.admitFrame(frame(base, t0), t0))
        pipeline.pullSlot("M01", 1L, base, t0, t0 + 20L)
        for (i in 2..5) {
            assertEquals(
                FrameAdmitDisposition.QUEUED,
                pipeline.admitFrame(frame(base + i, t0 + 20L * i), t0 + 20L * i),
            )
        }
        for (d in 10L..16L) {
            val live = base + d
            val t = t0 + 20L * d
            assertEquals(
                FrameAdmitDisposition.REORDER_DISPLACEMENT_EXCEEDED,
                pipeline.admitFrame(frame(live, t), t),
            )
            assertNull(
                "ADR-A must suppress QIW for future+$d",
                SessionMediaLiveEdgeAligner.maybeAlignLiveEdgeOnReorder(
                    pipeline, "M01", 1L, live, t, t, t,
                ),
            )
            assertFalse(pipeline.hasBufferedFrame("M01", 1L, live))
            val st = AdrALiveWatermarkFreeze.stateForTest("M01", 1L)!!
            assertTrue(st.holeOpen)
            assertTrue(st.displacement() <= MediaJitterConstants.MAX_REORDER_PACKETS)
            assertTrue(st.liveWatermark >= live)
        }
        assertEquals(base + 1, pipeline.nextExpectedSlot("M01", 1L))
    }

    /**
     * Field#2 seam: nextExpected still on a buffered slot (not pulled),
     * but a later hole exists in [next, live). hasHoleBelow must freeze.
     */
    @Test
    fun nextAlreadyBuffered_holeAbove_stillFreezes_noQiw() {
        val pipeline = MediaExecutionPipeline()
        pipeline.install(AdmittedMediaSource(sourceIdentity = "M01", incarnationId = 1L))
        val t0 = 5_000_000L
        val next = 60_000L
        assertEquals(FrameAdmitDisposition.QUEUED, pipeline.admitFrame(frame(next, t0), t0))
        assertTrue(pipeline.hasBufferedFrame("M01", 1L, next))
        assertEquals(next, pipeline.nextExpectedSlot("M01", 1L))
        for (i in 2..4) {
            assertEquals(
                FrameAdmitDisposition.QUEUED,
                pipeline.admitFrame(frame(next + i, t0 + 20L * i), t0 + 20L * i),
            )
        }
        val live = next + 12
        val tLive = t0 + 20L * 12
        assertEquals(
            FrameAdmitDisposition.REORDER_DISPLACEMENT_EXCEEDED,
            pipeline.admitFrame(frame(live, tLive), tLive),
        )
        assertNull(
            "hasHoleBelow must freeze even when next is buffered",
            SessionMediaLiveEdgeAligner.maybeAlignLiveEdgeOnReorder(
                pipeline, "M01", 1L, live, tLive, tLive, tLive,
            ),
        )
        assertFalse(pipeline.hasBufferedFrame("M01", 1L, live))
        val st = AdrALiveWatermarkFreeze.stateForTest("M01", 1L)!!
        assertTrue(st.holeOpen)
        assertTrue(st.displacement() <= MediaJitterConstants.MAX_REORDER_PACKETS)
        assertEquals(live, st.liveWatermark)
        assertTrue(st.effectiveLiveWatermark < live)
    }

    @Test
    fun sparseLoss_fillClearsFreeze() {
        val pipeline = MediaExecutionPipeline()
        pipeline.install(AdmittedMediaSource(sourceIdentity = "M01", incarnationId = 1L))
        val t0 = 2_000_000L
        val base = 30_000L
        assertEquals(FrameAdmitDisposition.QUEUED, pipeline.admitFrame(frame(base, t0), t0))
        pipeline.pullSlot("M01", 1L, base, t0, t0 + 20L)
        val hole = base + 1
        val far = hole + 8
        val tFar = t0 + 20L * 8
        assertEquals(
            FrameAdmitDisposition.REORDER_DISPLACEMENT_EXCEEDED,
            pipeline.admitFrame(frame(far, tFar), tFar),
        )
        assertNull(
            SessionMediaLiveEdgeAligner.maybeAlignLiveEdgeOnReorder(
                pipeline, "M01", 1L, far, tFar, tFar, tFar,
            ),
        )
        assertTrue(AdrALiveWatermarkFreeze.stateForTest("M01", 1L)!!.holeOpen)

        assertEquals(
            FrameAdmitDisposition.QUEUED,
            pipeline.admitFrame(frame(hole, t0 + 20L), t0 + 20L),
        )
        val cleared =
            AdrALiveWatermarkFreeze.displacementForAlign(
                sourceIdentity = "M01",
                incarnationId = 1L,
                nextExpected = hole,
                liveSlot = far,
                holeAtExpected = false,
            )
        assertEquals(far - hole, cleared)
        assertNull(AdrALiveWatermarkFreeze.stateForTest("M01", 1L))
    }

    @Test
    fun noHole_mildReorder_queuesWithoutFreeze() {
        val pipeline = MediaExecutionPipeline()
        pipeline.install(AdmittedMediaSource(sourceIdentity = "M01", incarnationId = 1L))
        val t0 = 3_000_000L
        val base = 40_000L
        assertEquals(FrameAdmitDisposition.QUEUED, pipeline.admitFrame(frame(base, t0), t0))
        for (i in 1..3) {
            assertEquals(
                FrameAdmitDisposition.QUEUED,
                pipeline.admitFrame(frame(base + i, t0 + 20L * i), t0 + 20L * i),
            )
        }
        assertNull(AdrALiveWatermarkFreeze.stateForTest("M01", 1L))
        assertEquals(base, pipeline.nextExpectedSlot("M01", 1L))
        assertTrue(pipeline.jitterSize("M01", 1L) >= 4)
    }

    @Test
    fun noHole_contiguousBeyondMaxReorder_doesNotEngageFreeze() {
        val pipeline = MediaExecutionPipeline()
        pipeline.install(AdmittedMediaSource(sourceIdentity = "M01", incarnationId = 1L))
        val t0 = 6_000_000L
        val base = 70_000L
        assertEquals(FrameAdmitDisposition.QUEUED, pipeline.admitFrame(frame(base, t0), t0))
        for (i in 1..12) {
            assertEquals(
                FrameAdmitDisposition.QUEUED,
                pipeline.admitFrame(frame(base + i, t0 + 20L * i), t0 + 20L * i),
            )
        }
        assertNull(AdrALiveWatermarkFreeze.stateForTest("M01", 1L))
        SessionMediaLiveEdgeAligner.maybeAlignLiveEdgeOnReorder(
            pipeline, "M01", 1L, base + 12, t0 + 20L * 12, t0 + 20L * 12, t0 + 20L * 12,
        )
        assertNull(AdrALiveWatermarkFreeze.stateForTest("M01", 1L))
    }

    @Test
    fun rawStaleBeyondInWindow_stillResyncs() {
        val pipeline = MediaExecutionPipeline()
        pipeline.install(AdmittedMediaSource(sourceIdentity = "M01", incarnationId = 1L))
        val t0 = 4_000_000L
        val base = 50_000L
        assertEquals(FrameAdmitDisposition.QUEUED, pipeline.admitFrame(frame(base, t0), t0))
        pipeline.pullSlot("M01", 1L, base, t0, t0 + 20L)
        for (i in 2..4) {
            assertEquals(
                FrameAdmitDisposition.QUEUED,
                pipeline.admitFrame(frame(base + i, t0 + 20L * i), t0 + 20L * i),
            )
        }
        val live = base + 100
        val tLive = t0 + 20L * 100
        assertEquals(
            FrameAdmitDisposition.REORDER_DISPLACEMENT_EXCEEDED,
            pipeline.admitFrame(frame(live, tLive), tLive),
        )
        val aligned =
            SessionMediaLiveEdgeAligner.maybeAlignLiveEdgeOnReorder(
                pipeline, "M01", 1L, live, tLive, tLive, tLive,
            )
        assertEquals(SessionMediaLiveEdgeAligner.Kind.RESYNC_STALE, aligned!!.kind)
        assertEquals(live, pipeline.nextExpectedSlot("M01", 1L))
        assertNull(AdrALiveWatermarkFreeze.stateForTest("M01", 1L))
    }

    private fun frame(
        slot: Long,
        t: Long,
    ) = AdmittedMediaFrame(
        sourceIdentity = "M01",
        incarnationId = 1L,
        mediaSlot = slot,
        mediaTimeMs = t,
        arrivalMs = t,
    )
}
