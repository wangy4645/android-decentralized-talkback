package com.talkback.core.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 1b-5 contract fixtures T1–T8 (MESH / ANCHOR mode boundary).
 * Exit: T1–T8 PASS → 1b-5 contract PASS → 1b-5 implementation NOT AUTHORIZED until signed.
 */
class ConferenceTopologyModeTransitionPhase1b5FixturesTest {

    private val roster3 = listOf("M01", "M02", "M03")
    private val roster4 = listOf("M01", "M02", "M03", "M04")
    private val threshold4 = 4

    private fun decisionInput(
        members: List<String> = roster4,
        host: String = "M01",
        candidate: String? = "M01",
        currentMode: ConferenceTopologyMode? = null,
        currentAnchorId: String? = null,
        meshGeneration: Long = 0L,
        anchorEpoch: Long = 0L
    ) = AnchorAdmissionDecisionInput(
        conferenceId = "c1",
        members = members,
        hostModuleId = host,
        candidateAnchorId = candidate,
        threshold = threshold4,
        currentTopologyMode = currentMode,
        currentAnchorId = currentAnchorId,
        meshGeneration = meshGeneration,
        anchorEpoch = anchorEpoch
    )

    private fun transitionInput(
        members: List<String>,
        decision: AnchorAdmissionDecision,
        previous: ConferenceTopologySnapshot? = null,
        meshGeneration: Long = previous?.meshGeneration ?: 0L,
        anchorEpoch: Long = previous?.anchorEpoch ?: 0L
    ) = ModeTransitionInput(
        conferenceId = "c1",
        hostModuleId = "M01",
        members = members,
        rosterEpoch = 1L,
        meshGeneration = meshGeneration,
        anchorEpoch = anchorEpoch,
        previousSnapshot = previous,
        decision = decision
    )

    @Test
    fun t1_threeMembers_meshSnapshot_hasEmptyActualMediaEdgeSet() {
        val decision = ConferenceAnchorAdmissionPolicy.decide(decisionInput(members = roster3))
        assertEquals(AnchorAdmissionDecision.Mesh, decision)
        val result = ConferenceTopologyModeTransitionContract.applyModeTransition(
            transitionInput(roster3, decision)
        )
        assertTrue(result is ModeTransitionResult.AdmittedMesh)
        val snapshot = (result as ModeTransitionResult.AdmittedMesh).snapshot
        assertEquals(ConferenceTopologyMode.MESH, snapshot.topologyMode)
        assertTrue(snapshot.actualMediaEdges.isEmpty())
        assertTrue(ConferenceTopologyModeTransitionContract.validateMeshSnapshot(snapshot) is SnapshotValidity.Valid)
    }

    @Test
    fun t2_fourMembers_meshToAnchor_bumpsMeshGeneration() {
        val meshDecision = ConferenceAnchorAdmissionPolicy.decide(decisionInput(members = roster3))
        val meshResult = ConferenceTopologyModeTransitionContract.applyModeTransition(
            transitionInput(roster3, meshDecision)
        ) as ModeTransitionResult.AdmittedMesh
        val anchorDecision = ConferenceAnchorAdmissionPolicy.decide(
            decisionInput(members = roster4, currentMode = ConferenceTopologyMode.MESH)
        )
        assertTrue(anchorDecision is AnchorAdmissionDecision.Anchor)
        val transition = ConferenceTopologyModeTransitionContract.detectTransition(
            meshResult.snapshot,
            anchorDecision
        )
        assertEquals(TopologyModeTransition.MESH_TO_ANCHOR, transition)
        val anchorResult = ConferenceTopologyModeTransitionContract.applyModeTransition(
            transitionInput(
                members = roster4,
                decision = anchorDecision,
                previous = meshResult.snapshot,
                meshGeneration = meshResult.snapshot.meshGeneration
            )
        )
        assertTrue(anchorResult is ModeTransitionResult.AdmittedAnchor)
        assertEquals(1L, (anchorResult as ModeTransitionResult.AdmittedAnchor).snapshot.meshGeneration)
    }

    @Test
    fun t3_meshToAnchor_replacesEmptyWithStar_notMerge() {
        val mesh = ConferenceTopologyModeTransitionContract.composeMeshSnapshot(
            "c1", "M01", roster3, rosterEpoch = 1L, meshGeneration = 0L
        )
        val decision = AnchorAdmissionDecision.Anchor("M01")
        val result = ConferenceTopologyModeTransitionContract.applyModeTransition(
            transitionInput(roster4, decision, previous = mesh, meshGeneration = 0L, anchorEpoch = 100L)
        ) as ModeTransitionResult.AdmittedAnchor
        assertTrue(mesh.actualMediaEdges.isEmpty())
        assertEquals(
            ConferenceTopologyContract.anchorStarEdges("M01", roster4),
            result.snapshot.actualMediaEdges
        )
    }

