package com.talkback.core.webrtc.conferenceaudio

import com.talkback.core.session.ConferenceTopologyContract
import com.talkback.core.session.ConferenceTopologyMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADR-0056 Phase 1b — MediaEdgeAuthority facade contract. */
class MediaEdgeAuthorityContractTest {

    @Test
    fun bindSnapshot_projectsActualMediaEdgeSetWithMediaEdgeId() {
        val snapshot = ConferenceTopologyContract.composeAnchorAdmission(
            com.talkback.core.session.AnchorAdmissionInput(
                conferenceId = "c1",
                hostModuleId = "M01",
                anchorId = "M01",
                members = listOf("M01", "M02", "M03")
            )
        )
        val authority = MediaEdgeAuthority()
        val bound = authority.bind(snapshot)
        assertTrue(bound is MediaEdgeAuthorityResult.Bound)
        val view = authority.actualMediaEdgeSet()!!
        assertEquals(ConferenceTopologyMode.ANCHOR, view.topologyMode)
        assertEquals(2, view.edges.size)
        assertTrue(view.edges.all { it.edgeId.value.contains("M01->") })
        assertTrue(view.edges.all { it.state == ActualMediaEdgeState.ADMITTED })
    }

    @Test
    fun mediaEdgeId_changesWithMeshGeneration_notPeerIdAlone() {
        val base = ConferenceTopologyContract.composeAnchorAdmission(
            com.talkback.core.session.AnchorAdmissionInput(
                conferenceId = "c1",
                hostModuleId = "M01",
                anchorId = "M01",
                members = listOf("M01", "M02"),
                meshGeneration = 1L
            )
        )
        val bumped = base.copy(meshGeneration = 2L)
        val edge = base.actualMediaEdges.first()
        val id1 = MediaEdgeIdFactory.fromTopologyEdge(base, edge)
        val id2 = MediaEdgeIdFactory.fromTopologyEdge(bumped, edge)
        assertNotEquals(id1, id2)
    }

    @Test
    fun deriveBusSpec_anchorStar_doesNotCreateSession() {
        val authority = MediaEdgeAuthority()
        authority.bind(
            ConferenceTopologyContract.composeAnchorAdmission(
                com.talkback.core.session.AnchorAdmissionInput(
                    conferenceId = "c1",
                    hostModuleId = "M01",
                    anchorId = "M01",
                    members = listOf("M01", "M02", "M03")
                )
            )
        )
        val spec = authority.deriveAnchorStarBusSpec(includeLocalMic = false).getOrThrow()
        assertEquals(listOf("M02", "M03"), spec.targetEdgeIds.map { it.value }.sorted())
        assertEquals(listOf("M02", "M03"), spec.sourceEdgeIds.map { it.value }.sorted())
    }

    @Test
    fun meshSnapshotWithoutAdmittedEdges_rejectsBusSpec() {
        val authority = MediaEdgeAuthority()
        val mesh = com.talkback.core.session.ConferenceTopologyModeTransitionContract.composeMeshSnapshot(
            conferenceId = "c1",
            hostModuleId = "M01",
            members = listOf("M01", "M02"),
            rosterEpoch = 1L,
            meshGeneration = 1L
        )
        authority.bind(mesh)
        assertTrue(authority.actualMediaEdgeSet()!!.edges.isEmpty())
        assertTrue(authority.deriveAnchorStarBusSpec().isFailure)
    }
}
