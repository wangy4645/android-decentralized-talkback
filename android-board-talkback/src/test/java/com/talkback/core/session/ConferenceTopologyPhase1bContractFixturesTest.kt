package com.talkback.core.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 1b contract fixtures F1–F8. Pure contract layer only — no Coordinator / Recovery.
 * Spec: docs/analysis/0056-phase-1b-contract-fixtures.md
 */
class ConferenceTopologyPhase1bContractFixturesTest {

    private val roster4 = listOf("M01", "M02", "M03", "M04")

    private fun admission(
        host: String,
        anchor: String,
        meshGeneration: Long = 1L,
        anchorEpoch: Long = 100L
    ) = ConferenceTopologyContract.composeAnchorAdmission(
        AnchorAdmissionInput(
            conferenceId = "c1",
            hostModuleId = host,
            anchorId = anchor,
            members = roster4,
            meshGeneration = meshGeneration,
            anchorEpoch = anchorEpoch
        )
    )

    private fun starM01() = setOf(
        MediaEdge("M01", "M02"),
        MediaEdge("M01", "M03"),
        MediaEdge("M01", "M04")
    )

    @Test
    fun f1_fourParticipantColdStartAnchor_producesCanonicalStarSnapshot() {
        val snapshot = admission(host = "M01", anchor = "M01")
        assertEquals(ConferenceTopologyMode.ANCHOR, snapshot.topologyMode)
        assertEquals("M01", snapshot.anchorId)
        assertEquals(4, snapshot.members.size)
        assertEquals(3, snapshot.actualMediaEdges.size)
        assertEquals(starM01(), snapshot.actualMediaEdges)
        assertTrue(ConferenceTopologyContract.validateSnapshot(snapshot) is SnapshotValidity.Valid)
    }

    @Test
    fun f2_hostNotAnchor_producesSameCanonicalTopologyNotSpecialBranch() {
        val snapshot = admission(host = "M02", anchor = "M01")
        assertEquals("M02", snapshot.hostModuleId)
        assertEquals("M01", snapshot.anchorId)
        assertNotEquals(snapshot.hostModuleId, snapshot.anchorId)
        assertEquals(roster4, snapshot.members)
        assertEquals(starM01(), snapshot.actualMediaEdges)
        assertTrue(ConferenceTopologyContract.validateSnapshot(snapshot) is SnapshotValidity.Valid)
    }

    @Test
    fun f3_iceConnectedPeerPair_notAdmitted_reverseAdmittedSurvivesReconnecting() {
        val snapshot = admission(host = "M01", anchor = "M01")
        val peerObs = IceEdgeObservation(
            localModuleId = "M02",
            remoteModuleId = "M03",
            iceConnected = true
        )
        assertTrue(peerObs.iceConnected)
        assertEquals(null, ConferenceTopologyContract.mediaEdgeForObservation(peerObs, snapshot))
        assertFalse(ConferenceTopologyContract.isAdmittedByTopology(peerObs, snapshot))

        val anchorEdge = MediaEdge("M01", "M02")
        val reconnectObs = IceEdgeObservation(
            localModuleId = "M01",
            remoteModuleId = "M02",
            iceConnected = false,
            iceReconnecting = true
        )
        assertTrue(ConferenceTopologyContract.edgeStillAdmitted(anchorEdge, snapshot))
        assertTrue(ConferenceTopologyContract.isAdmittedByTopology(reconnectObs, snapshot))
    }

    @Test
    fun f4_edgeSetChange_bumpsMeshGeneration_onlyOnChange() {
        val base = admission(host = "M01", anchor = "M01", meshGeneration = 5L)
        val withM05 = base.copy(
            members = roster4 + "M05",
            actualMediaEdges = ConferenceTopologyContract.anchorStarEdges("M01", roster4 + "M05")
        )
        assertEquals(
            6L,
            ConferenceTopologyContract.requiredMeshGenerationAfterEdgeChange(
                base.actualMediaEdges,
                withM05.actualMediaEdges,
                base.meshGeneration
            )
        )
        assertEquals(
            5L,
            ConferenceTopologyContract.requiredMeshGenerationAfterEdgeChange(
                base.actualMediaEdges,
                base.actualMediaEdges,
                base.meshGeneration
            )
        )
        assertTrue(ConferenceTopologyContract.validateSnapshot(withM05) is SnapshotValidity.Valid)
    }

