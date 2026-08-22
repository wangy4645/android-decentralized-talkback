package com.talkback.core.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 1b-3 contract fixtures G1–G8.
 * Spec: user-frozen 1b-3 admission decision cases (no Coordinator / ICE / media edges).
 *
 * Exit: G1–G8 PASS → 1b-3 contract PASS → 1b-3 implementation AUTHORIZED.
 */
class ConferenceAnchorAdmissionPhase1b3FixturesTest {

    private val roster3 = listOf("M01", "M02", "M03")
    private val roster4 = listOf("M01", "M02", "M03", "M04")
    private val threshold4 = 4

    private fun input(
        members: List<String> = roster4,
        host: String = "M01",
        candidate: String? = "M01",
        threshold: Int = threshold4,
        currentMode: ConferenceTopologyMode? = null,
        currentAnchorId: String? = null,
        anchorEpoch: Long = 100L,
        meshGeneration: Long = 1L
    ) = AnchorAdmissionDecisionInput(
        conferenceId = "c1",
        members = members,
        hostModuleId = host,
        candidateAnchorId = candidate,
        threshold = threshold,
        currentTopologyMode = currentMode,
        currentAnchorId = currentAnchorId,
        anchorEpoch = anchorEpoch,
        meshGeneration = meshGeneration
    )

    @Test
    fun g1_threeMembers_belowThreshold_staysMesh() {
        val decision = ConferenceAnchorAdmissionPolicy.decide(input(members = roster3))
        assertEquals(AnchorAdmissionDecision.Mesh, decision)
    }

    @Test
    fun g2_fourMembers_atThreshold_admitsAnchor() {
        val decision = ConferenceAnchorAdmissionPolicy.decide(input(members = roster4, candidate = "M01"))
        assertTrue(decision is AnchorAdmissionDecision.Anchor)
        assertEquals("M01", (decision as AnchorAdmissionDecision.Anchor).anchorId)
    }

    @Test
    fun g3_createFirstAdmission_anchorFollowsInitiator_preserveUnchangedOnRun() {
        val create = ConferenceAnchorAdmissionPolicy.decide(
            input(
                members = roster4,
                host = "M02",
                candidate = "M02",
                currentMode = ConferenceTopologyMode.MESH,
                currentAnchorId = null
            )
        )
        assertEquals(AnchorAdmissionDecision.Anchor("M02"), create)
        val preserve = ConferenceAnchorAdmissionPolicy.decide(
            input(
                members = roster4,
                host = "M02",
                candidate = "M02",
                currentMode = ConferenceTopologyMode.ANCHOR,
                currentAnchorId = "M01"
            )
        )
        assertEquals(AnchorAdmissionDecision.Anchor("M01"), preserve)
    }

    @Test
    fun g4_existingAnchor_notReElectedOnOrdinaryRead() {
        val decision = ConferenceAnchorAdmissionPolicy.decide(
            input(
                members = roster4,
                host = "M02",
                candidate = "M02",
                currentMode = ConferenceTopologyMode.ANCHOR,
                currentAnchorId = "M01"
            )
        )
        assertEquals(AnchorAdmissionDecision.Anchor("M01"), decision)
    }

    @Test
    fun g5_rosterGrow_threeToFour_recomputesMeshToAnchor() {
        val mesh = ConferenceAnchorAdmissionPolicy.decide(input(members = roster3, candidate = "M01"))
        assertEquals(AnchorAdmissionDecision.Mesh, mesh)
        val anchor = ConferenceAnchorAdmissionPolicy.decide(
            input(members = roster4, candidate = "M01", currentMode = ConferenceTopologyMode.MESH)
        )
        assertEquals(AnchorAdmissionDecision.Anchor("M01"), anchor)
    }

    @Test
    fun g6_thresholdCross_fourToThree_anchorToMesh() {
        val anchor = ConferenceAnchorAdmissionPolicy.decide(
            input(
                members = roster4,
                currentMode = ConferenceTopologyMode.ANCHOR,
                currentAnchorId = "M01"
            )
        )
        assertEquals(AnchorAdmissionDecision.Anchor("M01"), anchor)
        val mesh = ConferenceAnchorAdmissionPolicy.decide(
            input(
                members = roster3,
                currentMode = ConferenceTopologyMode.ANCHOR,
                currentAnchorId = "M01"
            )
        )
        assertEquals(AnchorAdmissionDecision.Mesh, mesh)
    }

    @Test
    fun g7_invalidCandidate_rejectedNoHalfTopology() {
        val decision = ConferenceAnchorAdmissionPolicy.decide(
            input(members = roster4, candidate = "M05")
        )
        assertTrue(decision is AnchorAdmissionDecision.Rejected)
        assertEquals("candidate anchor not in members", (decision as AnchorAdmissionDecision.Rejected).reason)
    }

    @Test
    fun g8_iceNotAnInput_thresholdNotInSnapshot() {
        val decision1 = ConferenceAnchorAdmissionPolicy.decide(input(members = roster4, candidate = "M01"))
        val decision2 = ConferenceAnchorAdmissionPolicy.decide(
            input(members = roster4, candidate = "M01", meshGeneration = 99L)
        )
        assertEquals(decision1, decision2)

        val anchorDecision = decision1 as AnchorAdmissionDecision.Anchor
        val snapshot = ConferenceTopologyContract.composeAnchorAdmission(
            ConferenceAnchorAdmissionPolicy.toAdmissionInput(
                input(members = roster4, candidate = "M01"),
                anchorDecision
            )
        )
        val peerObs = IceEdgeObservation(
            localModuleId = "M02",
            remoteModuleId = "M03",
            iceConnected = true
        )
        assertFalse(ConferenceTopologyContract.isAdmittedByTopology(peerObs, snapshot))
        assertFalse(TOPOLOGY_SNAPSHOT_FIELD_NAMES.contains("threshold"))
        assertTrue(ConferenceTopologyContract.validateSnapshot(snapshot) is SnapshotValidity.Valid)
    }

    companion object {
        /** Frozen inventory — threshold must never be added here (policy input only). */
        private val TOPOLOGY_SNAPSHOT_FIELD_NAMES = setOf(
            "conferenceId",
            "rosterEpoch",
            "anchorEpoch",
            "anchorId",
            "meshGeneration",
            "topologyMode",
            "hostModuleId",
            "members",
            "actualMediaEdges"
        )
    }
}
