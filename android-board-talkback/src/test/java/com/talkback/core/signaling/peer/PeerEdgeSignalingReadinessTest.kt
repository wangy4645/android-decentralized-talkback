package com.talkback.core.signaling.peer

import com.talkback.core.signaling.link.LinkQualificationState
import com.talkback.core.signaling.link.TransportCapabilitySnapshot
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PeerEdgeSignalingReadinessTest {

    private var nowMs = 1_000_000L
    private var localState = LinkQualificationState.BIDIRECTIONAL_READY
    private var currentGeneration = 6L

    private fun readiness(
        staleMs: Long = 15_000L
    ): PeerEdgeSignalingReadiness = PeerEdgeSignalingReadiness(
        moduleStaleMs = staleMs,
        clock = { nowMs },
        localSnapshot = {
            TransportCapabilitySnapshot(
                linkQualification = localState,
                rebindGeneration = currentGeneration
            )
        }
    )

    private fun observed(
        peer: String = "M03",
        generation: Long = 6L,
        atMs: Long = nowMs,
        socketId: Long = 10L
    ) = PeerInboundObserved(
        remoteModuleId = peer,
        socketId = socketId,
        receiveGeneration = generation,
        observedAtMs = atMs
    )

    @Test
    fun invSig003_localBidirectional_doesNotImplyPeerReady() {
        val r = readiness()
        assertFalse(r.isReady("M03"))
    }

    @Test
    fun invSig002_009_authenticatedInboundOnCurrentGen_makesReady() {
        val r = readiness()
        r.onPeerInboundObserved(observed())
        assertTrue(r.isReady("M03"))
    }

    @Test
    fun invSig004_generationInvalidate_clearsAllPeerReady() {
        val r = readiness()
        r.onPeerInboundObserved(observed("M03"))
        r.onPeerInboundObserved(observed("M01"))
        assertTrue(r.isReady("M03"))
        assertTrue(r.isReady("M01"))

        r.invalidateGeneration(priorGeneration = 6L)
        currentGeneration = 7L

        assertFalse(r.isReady("M03"))
        assertFalse(r.isReady("M01"))
    }

    @Test
    fun invSig004_staleGenerationFact_doesNotMakeReady() {
        val r = readiness()
        r.onPeerInboundObserved(observed(generation = 5L))
        assertFalse(r.isReady("M03"))
    }

    @Test
    fun invSig009_010_freshnessExpire_dropsReady_withoutStickyBit() {
        val r = readiness(staleMs = 15_000L)
        r.onPeerInboundObserved(observed(atMs = nowMs))
        assertTrue(r.isReady("M03"))

        nowMs += 15_001L
        assertFalse(r.isReady("M03"))
    }

    @Test
    fun invSig010_freshInbound_refreshesFreshness() {
        val r = readiness(staleMs = 15_000L)
        r.onPeerInboundObserved(observed(atMs = nowMs))
        nowMs += 10_000L
        r.onPeerInboundObserved(observed(atMs = nowMs))
        nowMs += 10_000L
        assertTrue(r.isReady("M03"))
    }

    @Test
    fun invSig003_localDropBelowBidir_dropsReadyEvenWithFreshInbound() {
        val r = readiness()
        r.onPeerInboundObserved(observed())
        assertTrue(r.isReady("M03"))
        localState = LinkQualificationState.RECEIVE_READY
        assertFalse(r.isReady("M03"))
    }

    @Test
    fun invSig012_014_readiness_emitsLost_withoutRepair() {
        val losses = mutableListOf<PeerEdgeSignalingLost>()
        val r = readiness(staleMs = 1_000L)
        r.onPeerEdgeSignalingLost = { losses.add(it) }
        r.onPeerInboundObserved(observed(atMs = nowMs))
        nowMs += 1_001L
        r.evaluateFreshness()
        assertFalse(r.isReady("M03"))
        assertTrue(losses.any {
            it.remoteModuleId == "M03" &&
                it.reason == PeerEdgeSignalingNotReadyReason.FRESHNESS_EXPIRED
        })
    }
}