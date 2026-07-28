package com.talkback.core.signaling.peer

import com.talkback.core.signaling.link.LinkQualificationState
import com.talkback.core.signaling.link.LinkQualificationTracker
import com.talkback.core.signaling.link.TransportCapabilitySnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PeerEdgeGenerationInvalidateTest {

    @Test
    fun invSig001_004_advanceGeneration_syncInvalidatesPeerReady() {
        val tracker = LinkQualificationTracker(scheduler = null)
        tracker.onSocketBound(1L, 1L, "net")
        var generation = tracker.currentRebindGeneration()
        val readiness = PeerEdgeSignalingReadiness(
            moduleStaleMs = 15_000L,
            clock = { 1_000_000L },
            localSnapshot = {
                TransportCapabilitySnapshot(
                    linkQualification = LinkQualificationState.BIDIRECTIONAL_READY,
                    rebindGeneration = generation
                )
            }
        )
        readiness.onPeerInboundObserved(
            PeerInboundObserved("M03", socketId = 1L, receiveGeneration = generation, observedAtMs = 1_000_000L)
        )
        assertTrue(readiness.isReady("M03"))

        val order = mutableListOf<String>()
        tracker.onSignalingGenerationAdvanced = { prior, next ->
            order.add("invalidate")
            readiness.invalidateGeneration(prior)
            generation = next
            order.add("advanced:$next")
        }
        val newGen = tracker.advanceRebindGeneration()
        order.add("returned:$newGen")

        assertEquals(2L, newGen)
        assertEquals(listOf("invalidate", "advanced:2", "returned:2"), order)
        assertFalse(readiness.isReady("M03"))
    }

    @Test
    fun invSig005_015_prrAnnounceDoesNotSetReady() {
        val readiness = PeerEdgeSignalingReadiness(
            moduleStaleMs = 15_000L,
            clock = { 1_000_000L },
            localSnapshot = {
                TransportCapabilitySnapshot(
                    linkQualification = LinkQualificationState.BIDIRECTIONAL_READY,
                    rebindGeneration = 6L
                )
            }
        )
        assertFalse(readiness.isReady("M03"))
    }

    @Test
    fun invSig012_013_staleHint_doesNotAdvanceGeneration() {
        val tracker = LinkQualificationTracker(scheduler = null)
        tracker.onSocketBound(1L, 6L, "net")
        val genBefore = tracker.currentRebindGeneration()
        var now = 1_000L
        val announced = mutableListOf<String>()
        val readiness = PeerEdgeSignalingReadiness(
            moduleStaleMs = 100L,
            clock = { now },
            localSnapshot = {
                TransportCapabilitySnapshot(
                    linkQualification = LinkQualificationState.BIDIRECTIONAL_READY,
                    socketId = tracker.snapshot().socketId,
                    rebindGeneration = tracker.snapshot().rebindGeneration,
                    networkId = tracker.snapshot().networkId
                )
            }
        )
        val hint = PeerEdgePrrHintCoordinator(
            debounceMs = 0L,
            clock = { now },
            scheduler = null,
            isStillNotReady = { !readiness.isReady(it) },
            announcePeer = { announced.add(it) }
        )
        readiness.onPeerEdgeSignalingLost = hint::onPeerEdgeSignalingLost
        readiness.onPeerInboundObserved(
            PeerInboundObserved("M03", 1L, 6L, observedAtMs = now)
        )
        now += 200L
        readiness.evaluateFreshness()
        assertEquals(listOf("M03"), announced)
        assertEquals(genBefore, tracker.currentRebindGeneration())
    }
}