    @Test
    fun t4_anchorToMesh_clearsEdges_andBumpsGeneration() {
        val anchor = ConferenceMediaEdgeAdmissionContract.admitFromTopologyProjection(
            AnchorAdmissionInput("c1", "M01", "M01", roster4, meshGeneration = 5L, anchorEpoch = 100L)
        ) as MediaEdgeAdmissionResult.Admitted
        val decision = AnchorAdmissionDecision.Mesh
        val transition = ConferenceTopologyModeTransitionContract.detectTransition(
            anchor.snapshot,
            decision
        )
        assertEquals(TopologyModeTransition.ANCHOR_TO_MESH, transition)
        val result = ConferenceTopologyModeTransitionContract.applyModeTransition(
            transitionInput(
                members = roster3,
                decision = decision,
                previous = anchor.snapshot,
                meshGeneration = 5L
            )
        ) as ModeTransitionResult.AdmittedMesh
        assertTrue(result.snapshot.actualMediaEdges.isEmpty())
        assertEquals(6L, result.snapshot.meshGeneration)
        assertTrue(ConferenceTopologyModeTransitionContract.anchorEdgesInvalidated(anchor.snapshot).isNotEmpty())
    }

    @Test
    fun t5_meshSnapshot_anchorEpochInactive() {
        val snapshot = ConferenceTopologyModeTransitionContract.composeMeshSnapshot(
            "c1", "M01", roster3, rosterEpoch = 2L, meshGeneration = 3L
        )
        assertEquals(0L, snapshot.anchorEpoch)
        assertNull(snapshot.anchorId)
        assertTrue(ConferenceTopologyModeTransitionContract.validateMeshSnapshot(snapshot) is SnapshotValidity.Valid)
        val invalid = snapshot.copy(anchorEpoch = 100L)
        assertTrue(ConferenceTopologyModeTransitionContract.validateMeshSnapshot(invalid) is SnapshotValidity.Invalid)
    }

    @Test
    fun t6_noStagedPartialTopologyDuringTransition() {
        val anchor = ConferenceMediaEdgeAdmissionContract.admitFromTopologyProjection(
            AnchorAdmissionInput("c1", "M01", "M01", roster4)
        ) as MediaEdgeAdmissionResult.Admitted
        val half = anchor.snapshot.copy(
            anchorId = "M02",
            actualMediaEdges = anchor.snapshot.actualMediaEdges
        )
        assertTrue(ConferenceTopologyContract.validateSnapshot(half) is SnapshotValidity.Invalid)
        val meshWithEdges = ConferenceTopologyModeTransitionContract.composeMeshSnapshot(
            "c1", "M01", roster3, 1L, 0L
        ).copy(actualMediaEdges = setOf(MediaEdge("M01", "M02")))
        assertTrue(ConferenceTopologyModeTransitionContract.validateMeshSnapshot(meshWithEdges) is SnapshotValidity.Invalid)
    }

    @Test
    fun t7_unchangedMesh_noGenerationBump() {
        val decision = AnchorAdmissionDecision.Mesh
        val transition = ConferenceTopologyModeTransitionContract.detectTransition(
            ConferenceTopologyModeTransitionContract.composeMeshSnapshot("c1", "M01", roster3, 1L, 2L),
            decision
        )
        assertEquals(TopologyModeTransition.UNCHANGED_MESH, transition)
        assertEquals(
            2L,
            ConferenceTopologyModeTransitionContract.meshGenerationAfterModeChange(2L, transition)
        )
    }

    @Test
    fun t8_cppReadPath_meshObservation_anchorAuthoritySnapshot() {
        assertEquals(
            ConferencePresenceReadPath.MESH_SESSION_OBSERVATION,
            ConferenceTopologyModeTransitionContract.presenceReadPath(ConferenceTopologyMode.MESH)
        )
        assertEquals(
            ConferencePresenceReadPath.ANCHOR_AUTHORITY_SNAPSHOT,
            ConferenceTopologyModeTransitionContract.presenceReadPath(ConferenceTopologyMode.ANCHOR)
        )
        val mesh = ConferenceTopologyModeTransitionContract.composeMeshSnapshot("c1", "M01", roster3, 1L, 0L)
        assertFalse(mesh.actualMediaEdges.isNotEmpty())
    }
}
