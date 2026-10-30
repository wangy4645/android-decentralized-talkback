package com.talkback.core.conference.playout

import com.talkback.core.conference.runtime.AdmittedMediaFrame
import com.talkback.core.conference.runtime.AdmittedMediaSource
import com.talkback.core.conference.runtime.ConferenceMediaExecutionOrchestrator
import com.talkback.core.conference.runtime.FrameAdmitDisposition
import com.talkback.core.conference.runtime.MediaExecutionPipeline
import com.talkback.core.conference.runtime.MediaJitterConstants
import com.talkback.core.conference.runtime.SlotPullDisposition
import com.talkback.core.conference.runtime.VoiceLevelObservation
import com.talkback.core.conference.session.AdrALiveWatermarkFreeze
import com.talkback.core.conference.session.HoleSideStaging
import com.talkback.core.conference.session.SessionMediaLiveEdgeAligner
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ADR-C Hole Consumption Contract v0 — minimal 8-case desk matrix (P1 frozen knobs).
 *
 * Documents [pullSlot] time authority vs [PerSourceRenderScheduler] synthetic mediaTime seam (C6).
 */
class AdrCHoleConsumptionDeskMatrixTest {
    @Before
    fun freezeP1Knobs() {
        PerSourceRenderScheduler.strongCatchUpEnabled = false
        PerSourceRenderScheduler.boundedCatchUpStep4Enabled = false
        HoleSideStaging.resetForTest()
        AdrALiveWatermarkFreeze.resetForTest()
        AdrALiveWatermarkFreeze.enabled = false
        SessionMediaLiveEdgeAligner.a1QiwTimeoutResyncEnabled = false
        SessionMediaLiveEdgeAligner.cQiwLagDebtConvergenceEnabled = false
        SessionMediaLiveEdgeAligner.q3QiwDeferredPinEnabled = false
    }

    @After
    fun tearDown() {
        PerSourceRenderScheduler.strongCatchUpEnabled = false
        PerSourceRenderScheduler.boundedCatchUpStep4Enabled = false
        HoleSideStaging.resetForTest()
        AdrALiveWatermarkFreeze.resetForTest()
        SessionMediaLiveEdgeAligner.a1QiwTimeoutResyncEnabled = false
    }

    /** C0-1: REAL — at most one cursor step per consumer invocation. */
    @Test
    fun c0_1_real_oneConsumptionAdvancesExactlyOnce() {
        val pipe = pipeline("M01")
        val t0 = 1_000_000L
        val base = 10_000L
        admit(pipe, "M01", base, t0)
        val next0 = pipe.nextExpectedSlot("M01", 1L)!!
        val d =
            pipe.pullSlot("M01", 1L, base, t0, t0 + 5L)
        assertEquals(SlotPullDisposition.DECODE_FRAME, d)
        assertEquals(next0 + 1, pipe.nextExpectedSlot("M01", 1L))
        val d2 =
            pipe.pullSlot("M01", 1L, base, t0, t0 + 6L)
        assertEquals(SlotPullDisposition.EMPTY, d2)
        assertEquals(next0 + 1, pipe.nextExpectedSlot("M01", 1L))
    }

    /** C0-2: HOLE before playout deadline — WAIT (no advance) on [pullSlot] time authority. */
    @Test
    fun c0_2_hole_beforeDeadline_pullSlotWaitsWithoutAdvance() {
        val pipe = pipeline("M01")
        val t0 = 2_000_000L
        val base = 20_000L
        consumeBase(pipe, "M01", base, t0)
        val hole = base + 1
        val holeMedia = t0 + 20L
        assertFalse(pipe.hasBufferedFrame("M01", 1L, hole))
        val before = pipe.nextExpectedSlot("M01", 1L)!!
        assertEquals(hole, before)
        val wait =
            pipe.pullSlot(
                "M01",
                1L,
                hole,
                holeMedia,
                holeMedia + MediaJitterConstants.MAX_PLAYOUT_DELAY_MS - 1L,
            )
        assertEquals(SlotPullDisposition.EMPTY, wait)
        assertEquals(before, pipe.nextExpectedSlot("M01", 1L))
    }

    /** C1: hole filled before deadline → REAL and S+1. */
    @Test
    fun c1_holeFilledBeforeDeadline_realAdvance() {
        val pipe = pipeline("M01")
        val t0 = 3_000_000L
        val base = 30_000L
        consumeBase(pipe, "M01", base, t0)
        val hole = base + 1
        val holeMedia = t0 + 20L
        assertEquals(FrameAdmitDisposition.QUEUED, admit(pipe, "M01", hole, holeMedia))
        val d = pipe.pullSlot("M01", 1L, hole, holeMedia, holeMedia + 5L)
        assertEquals(SlotPullDisposition.DECODE_FRAME, d)
        assertEquals(hole + 1, pipe.nextExpectedSlot("M01", 1L))
    }

