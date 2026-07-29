package com.talkback.core.signaling.peer

import com.talkback.core.signaling.link.LinkQualificationState
import com.talkback.core.signaling.link.TransportCapabilitySnapshot
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PeerEdgeSignalingTraceTest {

    private val lines = mutableListOf<String>()
    private var nowMs = 1_000_000L
    private var generation = 6L

    @Before
    fun setUp() {
        lines.clear()
        PeerEdgeSignalingTrace.resetForTest { lines.add(it) }
    }

    @After
    fun tearDown() {
        PeerEdgeSignalingTrace.resetForTest(null)
    }

    private fun readiness(): PeerEdgeSignalingReadiness =
        PeerEdgeSignalingReadiness(
            moduleStaleMs = 1_000L,
            clock = { nowMs },
            localSnapshot = {
                TransportCapabilitySnapshot(
                    linkQualification = LinkQualificationState.BIDIRECTIONAL_READY,
                    rebindGeneration = generation
                )
            }
        )

    @Test
    fun inboundObserved_emitsPeerEdgeReady() {
        val r = readiness()
        r.onPeerInboundObserved(
            PeerInboundObserved("M03", 1L, 6L, observedAtMs = nowMs)
        )
        assertTrue(lines.any {
            it.startsWith("PEER_EDGE_READY") &&
                it.contains("remote=M03") &&
                it.contains("previous=false") &&
                it.contains("current=true") &&
                it.contains("reason=INBOUND_OBSERVED")
        })
    }

    @Test
    fun staleTimeout_emitsPeerEdgeNotReady() {
        val r = readiness()
        r.onPeerInboundObserved(
            PeerInboundObserved("M03", 1L, 6L, observedAtMs = nowMs)
        )
        lines.clear()
        nowMs += 1_001L
        r.evaluateFreshness()
        assertTrue(lines.any {
            it.startsWith("PEER_EDGE_NOT_READY") &&
                it.contains("remote=M03") &&
                it.contains("reason=STALE_TIMEOUT") &&
                it.contains("current=false")
        })
        assertFalse(r.isReady("M03"))
    }

    @Test
    fun invalidateGeneration_emitsPeerEdgeInvalidated() {
        val r = readiness()
        r.onPeerInboundObserved(
            PeerInboundObserved("M03", 1L, 6L, observedAtMs = nowMs)
        )
        lines.clear()
        generation = 7L
        r.invalidateGeneration(priorGeneration = 6L)
        assertTrue(lines.any {
            it.startsWith("PEER_EDGE_INVALIDATED") &&
                it.contains("remote=M03") &&
                it.contains("oldGeneration=6") &&
                it.contains("reason=TRANSPORT_EPOCH_ADVANCED")
        })
        assertTrue(lines.any {
            it.startsWith("PEER_EDGE_NOT_READY") &&
                it.contains("reason=GENERATION_INVALIDATED")
        })
    }
}