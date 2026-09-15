package com.talkback.core.conference.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * E2b-05 / C-IG-01 harness: R12-A/B, R13–R16.
 */
class Profile03E2b05AndroidResourcesHarnessTest {
    @Test
    fun r12a_rejectNew_eleventhRejected_activeStays10() {
        val rt = AndroidRuntimeResources()
        rt.jitterAllocator.policy = JitterAllocPolicy.REJECT_NEW
        fillJitter(rt, count = 10)
        assertEquals(10, rt.jitterAllocator.activeCount())

        rt.install(AdmittedMediaSource("S11", 1L))
        val r = rt.allocateJitter("S11", 1L, nowMs = 1L)
        assertEquals(JitterAllocOutcome.REJECT_NEW, r.outcome)
        assertEquals(10, rt.jitterAllocator.activeCount())
        assertEquals(MediaResourceConstants.MAX_JITTER_SOURCES, 10)
        assertTrue(
            rt.evidenceSnapshot().any { it.kind == RuntimeDegradationKind.JITTER_CAP_EXHAUSTED },
        )
    }

    @Test
    fun r12b_reclaimThenAllocate_activeStays10() {
        val rt = AndroidRuntimeResources()
        rt.jitterAllocator.policy = JitterAllocPolicy.RECLAIM_EXISTING_THEN_ALLOCATE
        fillJitter(rt, count = 10)
        val before = rt.jitterAllocator.activeIdentities()
        assertEquals(10, before.size)

        rt.install(AdmittedMediaSource("S11", 1L))
        val r = rt.allocateJitter("S11", 1L, nowMs = 1L)
        assertEquals(JitterAllocOutcome.RECLAIM_EXISTING_THEN_ALLOCATE, r.outcome)
        assertNotNull(r.reclaimedIdentity)
        assertTrue(r.reclaimedIdentity in before)
        assertEquals(10, rt.jitterAllocator.activeCount())
        assertTrue("S11" in rt.jitterAllocator.activeIdentities())
        assertFalse(r.reclaimedIdentity in rt.jitterAllocator.activeIdentities())
    }

    @Test
    fun r12_unboundSource_cannotAllocateJitter() {
        val rt = AndroidRuntimeResources()
        val r = rt.allocateJitter("unbound", 1L, nowMs = 0L)
        assertEquals(JitterAllocOutcome.NOT_ADMITTED, r.outcome)
        assertEquals(0, rt.jitterAllocator.activeCount())
    }

    @Test
    fun r13a_silenceEmptyTopK_doesNotReleaseLock() {
        val rt = AndroidRuntimeResources()
        assertTrue(rt.beginTransportScope(TransportHandle("sock-1"), 0L))
        assertTrue(rt.multicastLock.isHeld())
        val releasesBefore = rt.multicastLock.releaseCount()

        rt.onSilenceOrEmptyTopKOrSoftReclaim()
        // Also simulate V=0 / empty Top-K soft reclaim on decoders
        rt.decoderPool.release("any")

        assertTrue(rt.multicastLock.isHeld())
        assertEquals(releasesBefore, rt.multicastLock.releaseCount())
    }

    @Test
    fun r13b_transportScopeEnd_releasesLock() {
        val rt = AndroidRuntimeResources()
        assertTrue(rt.beginTransportScope(TransportHandle("sock-1"), 0L))
        assertTrue(rt.multicastLock.isHeld())
        rt.endTransportScope()
        assertFalse(rt.multicastLock.isHeld())
        assertEquals(1, rt.multicastLock.releaseCount())
    }

    @Test
    fun r14_rebind_preservesAuthorityAndFence() {
        val rt = AndroidRuntimeResources()
        rt.install(AdmittedMediaSource("S1", 7L))
        rt.install(AdmittedMediaSource("S2", 7L))
        assertTrue(rt.hardFence("S1", 7L))
        assertTrue(rt.beginTransportScope(TransportHandle("sock-old"), 0L))

        val before = rt.authoritySnapshot()
        assertEquals(ExecutionFenceState.HARD_FENCED, before.fenceByIdentity["S1"])

        val newHandle = rt.rebindTransport(TransportHandle("sock-new"), nowMs = 10L)
        assertEquals("sock-new", newHandle.id)
        assertEquals(1, rt.rebindSeam.rebindCount)

        val after = rt.authoritySnapshot()
        assertEquals(before.admittedIdentities, after.admittedIdentities)
        assertEquals(before.incarnationByIdentity, after.incarnationByIdentity)
        assertEquals(before.fenceByIdentity, after.fenceByIdentity)
        assertEquals(before.membershipIdentity, after.membershipIdentity)
        assertEquals(before.conferenceGeneration, after.conferenceGeneration)
        assertEquals(before.anchorEpoch, after.anchorEpoch)
        assertEquals(ExecutionFenceState.HARD_FENCED, after.fenceByIdentity["S1"])
        assertNotEquals("sock-old", rt.transportState().handle?.id)
    }

