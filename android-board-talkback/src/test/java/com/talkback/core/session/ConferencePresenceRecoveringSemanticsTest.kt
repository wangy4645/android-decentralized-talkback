package com.talkback.core.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ConferencePresenceRecoveringSemanticsTest {

    private val roster4 = listOf("M01", "M02", "M03", "M04")
    private val now = 50_000L

    private fun anchorTopology() = ConferenceTopologyContract.composeAnchorAdmission(
        AnchorAdmissionInput(
            conferenceId = "c1",
            hostModuleId = "M01",
            anchorId = "M01",
            members = roster4,
            meshGeneration = 1L,
            anchorEpoch = 10L
        )
    )

    @Test
    fun compose_doesNotUnionReconnectingModuleIdsFromUnusableEdge() {
        val topology = anchorTopology()
        val observed = ConferencePresenceFactsAdapter.projectFromObservation(
            conferenceId = topology.conferenceId,
            producerModuleId = "M01",
            rosterEpoch = topology.rosterEpoch,
            anchorEpoch = topology.anchorEpoch,
            meshGeneration = topology.meshGeneration,
            producedAtMs = now,
            localModuleId = "M01",
            iceConnectedRemoteIds = setOf("M02"),
            topology = topology,
            perEdgeFacts = listOf(
                PerEdgeMediaUsabilityFact(
                    conferenceId = "c1",
                    anchorEpoch = 10L,
                    meshGeneration = 1L,
                    producedAtMs = now,
                    producerModuleId = "M01",
                    edge = MediaEdge("M01", "M04"),
                    usable = false
                )
            ),
            nowMs = now
        )
        assertFalse(observed.reconnectingModuleIds.contains("M04"))
        val projection = ConferencePresenceProjector.compose(
            conferenceId = topology.conferenceId,
            canonicalRoster = topology.members,
            currentAnchorEpoch = topology.anchorEpoch,
            snapshot = observed.snapshot,
            nowMs = now,
            recoveringModuleIds = emptySet()
        )
        assertFalse("M04" in projection.recoveringPeers)
        assertEquals(CppMediaRelation.NONE, projection.participants.single { it.moduleId == "M04" }.mediaRelation)
    }

    @Test
    fun compose_recoversOnlyFromEdgeRecoveryFacts() {
        val snap = ConferencePresenceFactsAdapter.snapshotFromLocalObservation(
            conferenceId = "c1",
            producerModuleId = "M01",
            rosterEpoch = 1,
            anchorEpoch = 10,
            meshGeneration = 1,
            producedAtMs = now,
            localModuleId = "M01",
            iceConnectedRemoteIds = setOf("M02", "M03", "M04")
        )
        val projection = ConferencePresenceProjector.compose(
            conferenceId = "c1",
            canonicalRoster = roster4,
            currentAnchorEpoch = 10,
            snapshot = snap,
            nowMs = now,
            recoveringModuleIds = setOf("M03")
        )
        assertEquals(setOf("M03"), projection.recoveringPeers)
    }
}
