package com.talkback.core.signaling.peer

import com.talkback.core.signaling.link.LinkQualificationState
import com.talkback.core.signaling.link.TransportCapabilitySnapshot
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PeerEdgeSignalingBidirectionalPromotionTest {
    @Before
    fun isolateTraceFromAndroidLog() {
        PeerEdgeSignalingTrace.resetForTest { }
    }

    @After
    fun restoreTraceSink() {
        PeerEdgeSignalingTrace.resetForTest()
    }

    private var nowMs = 1_000_000L
    private var localState = LinkQualificationState.RECEIVE_READY
    private var currentGeneration = 6L

    private fun readiness(): PeerEdgeSignalingReadiness =
        PeerEdgeSignalingReadiness(
            moduleStaleMs = 15_000L,
            clock = { nowMs },
            localSnapshot = {
                TransportCapabilitySnapshot(
                    linkQualification = localState,
                    rebindGeneration = currentGeneration,
                )
            },
        )

    private fun observed(peer: String = "M02") =
        PeerInboundObserved(
            remoteModuleId = peer,
            socketId = 10L,
            receiveGeneration = currentGeneration,
            observedAtMs = nowMs,
        )

    @Test
    fun inboundWhileNotBidirectional_thenPromotion_emitsReadyWithoutNewInbound() {
        val ready = mutableListOf<String>()
        val r = readiness()
        r.onPeerEdgeSignalingReady = { ready += it }
        r.onPeerInboundObserved(observed())
        assertFalse(r.isReady("M02"))
        assertTrue(ready.isEmpty())

        localState = LinkQualificationState.BIDIRECTIONAL_READY
        r.onLocalBecameBidirectionalReady()

        assertTrue(r.isReady("M02"))
        assertEquals(listOf("M02"), ready)
    }

    @Test
    fun promotionWithoutInbound_doesNotReady() {
        val ready = mutableListOf<String>()
        val r = readiness()
        r.onPeerEdgeSignalingReady = { ready += it }
        localState = LinkQualificationState.BIDIRECTIONAL_READY
        r.onLocalBecameBidirectionalReady()
        assertFalse(r.isReady("M02"))
        assertTrue(ready.isEmpty())
    }

    @Test
    fun promotionTwice_doesNotRetriggerReady() {
        val ready = mutableListOf<String>()
        val r = readiness()
        r.onPeerEdgeSignalingReady = { ready += it }
        r.onPeerInboundObserved(observed())
        localState = LinkQualificationState.BIDIRECTIONAL_READY
        r.onLocalBecameBidirectionalReady()
        r.onLocalBecameBidirectionalReady()
        assertEquals(1, ready.size)
    }
}
