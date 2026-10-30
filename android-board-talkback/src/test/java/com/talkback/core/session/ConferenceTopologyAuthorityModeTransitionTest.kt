package com.talkback.core.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConferenceTopologyAuthorityModeTransitionTest {

    private val roster3 = listOf("M01", "M02", "M03")

    @Test
    fun publishModeTransition_meshSnapshot_emptyEdges() {
        val authority = ConferenceTopologyAuthority()
        val mesh = ConferenceTopologyModeTransitionContract.composeMeshSnapshot(
            conferenceId = "c1",
            hostModuleId = "M01",
            members = roster3,
            rosterEpoch = 1L,
            meshGeneration = 0L
        )
        val result = authority.publishModeTransition(mesh, TopologyModeTransition.UNCHANGED_MESH)
        assertTrue(result is ConferenceTopologyAuthority.PublishResult.Published)
        val published = result as ConferenceTopologyAuthority.PublishResult.Published
        assertEquals(TopologyModeTransition.UNCHANGED_MESH, published.modeTransition)
        assertTrue(published.snapshot.actualMediaEdges.isEmpty())
        assertNull(published.snapshot.anchorId)
    }

    @Test
    fun publishModeTransition_anchorToMesh_clearsPriorAnchorEdges() {
        val authority = ConferenceTopologyAuthority()
        val anchor = ConferenceMediaEdgeAdmissionContract.admitFromTopologyProjection(
            AnchorAdmissionInput(
                conferenceId = "c1",
                hostModuleId = "M01",
                anchorId = "M01",
                members = roster3 + "M04",
                meshGeneration = 5L
            )
        ) as MediaEdgeAdmissionResult.Admitted
        authority.publishTopologyProjection(
            AnchorAdmissionInput("c1", "M01", "M01", roster3 + "M04", meshGeneration = 5L)
        )
        val mesh = ConferenceTopologyModeTransitionContract.composeMeshSnapshot(
            "c1", "M01", roster3, 1L, 6L
        )
        val result = authority.publishModeTransition(mesh, TopologyModeTransition.ANCHOR_TO_MESH)
        assertTrue(result is ConferenceTopologyAuthority.PublishResult.Published)
        val current = authority.currentSnapshot("c1")!!
        assertTrue(current.actualMediaEdges.isEmpty())
        assertEquals(ConferenceTopologyMode.MESH, current.topologyMode)
    }
}
