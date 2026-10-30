package com.talkback.core.conference.playout

import com.talkback.core.conference.runtime.AdmittedMediaFrame
import com.talkback.core.conference.runtime.AdmittedMediaSource
import com.talkback.core.conference.runtime.ConferenceMediaExecutionOrchestrator
import com.talkback.core.conference.runtime.FrameAdmitDisposition
import com.talkback.core.conference.runtime.VoiceLevelObservation
import com.talkback.core.conference.session.HoleSideStaging
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class Profile01HoleSideStagingDeskTest {
    private val lines = mutableListOf<String>()

    @Before
    fun setUp() {
        lines.clear()
        HoleSideStaging.resetForTest()
        HoleSideStaging.testLogSink = { lines += it }
        PerSourceRenderScheduler.boundedCatchUpStep4Enabled = false
        PerSourceRenderScheduler.strongCatchUpEnabled = false
    }

    @After
    fun tearDown() {
        HoleSideStaging.resetForTest()
    }

    @Test
    fun flagOff_default() {
        assertFalse(HoleSideStaging.enabled)
    }

    @Test
    fun stageThenDrain_whenNextAdvancesIntoReorderWindow() {
        HoleSideStaging.enabled = true
        val orch = orch("M02")
        val pipe = orch.pipeline
        val t0 = 9_000_000L
        val base = 90_000L
        assertEquals(
            FrameAdmitDisposition.QUEUED,
            pipe.admitFrame(frame("M02", 1L, base, t0), t0),
        )
        pipe.pullSlot("M02", 1L, base, t0, t0 + 20L)
        // next = base+1 hole; far live would REORDER
        val live = base + 12
        val far = frame("M02", 1L, live, t0 + 40L)
        assertTrue(
            HoleSideStaging.maybeStageOnReorder(pipe, far, t0 + 40L),
        )
        assertTrue(lines.any { it.startsWith("L2_HOLE_STAGE ") })
        // Advance cursor near live via PLC-like skips
        repeat(8) { i ->
            val slot = base + 1 + i
            pipe.concealMissingExpectedSlot("M02", 1L, slot)
        }
        val drained =
            HoleSideStaging.drain(
                pipeline = pipe,
                sourceIdentity = "M02",
                incarnationId = 1L,
                nowMs = t0 + 200L,
            )
        assertTrue("expected drain>0 got $drained", drained > 0)
        assertTrue(pipe.hasBufferedFrame("M02", 1L, live))
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