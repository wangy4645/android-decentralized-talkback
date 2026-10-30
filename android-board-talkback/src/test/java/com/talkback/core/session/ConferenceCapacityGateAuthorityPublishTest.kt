package com.talkback.core.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Capacity gate on Authority publish path (K1/K6). Rejected does not rewrite topology.
 */
class ConferenceCapacityGateAuthorityPublishTest {

    private fun members(n: Int): List<String> = (1..n).map { i -> "M%02d".format(i) }

    private fun mesh(n: Int, gen: Long = 1L) =
        ConferenceTopologyModeTransitionContract.composeMeshSnapshot(
            conferenceId = "c1",
            hostModuleId = "M01",
            members = members(n),
            rosterEpoch = 1L,
            meshGeneration = gen
        )

    private fun anchorInput(n: Int, gen: Long = 5L) = AnchorAdmissionInput(
        conferenceId = "c1",
        hostModuleId = "M01",
        anchorId = "M01",
        members = members(n),
        meshGeneration = gen,
        anchorEpoch = 10L
    )

    @Test
    fun k1_publish_n4Mesh_rejectedNotInstalled() {
        val authority = ConferenceTopologyAuthority()
        val result = authority.publishModeTransition(mesh(4), TopologyModeTransition.UNCHANGED_MESH)
        assertTrue(result is ConferenceTopologyAuthority.PublishResult.Rejected)
        assertNull(authority.currentSnapshot("c1"))
        assertNull(authority.previousSnapshot("c1"))
        assertNull(authority.currentAuthorizedTransition("c1"))
    }

    @Test
    fun k6_publish_n8Mesh_rejected_keepsAnchorTransaction() {
        val authority = ConferenceTopologyAuthority()
        assertTrue(
            authority.publishTopologyProjection(anchorInput(8)) is
                ConferenceTopologyAuthority.PublishResult.Published
        )
        val current = authority.currentSnapshot("c1")!!
        assertEquals(7, current.actualMediaEdges.size)
        val failover = authority.publishAnchorFailover("c1", "M02") as
            ConferenceTopologyAuthority.PublishResult.Published
        val afterFailover = authority.currentSnapshot("c1")
        val previous = authority.previousSnapshot("c1")
        val transition = authority.currentAuthorizedTransition("c1")
        assertEquals(failover.snapshot, afterFailover)
        assertTrue(transition != null)

        val rejected = authority.publishModeTransition(
            mesh(8, gen = 99L),
            TopologyModeTransition.ANCHOR_TO_MESH
        )
        assertTrue(rejected is ConferenceTopologyAuthority.PublishResult.Rejected)
        assertEquals(afterFailover, authority.currentSnapshot("c1"))
        assertEquals(previous, authority.previousSnapshot("c1"))
        assertEquals(transition, authority.currentAuthorizedTransition("c1"))
        assertEquals(7, authority.currentSnapshot("c1")!!.actualMediaEdges.size)
    }

    @Test
    fun publish_n8Anchor_starInstalled() {
        val authority = ConferenceTopologyAuthority()
        val result = authority.publishTopologyProjection(anchorInput(8))
        assertTrue(result is ConferenceTopologyAuthority.PublishResult.Published)
        assertEquals(7, authority.currentSnapshot("c1")!!.actualMediaEdges.size)
        assertEquals(
            ConferenceCapacityEvaluation.AnchorAdmitted(7),
            ConferenceCapacityGateContract.evaluate(authority.currentSnapshot("c1")!!)
        )
    }

    @Test
    fun publish_n3Mesh_stillAdmitted() {
        val authority = ConferenceTopologyAuthority()
        val result = authority.publishModeTransition(mesh(3), TopologyModeTransition.UNCHANGED_MESH)
        assertTrue(result is ConferenceTopologyAuthority.PublishResult.Published)
        assertTrue(authority.currentSnapshot("c1")!!.actualMediaEdges.isEmpty())
    }
}
