package com.talkback.core.conference.playout

import com.talkback.core.conference.runtime.AdmittedMediaFrame
import com.talkback.core.conference.runtime.AdmittedMediaSource
import com.talkback.core.conference.runtime.ConferenceMediaExecutionOrchestrator
import com.talkback.core.conference.runtime.FrameAdmitDisposition
import com.talkback.core.conference.runtime.VoiceLevelObservation
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * BOUNDED_CATCHUP_STEP4 field gate desk.
 * Default flag off; when on, hole + live-next > MAX_REORDER → skip ≤4 toward live-4.
 */
class Profile01BoundedCatchUpStep4DeskTest {
    @Before
    fun setUp() {
        PerSourceRenderScheduler.boundedCatchUpStep4Enabled = false
        PerSourceRenderScheduler.strongCatchUpEnabled = false
    }

    @After
    fun tearDown() {
        PerSourceRenderScheduler.boundedCatchUpStep4Enabled = false
        PerSourceRenderScheduler.strongCatchUpEnabled = false
    }

    @Test
    fun flagOff_default() {
        assertEquals(false, PerSourceRenderScheduler.boundedCatchUpStep4Enabled)
    }

    @Test
    fun pipeline_skipsAtMost4_towardLiveMinusReorder() {
        val orch = orch("M02")
        val pipe = orch.pipeline
        val t0 = 7_000_000L
        val base = 70_000L
        seedFarHole(pipe, "M02", 1L, base, t0)
        val hole = base + 1
        assertEquals(hole, pipe.nextExpectedSlot("M02", 1L))
        val skipped = pipe.boundedCatchUpTowardLive("M02", 1L, maxStep = 4L)
        assertEquals(4L, skipped)
        assertEquals(hole + 4, pipe.nextExpectedSlot("M02", 1L))
        // Live edge retained
        assertTrue(pipe.hasBufferedFrame("M02", 1L, base + 12))
    }

    @Test
    fun flagOn_tick_appliesStep4Once() {
        PerSourceRenderScheduler.boundedCatchUpStep4Enabled = true
        val orch = orch("M02")
        val pipe = orch.pipeline
        val t0 = 8_000_000L
        val base = 80_000L
        seedFarHole(pipe, "M02", 1L, base, t0)
        val hole = base + 1
        assertEquals(hole, pipe.nextExpectedSlot("M02", 1L))
        PerSourceRenderScheduler.tick(orch, t0 + 40L)
        // One tick: catch-up +4, then likely PLC/pull at new next → +1 more possible
        val after = pipe.nextExpectedSlot("M02", 1L)!!
        assertTrue("expected advance ≥4, got ${after - hole}", after >= hole + 4)
        assertTrue("should not jump past live-4 in one step4", after <= base + 12 - 4 + 1)
    }

    private fun orch(id: String): ConferenceMediaExecutionOrchestrator {
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

    private fun seedFarHole(
        pipeline: com.talkback.core.conference.runtime.MediaExecutionPipeline,
        id: String,
        incarnation: Long,
        base: Long,
        t0: Long,
    ) {
        assertEquals(
            FrameAdmitDisposition.QUEUED,
            pipeline.admitFrame(frame(id, incarnation, base, t0), t0),
        )
        pipeline.pullSlot(id, incarnation, base, t0, t0 + 20L)
        // next = base+1 hole; queue live at base+12 beyond reorder via QIW helper
        pipeline.queueWithinPlayoutWindow(
            frame(id, incarnation, base + 12, t0 + 40L),
            recoverableHoleSlot = base + 1,
        )
    }

    private fun frame(
        id: String,
        incarnation: Long,
        slot: Long,
        ms: Long,
    ) = AdmittedMediaFrame(
        sourceIdentity = id,
        incarnationId = incarnation,
        mediaSlot = slot,
        mediaTimeMs = ms,
        arrivalMs = ms,
    )
}