    /** C2: hole at deadline → PLC or EMPTY and exactly S+1. */
    @Test
    fun c2_holeAtDeadline_advancesExactlyOnce() {
        val pipe = pipeline("M01")
        val t0 = 4_000_000L
        val base = 40_000L
        consumeBase(pipe, "M01", base, t0)
        val hole = base + 1
        val holeMedia = t0 + 20L
        val before = pipe.nextExpectedSlot("M01", 1L)!!
        val d =
            pipe.pullSlot(
                "M01",
                1L,
                hole,
                holeMedia,
                holeMedia + MediaJitterConstants.MAX_PLAYOUT_DELAY_MS + 1L,
            )
        assertTrue(
            d == SlotPullDisposition.PLC_SYNTHESIS ||
                d == SlotPullDisposition.SILENCE_GAP ||
                d == SlotPullDisposition.EMPTY,
        )
        assertEquals(before + 1, pipe.nextExpectedSlot("M01", 1L))
    }

    /** C3: S hole with S+1..S+k buffered — futures remain after one consumer step. */
    @Test
    fun c3_holeWithBufferedAhead_futuresPreserved_oneAdvancePerTick() {
        val orch = orchestrator("M02")
        val pipe = orch.pipeline
        val t0 = 5_000_000L
        val base = 50_000L
        seedHoleWithAhead(pipe, "M02", base, t0)
        val hole = base + 1
        assertEquals(hole, pipe.nextExpectedSlot("M02", 1L))
        assertTrue(pipe.hasBufferedFrame("M02", 1L, base + 2))
        assertTrue(pipe.hasBufferedFrame("M02", 1L, base + 5))

        val before = pipe.nextExpectedSlot("M02", 1L)!!
        PerSourceRenderScheduler.tick(orch, t0 + 50L)
        val after = pipe.nextExpectedSlot("M02", 1L)!!
        assertEquals("C0: one tick at most +1", before + 1, after)
        assertTrue(pipe.hasBufferedFrame("M02", 1L, base + 2))
        assertTrue(pipe.hasBufferedFrame("M02", 1L, base + 5))
    }

    /** C4: ingress REORDER / QIW independent of consumer cursor (displacement > MAX_REORDER). */
    @Test
    fun c4_futureDisplacement_exceedsReorder_ingressAlignerWithoutConsumerAdvance() {
        val pipe = pipeline("M01")
        val t0 = 6_000_000L
        val base = 60_000L
        consumeBase(pipe, "M01", base, t0)
        for (i in 2..5) {
            assertEquals(FrameAdmitDisposition.QUEUED, admit(pipe, "M01", base + i, t0 + 20L * i))
        }
        val live = base + 12
        val tLive = t0 + 20L * 12
        assertEquals(
            FrameAdmitDisposition.REORDER_DISPLACEMENT_EXCEEDED,
            admit(pipe, "M01", live, tLive),
        )
        val nextBeforeAlign = pipe.nextExpectedSlot("M01", 1L)!!
        val aligned =
            SessionMediaLiveEdgeAligner.maybeAlignLiveEdgeOnReorder(
                pipeline = pipe,
                sourceIdentity = "M01",
                incarnationId = 1L,
                liveSlot = live,
                mediaTimeMs = tLive,
                arrivalMs = tLive,
                nowMs = tLive,
            )
        assertNotNull(aligned)
        assertEquals(SessionMediaLiveEdgeAligner.Kind.QUEUE_IN_WINDOW, aligned!!.kind)
        assertTrue(pipe.hasBufferedFrame("M01", 1L, live))
        assertEquals(nextBeforeAlign, pipe.nextExpectedSlot("M01", 1L))
    }

    /** C5: RESYNC is ingress transition; consumer pullSlot on hole does not substitute resync. */
    @Test
    fun c5_resync_ingressDomain_notConsumerPlcShortcut() {
        val pipe = pipeline("M01")
        val t0 = 7_000_000L
        val base = 70_000L
        consumeBase(pipe, "M01", base, t0)
        val live = base + SessionMediaLiveEdgeAligner.IN_WINDOW_SLOTS + 2
        val tLive = t0 + MediaJitterConstants.MEDIA_SLOT_MS * (live - base)
        assertEquals(
            FrameAdmitDisposition.REORDER_DISPLACEMENT_EXCEEDED,
            admit(pipe, "M01", live, tLive),
        )
        val aligned =
            SessionMediaLiveEdgeAligner.maybeAlignLiveEdgeOnReorder(
                pipeline = pipe,
                sourceIdentity = "M01",
                incarnationId = 1L,
                liveSlot = live,
                mediaTimeMs = tLive,
                arrivalMs = tLive,
                nowMs = tLive,
            )
        assertNotNull(aligned)
        assertEquals(SessionMediaLiveEdgeAligner.Kind.RESYNC_STALE, aligned!!.kind)
        assertEquals(live, pipe.nextExpectedSlot("M01", 1L))
        assertTrue(pipe.hasBufferedFrame("M01", 1L, live))
    }

