package com.talkback.core.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConferenceParticipantReadyContractTest {

    private val members4 = listOf("M01", "M02", "M03", "M04")

    private fun hostNotAnchor() = ConferenceTopologyContract.composeAnchorAdmission(
        AnchorAdmissionInput(
            conferenceId = "c1",
            hostModuleId = "M01",
            anchorId = "M03",
            members = members4
        )
    )

    @Test
    fun hostSession_requiresNoRemotes() {
        val req = ConferenceParticipantReadyContract.remotesRequiredForReady(
            localModuleId = "M01",
            hostModuleId = "M01",
            topology = hostNotAnchor()
        )
        assertEquals(ConferenceReadyGate.HOST_SESSION, req.gate)
        assertTrue(req.remotes.isEmpty())
        assertEquals("NONE", req.blockReason(allConnected = false))
    }

    @Test
    fun spoke_hostNotAnchor_requiresAnchorNotHost() {
        val req = ConferenceParticipantReadyContract.remotesRequiredForReady(
            localModuleId = "M02",
            hostModuleId = "M01",
            topology = hostNotAnchor()
        )
        assertEquals(ConferenceReadyGate.ADMITTED_EDGE, req.gate)
        assertEquals(setOf("M03"), req.remotes)
        assertEquals("WAIT_ADMITTED_EDGE", req.blockReason(allConnected = false))
        assertEquals("NONE", req.blockReason(allConnected = true))
    }

    @Test
    fun mediaAnchor_notHost_requiresStarSpokes() {
        val req = ConferenceParticipantReadyContract.remotesRequiredForReady(
            localModuleId = "M03",
            hostModuleId = "M01",
            topology = hostNotAnchor()
        )
        assertEquals(ConferenceReadyGate.ADMITTED_EDGE, req.gate)
        assertEquals(setOf("M01", "M02", "M04"), req.remotes)
    }

    @Test
    fun meshEmptyEdges_fallsBackToHostIce() {
        val mesh = ConferenceTopologySnapshot(
            conferenceId = "c1",
            rosterEpoch = 1L,
            anchorEpoch = 0L,
            anchorId = null,
            meshGeneration = 2L,
            topologyMode = ConferenceTopologyMode.MESH,
            hostModuleId = "M01",
            members = listOf("M01", "M03", "M04"),
            actualMediaEdges = emptySet()
        )
        val req = ConferenceParticipantReadyContract.remotesRequiredForReady(
            localModuleId = "M02",
            hostModuleId = "M01",
            topology = mesh
        )
        assertEquals(ConferenceReadyGate.HOST_ICE, req.gate)
        assertEquals(setOf("M01"), req.remotes)
        assertEquals("WAIT_HOST_ICE", req.blockReason(allConnected = false))
    }

    @Test
    fun noSnapshot_fallsBackToHostIce() {
        val req = ConferenceParticipantReadyContract.remotesRequiredForReady(
            localModuleId = "M02",
            hostModuleId = "M01",
            topology = null
        )
        assertEquals(ConferenceReadyGate.HOST_ICE, req.gate)
        assertEquals(setOf("M01"), req.remotes)
    }
}