    @Test
    fun r15_audioTrackFailure_evidenceOnly_noAuthorityMutation() {
        val rt = AndroidRuntimeResources()
        rt.install(AdmittedMediaSource("S1", 1L))
        rt.observeVoice(VoiceLevelObservation("S1", 1L, true, 20))
        rt.selectTopK(0L)
        val before = rt.authoritySnapshot()
        val topKBefore = rt.selection.currentTopK().members.size

        rt.audioTrack.failKind = RuntimeDegradationKind.AUDIOTRACK_UNDERRUN
        val block = MixedBlock(shortArrayOf(100, -100), mixParticipantCount = 1, peakAbsFs = 0.1)
        assertFalse(rt.playout(block, nowMs = 5L))

        assertTrue(
            rt.evidenceSnapshot().any { it.kind == RuntimeDegradationKind.AUDIOTRACK_UNDERRUN },
        )
        val after = rt.authoritySnapshot()
        assertEquals(before, after)
        assertEquals(topKBefore, rt.selection.currentTopK().members.size)
        assertEquals(MediaMixConstants.MIX_MAX_SOURCES, 4)
        assertEquals(MediaMixConstants.MAX_LIVE_DECODERS, 5)
        assertEquals(ExecutionFenceState.OPEN, after.fenceByIdentity["S1"])
    }

    @Test
    fun r16_resourceExhaustion_rejects_capsUnchanged() {
        val rt = AndroidRuntimeResources()
        // Fill MaxLiveDecoders=5
        for (i in 1..5) {
            val id = "D$i"
            rt.install(AdmittedMediaSource(id, 1L))
            assertNotNull(rt.allocateDecoder(id, 1L, nowMs = 0L, forTransitionOnly = i == 5))
        }
        assertEquals(5, rt.decoderPool.liveCount())

        rt.install(AdmittedMediaSource("D6", 1L))
        assertNull(rt.allocateDecoder("D6", 1L, nowMs = 1L))
        assertTrue(
            rt.evidenceSnapshot().any {
                it.kind == RuntimeDegradationKind.RESOURCE_ALLOCATION_REJECTED
            },
        )

        // Fill jitter to 10 then reject
        rt.jitterAllocator.policy = JitterAllocPolicy.REJECT_NEW
        fillJitter(rt, count = 10, prefix = "J")
        rt.install(AdmittedMediaSource("J11", 1L))
        assertEquals(JitterAllocOutcome.REJECT_NEW, rt.allocateJitter("J11", 1L, 2L).outcome)

        assertEquals(MediaMixConstants.MAX_LIVE_DECODERS, 5)
        assertEquals(MediaMixConstants.MIX_MAX_SOURCES, 4)
        assertEquals(MediaRuntimeConstants.TOP_K, 4)
        assertEquals(MediaResourceConstants.MAX_JITTER_SOURCES, 10)
        assertEquals(5, rt.decoderPool.liveCount())
        assertEquals(10, rt.jitterAllocator.activeCount())
    }

    @Test
    fun cE2b05_06_facadeDoesNotLegalizeExecution() {
        val rt = AndroidRuntimeResources()
        assertTrue(rt.beginTransportScope(TransportHandle("sock"), 0L))
        // Transport + lock held, but no AdmittedMediaSource → not decode-eligible
        assertFalse(rt.selection.isDecodeEligible("ghost", 1L))
        assertEquals(JitterAllocOutcome.NOT_ADMITTED, rt.allocateJitter("ghost", 1L, 0L).outcome)
    }

    private fun fillJitter(
        rt: AndroidRuntimeResources,
        count: Int,
        prefix: String = "S",
    ) {
        for (i in 1..count) {
            val id = "$prefix$i"
            rt.install(AdmittedMediaSource(id, 1L))
            val r = rt.allocateJitter(id, 1L, nowMs = 0L)
            assertEquals(JitterAllocOutcome.ALLOCATED, r.outcome)
        }
    }
}
