package com.talkback.core.webrtc.conferenceaudio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADR-0056 Phase 1b — topology → media edge → bus chain (harness only). */
class TopologyMediaBridgeHarnessTest {

    @Test
    fun anchorPublish_bind_deriveBus_renderWithoutCoordinator() {
        val harness = TopologyMediaBridgeHarness()
        val bound = harness.publishAnchorStar()
        assertTrue(bound is MediaEdgeAuthorityResult.Bound)
        assertEquals(3, (bound as MediaEdgeAuthorityResult.Bound).edgeSet.edges.size)

        val bus = harness.createBusFromBoundSnapshot().getOrThrow()
        val source = FakeRemotePcmSource("M02", level = 4_000)
        source.admit()
        assertTrue(bus.pushAndRender(MediaEdgeId("M02"), source.produceFrame()).isSuccess)
        assertEquals(1, bus.targetPort(MediaEdgeId("M03"))!!.writtenFrames.size)
    }

    @Test
    fun topologyAuthority_onlyPublishOwner_snapshotImmutableDownstream() {
        val harness = TopologyMediaBridgeHarness()
        harness.publishAnchorStar()
        val snap1 = harness.mediaEdgeAuthority.snapshot!!
        val edgeCount1 = harness.mediaEdgeAuthority.actualMediaEdgeSet()!!.edges.size

        val snap2 = harness.topologyAuthority.currentSnapshot("conf-bridge")!!
        assertEquals(snap1.conferenceId, snap2.conferenceId)
        assertEquals(edgeCount1, snap2.actualMediaEdges.size)
    }
}