    @Test
    fun f5_anchorFailover_bumpsEpochAndGeneration_invalidatesOld() {
        val old = admission(host = "M01", anchor = "M01", meshGeneration = 31L, anchorEpoch = 10L)
        val newSnap = ConferenceTopologyContract.failoverSnapshot(old, newAnchorId = "M02")
        assertEquals("M02", newSnap.anchorId)
        assertEquals(11L, newSnap.anchorEpoch)
        assertEquals(32L, newSnap.meshGeneration)
        assertEquals(
            ConferenceTopologyContract.anchorStarEdges("M02", roster4),
            newSnap.actualMediaEdges
        )
        assertTrue(ConferenceTopologyContract.validateSnapshot(newSnap) is SnapshotValidity.Valid)
        assertTrue(ConferenceTopologyContract.isStaleSnapshot(old, newSnap))
    }

    @Test
    fun f6_transientIce_doesNotRewriteTopologyOrGeneration() {
        val snapshot = admission(host = "M01", anchor = "M01", meshGeneration = 7L)
        val edge = MediaEdge("M01", "M02")
        assertEquals(
            7L,
            ConferenceTopologyContract.requiredMeshGenerationAfterEdgeChange(
                snapshot.actualMediaEdges,
                snapshot.actualMediaEdges,
                snapshot.meshGeneration
            )
        )
        assertTrue(
            ConferenceTopologyContract.edgeStillAdmitted(
                edge,
                snapshot.copy(
                    hostModuleId = snapshot.hostModuleId
                )
            )
        )
    }

    @Test
    fun f7_atomicSnapshot_validCompose_invalidHalfTopologyRejected() {
        val valid = admission(host = "M02", anchor = "M01", meshGeneration = 32L, anchorEpoch = 11L)
        assertTrue(ConferenceTopologyContract.validateSnapshot(valid) is SnapshotValidity.Valid)

        val invalid = valid.copy(
            anchorId = "M02",
            actualMediaEdges = starM01()
        )
        val result = ConferenceTopologyContract.validateSnapshot(invalid)
        assertTrue(result is SnapshotValidity.Invalid)
        assertEquals("edge anchor mismatch", (result as SnapshotValidity.Invalid).reason)
    }

    @Test
    fun f8_cppFollowsTopologySnapshot_doesNotMutateTopology() {
        val topology = admission(host = "M02", anchor = "M01", meshGeneration = 1L)
        val topology2 = topology.copy(meshGeneration = 2L)

        fun presenceFromTopology(t: ConferenceTopologySnapshot) =
            ConferencePresenceFactsAdapter.snapshotFromLocalObservation(
                conferenceId = t.conferenceId,
                producerModuleId = t.anchorId!!,
                rosterEpoch = t.rosterEpoch,
                anchorEpoch = t.anchorEpoch,
                meshGeneration = t.meshGeneration,
                producedAtMs = 100_000L,
                localModuleId = t.hostModuleId,
                iceConnectedRemoteIds = setOf("M02", "M03")
            )

        val snap1 = presenceFromTopology(topology)
        val snap2 = presenceFromTopology(topology2)
        assertEquals(1L, snap1.meshGeneration)
        assertEquals(2L, snap2.meshGeneration)

        val out1 = ConferencePresenceProjector.compose(
            conferenceId = topology.conferenceId,
            canonicalRoster = topology.members,
            currentAnchorEpoch = topology.anchorEpoch,
            snapshot = snap1,
            nowMs = 100_000L
        )
        val out2 = ConferencePresenceProjector.compose(
            conferenceId = topology2.conferenceId,
            canonicalRoster = topology2.members,
            currentAnchorEpoch = topology2.anchorEpoch,
            snapshot = snap2,
            nowMs = 100_000L
        )
        assertEquals(4, out1.joinedCount)
        assertEquals(4, out2.joinedCount)
        assertEquals(topology.meshGeneration, 1L)
        assertEquals(topology2.meshGeneration, 2L)
        assertTrue(ConferenceTopologyContract.validateSnapshot(topology) is SnapshotValidity.Valid)
    }
}
