package com.talkback.core.conference.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * E2b-03 C-IG-02 harness: R04 / R05 / R06 / R07 / R09 queued-work race.
 */
class Profile03E2b03JitterPlcHarnessTest {
    @Test
    fun r04_reorder_mediaTimeDelivery() {
        val pipe = pipelineWithEligibleSource("S1")
        val base = 1_000L
        // Arrive T+2, T, T+1 — all in-time, reorder depth <= 4
        assertEquals(
            FrameAdmitDisposition.QUEUED,
            pipe.admitFrame(frame("S1", 2, base + 40, base + 5), base + 5),
        )
        assertEquals(
            FrameAdmitDisposition.QUEUED,
            pipe.admitFrame(frame("S1", 0, base, base + 10), base + 10),
        )
        assertEquals(
            FrameAdmitDisposition.QUEUED,
            pipe.admitFrame(frame("S1", 1, base + 20, base + 15), base + 15),
        )

        val now = base + 50
        assertEquals(SlotPullDisposition.DECODE_FRAME, pipe.pullSlot("S1", 1L, 0, base, now))
        assertEquals(SlotPullDisposition.DECODE_FRAME, pipe.pullSlot("S1", 1L, 1, base + 20, now))
        assertEquals(SlotPullDisposition.DECODE_FRAME, pipe.pullSlot("S1", 1L, 2, base + 40, now))
        assertEquals(listOf(0L, 1L, 2L), pipe.decodeOrderSlots)
        assertEquals(0, pipe.lateForPlayoutCount)
        assertEquals(3, pipe.decodeCount)
    }

    @Test
    fun cE2b03_01_inOrderBufferMayExceedFourFrames() {
        val pipe = pipelineWithEligibleSource("S1")
        val base = 2_000L
        for (i in 0 until 8) {
            val d =
                pipe.admitFrame(
                    frame("S1", i.toLong(), base + i * 20L, base + i),
                    nowMs = base + i,
                )
            assertEquals("slot $i", FrameAdmitDisposition.QUEUED, d)
        }
        assertEquals(8, pipe.jitterSize("S1", 1L))
    }

    @Test
    fun r05_lateOver120ms_lateForPlayout_noDecode() {
        val pipe = pipelineWithEligibleSource("S1")
        val mediaTime = 5_000L
        val arrival = mediaTime + MediaJitterConstants.MAX_PLAYOUT_DELAY_MS + 1
        val d =
            pipe.admitFrame(
                frame("S1", 0, mediaTime, arrival),
                nowMs = arrival,
            )
        assertEquals(FrameAdmitDisposition.LATE_FOR_PLAYOUT, d)
        assertEquals(1, pipe.lateForPlayoutCount)
        assertEquals(0, pipe.decodeCount)
        assertNotEquals("SRTP_REPLAY_TOO_OLD", d.name)
        assertFalse(d.name.contains("REPLAY"))
    }

    @Test
    fun r06_threeLostSlots_plcExactly3() {
        val pipe = pipelineWithEligibleSource("S1")
        val base = 10_000L
        // No packets; advance three slots past deadline
        for (i in 0 until 3) {
            val slotMedia = base + i * 20L
            val now = slotMedia + MediaJitterConstants.MAX_PLAYOUT_DELAY_MS + 1
            val d = pipe.pullSlot("S1", 1L, i.toLong(), slotMedia, now)
            assertEquals("slot $i", SlotPullDisposition.PLC_SYNTHESIS, d)
        }
        assertEquals(3, pipe.plcCount)
        assertEquals(0, pipe.silenceGapCount)
        assertEquals(ExecutionFenceState.OPEN, pipe.selection.registry.get("S1")!!.fence)
    }

    @Test
    fun r07_sixLostSlots_plc5ThenSilence() {
        val pipe = pipelineWithEligibleSource("S1")
        val base = 20_000L
        val dispositions = mutableListOf<SlotPullDisposition>()
        for (i in 0 until 6) {
            val slotMedia = base + i * 20L
            val now = slotMedia + MediaJitterConstants.MAX_PLAYOUT_DELAY_MS + 1
            dispositions += pipe.pullSlot("S1", 1L, i.toLong(), slotMedia, now)
        }
        assertEquals(5, dispositions.count { it == SlotPullDisposition.PLC_SYNTHESIS })
        assertEquals(1, dispositions.count { it == SlotPullDisposition.SILENCE_GAP })
        assertEquals(5, pipe.plcCount)
        assertEquals(1, pipe.silenceGapCount)
        assertEquals(ExecutionFenceState.OPEN, pipe.selection.registry.get("S1")!!.fence)
    }

    @Test
    fun r09_queuedFrameThenFence_noDecodeNoPlc() {
        val pipe = pipelineWithEligibleSource("S1")
        val base = 30_000L
        assertEquals(
            FrameAdmitDisposition.QUEUED,
            pipe.admitFrame(frame("S1", 0, base, base + 1), base + 1),
        )
        assertEquals(1, pipe.jitterSize("S1", 1L))

        assertTrue(pipe.hardFence("S1", 1L))
        assertFalse(pipe.isJitterExecutable("S1", 1L))

        // At original playout-capable time
        val now = base + 50
        val d = pipe.pullSlot("S1", 1L, 0, base, now)
        assertEquals(SlotPullDisposition.FENCED_SKIP, d)
        assertEquals(0, pipe.decodeCount)
        assertEquals(0, pipe.plcCount)
    }

    @Test
    fun plc_requiresDecodeEligibility() {
        val pipe = MediaExecutionPipeline()
        pipe.install(AdmittedMediaSource("quiet", 1L))
        pipe.install(AdmittedMediaSource("a", 1L))
        pipe.install(AdmittedMediaSource("b", 1L))
        pipe.install(AdmittedMediaSource("c", 1L))
        pipe.install(AdmittedMediaSource("d", 1L))
        pipe.observeVoice(VoiceLevelObservation("a", 1L, true, 10))
        pipe.observeVoice(VoiceLevelObservation("b", 1L, true, 20))
        pipe.observeVoice(VoiceLevelObservation("c", 1L, true, 30))
        pipe.observeVoice(VoiceLevelObservation("d", 1L, true, 40))
        pipe.observeVoice(VoiceLevelObservation("quiet", 1L, true, 90))
        pipe.selectTopK(0L)
        assertFalse(pipe.selection.isDecodeEligible("quiet", 1L))

        val base = 40_000L
        val now = base + MediaJitterConstants.MAX_PLAYOUT_DELAY_MS + 1
        val d = pipe.pullSlot("quiet", 1L, 0, base, now)
        assertEquals(SlotPullDisposition.SILENCE_GAP, d)
        assertEquals(0, pipe.plcCount)
    }

    private fun pipelineWithEligibleSource(id: String): MediaExecutionPipeline {
        val pipe = MediaExecutionPipeline()
        pipe.install(AdmittedMediaSource(id, 1L))
        pipe.observeVoice(VoiceLevelObservation(id, 1L, true, 20))
        pipe.selectTopK(0L)
        assertTrue(pipe.selection.isDecodeEligible(id, 1L))
        return pipe
    }

    private fun frame(
        id: String,
        slot: Long,
        mediaTime: Long,
        arrival: Long,
    ): AdmittedMediaFrame =
        AdmittedMediaFrame(
            sourceIdentity = id,
            incarnationId = 1L,
            mediaSlot = slot,
            mediaTimeMs = mediaTime,
            arrivalMs = arrival,
        )
}
