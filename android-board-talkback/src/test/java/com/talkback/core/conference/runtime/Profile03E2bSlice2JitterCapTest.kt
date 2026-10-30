package com.talkback.core.conference.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ADR-0058 E2b Slice 2 — R12 on unified production chain ([MediaExecutionPipeline] cap).
 */
class Profile03E2bSlice2JitterCapTest {
    @Test
    fun r12a_pipelineRejectNew_eleventhFrameRejected_activeStays10() {
        val orch = ConferenceMediaExecutionOrchestrator()
        orch.pipeline.jitterAllocator.policy = JitterAllocPolicy.REJECT_NEW
        fillTenWithFrames(orch)

        orch.install(AdmittedMediaSource("S11", 1L))
        val topKBefore = orch.selection.currentTopK().members.map { it.sourceIdentity }.toSet()

        val disposition =
            orch.admitFrame(
                frame("S11", slot = 0, mediaTime = 5_000L, arrival = 5_001L),
                nowMs = 5_001L,
            )
        assertEquals(FrameAdmitDisposition.JITTER_CAP_REJECTED, disposition)
        assertEquals(JitterAllocOutcome.REJECT_NEW, orch.pipeline.lastJitterAllocResult?.outcome)
        assertEquals(10, orch.pipeline.activeJitterSourceCount())
        assertEquals(MediaResourceConstants.MAX_JITTER_SOURCES, 10)
        assertEquals(0, orch.pipeline.jitterSize("S11", 1L))

        orch.selectTopK(5_010L)
        assertEquals(topKBefore, orch.selection.currentTopK().members.map { it.sourceIdentity }.toSet())
        for (i in 1..11) {
            assertTrue(orch.selection.registry.get("S$i") != null || i == 11)
        }
        assertEquals(ExecutionFenceState.OPEN, orch.selection.registry.get("S11")!!.fence)
    }

    @Test
    fun r12b_pipelineReclaimThenAllocate_activeStays10() {
        val orch = ConferenceMediaExecutionOrchestrator()
        orch.pipeline.jitterAllocator.policy = JitterAllocPolicy.RECLAIM_EXISTING_THEN_ALLOCATE
        fillTenWithFrames(orch)
        val before = orch.pipeline.jitterAllocator.activeIdentities()
        assertEquals(10, before.size)

        orch.install(AdmittedMediaSource("S11", 1L))
        val disposition =
            orch.admitFrame(
                frame("S11", slot = 0, mediaTime = 6_000L, arrival = 6_001L),
                nowMs = 6_001L,
            )
        assertEquals(FrameAdmitDisposition.QUEUED, disposition)
        assertEquals(
            JitterAllocOutcome.RECLAIM_EXISTING_THEN_ALLOCATE,
            orch.pipeline.lastJitterAllocResult?.outcome,
        )
        val reclaimed = orch.pipeline.lastJitterAllocResult?.reclaimedIdentity
        assertNotNull(reclaimed)
        assertTrue(reclaimed in before)
        assertEquals(10, orch.pipeline.activeJitterSourceCount())
        assertTrue("S11" in orch.pipeline.jitterAllocator.activeIdentities())
        assertFalse(reclaimed in orch.pipeline.jitterAllocator.activeIdentities())
        assertEquals(0, orch.pipeline.jitterSize(reclaimed!!, 1L))
        assertEquals(1, orch.pipeline.jitterSize("S11", 1L))
    }

    @Test
    fun r12_unboundSource_notAdmitted_noJitterBypass() {
        val orch = ConferenceMediaExecutionOrchestrator()
        val disposition =
            orch.admitFrame(
                frame("unbound", slot = 0, mediaTime = 0L, arrival = 1L),
                nowMs = 1L,
            )
        assertEquals(FrameAdmitDisposition.NOT_ADMITTED_INCARNATION, disposition)
        assertEquals(0, orch.pipeline.activeJitterSourceCount())
    }

    private fun fillTenWithFrames(orch: ConferenceMediaExecutionOrchestrator) {
        for (i in 1..10) {
            val id = "S$i"
            orch.install(AdmittedMediaSource(id, 1L))
            assertEquals(
                FrameAdmitDisposition.QUEUED,
                orch.admitFrame(
                    frame(id, slot = 0, mediaTime = 1_000L + i, arrival = 1_000L + i),
                    nowMs = 1_000L + i,
                ),
            )
        }
        assertEquals(10, orch.pipeline.activeJitterSourceCount())
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