    /**
     * C6: WAIT→deadline boundary — pullSlot respects real mediaTime; scheduler empty-jitter
     * path may violate the same invariant (ADR-C hard STOP gate).
     */
    @Test
    fun c6_waitToDeadlineBoundary_pullSlotSingleAdvance_schedulerSeamDocumented() {
        val pipe = pipeline("M01")
        val t0 = 8_000_000L
        val base = 80_000L
        consumeBase(pipe, "M01", base, t0)
        val hole = base + 1
        val holeMedia = t0 + 20L
        val withinDeadline = holeMedia + MediaJitterConstants.MAX_PLAYOUT_DELAY_MS - 2L

        assertEquals(
            SlotPullDisposition.EMPTY,
            pipe.pullSlot("M01", 1L, hole, holeMedia, withinDeadline),
        )
        assertEquals(hole, pipe.nextExpectedSlot("M01", 1L))

        val atDeadline =
            pipe.pullSlot(
                "M01",
                1L,
                hole,
                holeMedia,
                holeMedia + MediaJitterConstants.MAX_PLAYOUT_DELAY_MS + 1L,
            )
        assertTrue(atDeadline != SlotPullDisposition.EMPTY || hole + 1 == pipe.nextExpectedSlot("M01", 1L))
        assertEquals(hole + 1, pipe.nextExpectedSlot("M01", 1L))

        val refPipe = pipeline("M03")
        val orch2 = orchestrator("M03")
        consumeBase(refPipe, "M03", base, t0)
        consumeBase(orch2.pipeline, "M03", base, t0)
        assertEquals(hole, orch2.pipeline.nextExpectedSlot("M03", 1L))
        val nextBeforeTick = orch2.pipeline.nextExpectedSlot("M03", 1L)!!
        PerSourceRenderScheduler.tick(orch2, withinDeadline)
        val nextAfterTick = orch2.pipeline.nextExpectedSlot("M03", 1L)!!
        if (nextAfterTick > nextBeforeTick) {
            val pullWouldWait =
                refPipe.pullSlot("M03", 1L, hole, holeMedia, withinDeadline) ==
                    SlotPullDisposition.EMPTY &&
                    refPipe.nextExpectedSlot("M03", 1L) == hole
            assertTrue(
                "STOP gate: scheduler advanced at withinDeadline while pullSlot would WAIT " +
                    "(synthetic slotMediaTime vs real hole mediaTime)",
                pullWouldWait,
            )
        }
    }

    private fun pipeline(id: String): MediaExecutionPipeline {
        val p = MediaExecutionPipeline()
        p.install(AdmittedMediaSource(id, 1L))
        return p
    }

    private fun orchestrator(id: String): ConferenceMediaExecutionOrchestrator {
        val o = ConferenceMediaExecutionOrchestrator()
        o.selection.install(AdmittedMediaSource(id, 1L))
        o.pipeline.install(AdmittedMediaSource(id, 1L))
        o.observeVoice(
            VoiceLevelObservation(
                sourceIdentity = id,
                incarnationId = 1L,
                voiceActive = true,
                audioLevel = 90,
            ),
        )
        return o
    }

    private fun admit(
        pipe: MediaExecutionPipeline,
        id: String,
        slot: Long,
        ms: Long,
    ): FrameAdmitDisposition =
        pipe.admitFrame(
            AdmittedMediaFrame(
                sourceIdentity = id,
                incarnationId = 1L,
                mediaSlot = slot,
                mediaTimeMs = ms,
                arrivalMs = ms,
            ),
            ms,
        )

    private fun consumeBase(
        pipe: MediaExecutionPipeline,
        id: String,
        base: Long,
        t0: Long,
    ) {
        admit(pipe, id, base, t0)
        pipe.pullSlot(id, 1L, base, t0, t0 + 20L)
    }

    private fun seedHoleWithAhead(
        pipe: MediaExecutionPipeline,
        id: String,
        base: Long,
        t0: Long,
    ) {
        admit(pipe, id, base, t0)
        pipe.pullSlot(id, 1L, base, t0, t0 + 20L)
        for (i in 2..5) {
            assertEquals(FrameAdmitDisposition.QUEUED, admit(pipe, id, base + i, t0 + 20L * i))
        }
    }
}
