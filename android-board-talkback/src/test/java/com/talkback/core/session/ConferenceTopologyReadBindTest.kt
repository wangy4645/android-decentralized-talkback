package com.talkback.core.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConferenceTopologyReadBindTest {

    @Test
    fun presenceReadInput_mapsSnapshotFieldsWithoutMutation() {
        val snapshot = ConferenceTopologyContract.composeAnchorAdmission(
            AnchorAdmissionInput(
                conferenceId = "c1",
                hostModuleId = "M02",
                anchorId = "M01",
                members = listOf("M01", "M02", "M03", "M04"),
                rosterEpoch = 7L,
                anchorEpoch = 100L,
                meshGeneration = 3L
            )
        )
        val read = ConferenceTopologyReadBind.presenceReadInput(snapshot)
        assertEquals("c1", read.conferenceId)
        assertEquals(7L, read.rosterEpoch)
        assertEquals(100L, read.anchorEpoch)
        assertEquals(3L, read.meshGeneration)
        assertEquals("M01", read.producerModuleId)
        assertEquals("M01", read.anchorId)
        assertEquals("M02", read.hostModuleId)
        assertEquals(listOf("M01", "M02", "M03", "M04"), read.canonicalRoster)
        assertEquals(snapshot.members, read.canonicalRoster)
    }

    @Test
    fun presenceReadInput_hostNotAnchor_preservesDistinctHost() {
        val snapshot = ConferenceTopologyContract.composeAnchorAdmission(
            AnchorAdmissionInput(
                conferenceId = "c2",
                hostModuleId = "M04",
                anchorId = "M01",
                members = listOf("M01", "M02", "M03", "M04")
            )
        )
        val read = ConferenceTopologyReadBind.presenceReadInput(snapshot)
        assertEquals("M04", read.hostModuleId)
        assertEquals("M01", read.anchorId)
        assertEquals("M01", read.producerModuleId)
    }

    @Test
    fun bindDownstreamView_rejectsCrossGenerationMix() {
        val invalid = ConferenceTopologyContract.composeAnchorAdmission(
            AnchorAdmissionInput(
                conferenceId = "c1",
                hostModuleId = "M01",
                anchorId = "M02",
                members = listOf("M01", "M02", "M03", "M04"),
                anchorEpoch = 11L,
                meshGeneration = 31L
            )
        ).copy(actualMediaEdges = ConferenceTopologyContract.anchorStarEdges("M01", listOf("M01", "M02", "M03", "M04")))
        val bind = ConferenceTopologyReadBind.bindDownstreamView(invalid)
        assertTrue(bind is ConferenceTopologyConsistencyContract.BindResult.Rejected)
        assertNull(ConferenceTopologyReadBind.tryPresenceReadInput(invalid))
    }

    @Test
    fun bindDownstreamView_meshSnapshot_notPresenceReadInput() {
        val mesh = ConferenceTopologyModeTransitionContract.composeMeshSnapshot(
            conferenceId = "c1",
            hostModuleId = "M01",
            members = listOf("M01", "M02", "M03"),
            rosterEpoch = 1L,
            meshGeneration = 2L
        )
        val bind = ConferenceTopologyReadBind.bindDownstreamView(mesh)
        assertTrue(bind is ConferenceTopologyConsistencyContract.BindResult.Ok)
        assertEquals(
            ConferencePresenceReadPath.MESH_SESSION_OBSERVATION,
            (bind as ConferenceTopologyConsistencyContract.BindResult.Ok).view.presenceReadPath
        )
        assertNull(ConferenceTopologyReadBind.tryPresenceReadInput(mesh))
    }
}
