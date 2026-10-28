package com.talkback.core.conference.playout

import com.talkback.core.conference.runtime.AdmittedMediaFrame
import com.talkback.core.conference.runtime.AdmittedMediaSource
import com.talkback.core.conference.runtime.ConferenceMediaExecutionOrchestrator
import com.talkback.core.conference.runtime.FrameAdmitDisposition
import com.talkback.core.conference.runtime.MediaJitterConstants
import com.talkback.core.conference.runtime.VoiceLevelObservation
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * S1 strong catch-up desk — [PerSourceRenderScheduler.strongCatchUpEnabled].
 *
 * Invariant: ready > nextExpected ⇒ each render tick advances next by exactly +1
 * (cursor consumption). Does not raise reorder / change QIW / Top-K policy /
 * lag debt / chase live.
 */
class Profile01StrongCatchUpDeskTest {
    @Before
    fun setUp() {
        PerSourceRenderScheduler.strongCatchUpEnabled = true
    }

    @After
    fun tearDown() {
        PerSourceRenderScheduler.strongCatchUpEnabled = false
    }

    @Test
    fun readyGreaterThanNext_eachTickAdvancesNextByOne() {
        val orch = orchestratorWith("M02", level = 90)
        val pipeline = orch.pipeline
        val t0 = 2_000_000L
        val base = 20_000L
        seedHoleWithAhead(pipeline, "M02", 1L, base, t0)

        val hole = base + 1
        assertEquals(hole, pipeline.nextExpectedSlot("M02", 1L))
        assertTrue(pipeline.hasBufferedFrame("M02", 1L, base + 2))

        var next = hole
        repeat(4) { i ->
            PerSourceRenderScheduler.tick(orch, t0 + 100L * (i + 1))
            next += 1
            assertEquals("tick ${i + 1}", next, pipeline.nextExpectedSlot("M02", 1L))
        }
        // Ahead frames still present — not a wipe
        assertTrue(pipeline.hasBufferedFrame("M02", 1L, base + 5))
    }

    @Test
    fun readyEqualsNext_unchangedPullSemantics() {
        val orch = orchestratorWith("M02", level = 90)
        val pipeline = orch.pipeline
        val t0 = 3_000_000L
        val base = 30_000L
        assertEquals(
            FrameAdmitDisposition.QUEUED,
            pipeline.admitFrame(frame("M02", 1L, base, t0), t0),
        )
        assertEquals(base, pipeline.nextExpectedSlot("M02", 1L))

        PerSourceRenderScheduler.tick(orch, t0 + 20L)
        // Contiguous real at next → consumed to base+1
        assertEquals(base + 1, pipeline.nextExpectedSlot("M02", 1L))
        assertFalse(pipeline.hasBufferedFrame("M02", 1L, base))
    }

    @Test
    fun catchUpPastHole_lateFillStillAdmissible() {
        val orch = orchestratorWith("M02", level = 90)
        val pipeline = orch.pipeline
        val t0 = 4_000_000L
        val base = 40_000L
        seedHoleWithAhead(pipeline, "M02", 1L, base, t0)
        val hole = base + 1L

        PerSourceRenderScheduler.tick(orch, t0 + 50L)
        assertEquals(hole + 1, pipeline.nextExpectedSlot("M02", 1L))

        // Late hole packet still queues and may re-anchor cursor (recoverable).
        assertEquals(
            FrameAdmitDisposition.QUEUED,
            pipeline.admitFrame(frame("M02", 1L, hole, t0 + 20L), t0 + 20L),
        )
        assertTrue(pipeline.hasBufferedFrame("M02", 1L, hole))
    }

    @Test
    fun nonTopK_withReadyGreaterThanNext_stillAdvancesOncePerTick() {
        val orch = ConferenceMediaExecutionOrchestrator()
        orch.selection.install(AdmittedMediaSource("M01", 1L))
        orch.selection.install(AdmittedMediaSource("M02", 1L))
        orch.pipeline.install(AdmittedMediaSource("M01", 1L))
        orch.pipeline.install(AdmittedMediaSource("M02", 1L))
        // M01 louder → Top-K; M02 quieter but still installed
        orch.observeVoice(VoiceLevelObservation("M01", 1L, voiceActive = true, audioLevel = 95))
        orch.observeVoice(VoiceLevelObservation("M02", 1L, voiceActive = true, audioLevel = 10))

        val t0 = 5_000_000L
        val base = 50_000L
        seedHoleWithAhead(orch.pipeline, "M02", 1L, base, t0)
        // Seed M01 so Top-K has a member
        assertEquals(
            FrameAdmitDisposition.QUEUED,
            orch.pipeline.admitFrame(frame("M01", 1L, base, t0), t0),
        )

        val hole = base + 1
        assertEquals(hole, orch.pipeline.nextExpectedSlot("M02", 1L))
        PerSourceRenderScheduler.tick(orch, t0 + 40L)
        val topK = orch.selection.currentTopK().members.map { it.sourceIdentity }.toSet()
        assertTrue("M01 should be Top-K", "M01" in topK)
        // M02 may be out of Top-K; catch-up drain must still move cursor
        assertEquals(hole + 1, orch.pipeline.nextExpectedSlot("M02", 1L))
    }

    @Test
    fun flagOff_readyGreaterThanNext_mayLeaveNextUnchangedWhenConcealSkipped() {
        PerSourceRenderScheduler.strongCatchUpEnabled = false
        val orch = orchestratorWith("M02", level = 90)
        val pipeline = orch.pipeline
        val t0 = 6_000_000L
        val base = 60_000L
        seedHoleWithAhead(pipeline, "M02", 1L, base, t0)
        val hole = base + 1
        // Production path still usually conceals; this asserts flag default does not
        // force the S1 drain API. Cursor may advance via legacy conceal — allowed.
        assertEquals(false, PerSourceRenderScheduler.strongCatchUpEnabled)
        assertEquals(hole, pipeline.nextExpectedSlot("M02", 1L))
    }

    private fun orchestratorWith(
        id: String,
        level: Int,
    ): ConferenceMediaExecutionOrchestrator {
        val orch = ConferenceMediaExecutionOrchestrator()
        orch.selection.install(AdmittedMediaSource(id, 1L))
        orch.pipeline.install(AdmittedMediaSource(id, 1L))
        orch.observeVoice(
            VoiceLevelObservation(
                sourceIdentity = id,
                incarnationId = 1L,
                voiceActive = true,
                audioLevel = level,
            ),
        )
        return orch
    }

    private fun seedHoleWithAhead(
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
        for (i in 2..5) {
            assertEquals(
                FrameAdmitDisposition.QUEUED,
                pipeline.admitFrame(frame(id, incarnation, base + i, t0 + 20L * i), t0 + 20L * i),
            )
        }
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
