package com.talkback.core.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConferenceTopologyAuthorityTest {

    private val roster4 = listOf("M01", "M02", "M03", "M04")

    private fun input(
        conferenceId: String = "c1",
        host: String = "M01",
        anchor: String = "M01",
        meshGeneration: Long = 1L,
        anchorEpoch: Long = 100L,
        rosterEpoch: Long = 1L,
        members: List<String> = roster4
    ) = AnchorAdmissionInput(
        conferenceId = conferenceId,
        hostModuleId = host,
        anchorId = anchor,
        members = members,
        rosterEpoch = rosterEpoch,
        anchorEpoch = anchorEpoch,
        meshGeneration = meshGeneration
    )

    @Test
    fun publish_validSnapshot_installsImmutableCurrent() {
        val authority = ConferenceTopologyAuthority()
        val result = authority.publishAnchorAdmission(input())
        assertTrue(result is ConferenceTopologyAuthority.PublishResult.Published)
        val current = authority.currentSnapshot("c1")
        assertNotNull(current)
        assertEquals(ConferenceTopologyMode.ANCHOR, current!!.topologyMode)
        assertEquals(3, current.actualMediaEdges.size)
    }

    @Test
    fun publish_f7HalfTopology_rejectedAndNotInstalled() {
        val authority = ConferenceTopologyAuthority()
        val valid = ConferenceTopologyContract.composeAnchorAdmission(input(host = "M02", anchor = "M01"))
        val invalid = valid.copy(anchorId = "M02", actualMediaEdges = valid.actualMediaEdges)
        val result = authority.publishValidatedForTest(invalid)
        assertTrue(result is ConferenceTopologyAuthority.PublishResult.Rejected)
        assertEquals("edge anchor mismatch", (result as ConferenceTopologyAuthority.PublishResult.Rejected).reason)
        assertNull(authority.currentSnapshot("c1"))
    }

    @Test
    fun publish_staleCandidate_rejected() {
        val authority = ConferenceTopologyAuthority()
        authority.publishAnchorAdmission(input(anchorEpoch = 20L, meshGeneration = 5L))
        val stale = authority.publishAnchorAdmission(
            input(anchorEpoch = 19L, meshGeneration = 4L)
        )
        assertTrue(stale is ConferenceTopologyAuthority.PublishResult.Rejected)
        assertEquals(20L, authority.currentSnapshot("c1")!!.anchorEpoch)
    }

    @Test
    fun publish_edgeChange_bumpsMeshGenerationViaRosterAdmission() {
        val authority = ConferenceTopologyAuthority()
        authority.publishTopologyProjection(input(meshGeneration = 5L))
        val expanded = authority.publishRosterEdgeChange("c1", roster4 + "M05")
        assertTrue(expanded is ConferenceTopologyAuthority.PublishResult.Published)
        val published = expanded as ConferenceTopologyAuthority.PublishResult.Published
        assertEquals(MediaEdgeAdmissionCause.ROSTER_EDGE_CHANGE, published.mediaEdgeCause)
        assertEquals(6L, authority.currentSnapshot("c1")!!.meshGeneration)
    }

    @Test
    fun publish_anchorFailover_replacesEdgeSet() {
        val authority = ConferenceTopologyAuthority()
        authority.publishTopologyProjection(input(meshGeneration = 31L, anchorEpoch = 10L))
        val result = authority.publishAnchorFailover("c1", "M02")
        assertTrue(result is ConferenceTopologyAuthority.PublishResult.Published)
        val published = result as ConferenceTopologyAuthority.PublishResult.Published
        assertEquals(MediaEdgeAdmissionCause.ANCHOR_FAILOVER, published.mediaEdgeCause)
        assertEquals("M02", authority.currentSnapshot("c1")!!.anchorId)
        assertEquals(32L, authority.currentSnapshot("c1")!!.meshGeneration)
    }

    @Test
    fun publish_unchangedSnapshot_returnsUnchanged() {
        val authority = ConferenceTopologyAuthority()
        assertTrue(authority.publishAnchorAdmission(input()) is ConferenceTopologyAuthority.PublishResult.Published)
        assertTrue(authority.publishAnchorAdmission(input()) is ConferenceTopologyAuthority.PublishResult.Unchanged)
    }

    @Test
    fun clear_removesSnapshot() {
        val authority = ConferenceTopologyAuthority()
        authority.publishAnchorAdmission(input())
        authority.clear("c1")
        assertNull(authority.currentSnapshot("c1"))
    }
}
