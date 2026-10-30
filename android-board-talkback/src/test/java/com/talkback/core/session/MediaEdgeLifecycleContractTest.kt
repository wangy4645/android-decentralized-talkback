package com.talkback.core.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADR-0056 Phase 1b-7 — MediaEdge lifecycle contract (L1–L5). */
class MediaEdgeLifecycleContractTest {

    private val roster4 = listOf("M01", "M02", "M03", "M04")

    private fun anchorInput(
        conferenceId: String = "c1",
        anchor: String = "M01",
        meshGeneration: Long = 1L,
        anchorEpoch: Long = 100L,
        members: List<String> = roster4
    ) = AnchorAdmissionInput(
        conferenceId = conferenceId,
        hostModuleId = "M01",
        anchorId = anchor,
        members = members,
        meshGeneration = meshGeneration,
        anchorEpoch = anchorEpoch
    )

    @Test
    fun l1_edgeCreate_admittedAndGenerationBumps() {
        val harness = MediaEdgeLifecycleHarness()
        harness.publishAnchor(anchorInput(meshGeneration = 4L))

        val admitted = harness.topologyLifecycleEvents().filterIsInstance<TopologyMediaEdgeLifecycleEvent.Admitted>()
        assertEquals(3, admitted.size)
        assertTrue(admitted.all { it.ref.meshGeneration == 4L })

        val expanded = harness.publishRosterChange(roster4 + "M05")
        assertTrue(expanded is ConferenceTopologyAuthority.PublishResult.Published)
        assertEquals(5L, harness.currentSnapshot!!.meshGeneration)
        assertTrue(
            harness.topologyLifecycleEvents().any {
                it is TopologyMediaEdgeLifecycleEvent.Admitted &&
                    it.ref.edge.remoteModuleId == "M05"
            }
        )
    }

    @Test
    fun l2_edgeRemove_invalidatedNotDrainingAtTopologyLayer() {
        val harness = MediaEdgeLifecycleHarness()
        harness.publishAnchor(anchorInput(meshGeneration = 10L))
        val edgeRef = TopologyMediaEdgeRef.fromSnapshot(
            harness.currentSnapshot!!,
            MediaEdge("M01", "M02")
        )
        harness.activateMedia(edgeRef)
        assertEquals(MediaExecutionLifecycleState.ACTIVE, harness.mediaState(edgeRef))

        harness.publishRosterChange(listOf("M01", "M03", "M04"))

        val invalidated = harness.topologyLifecycleEvents()
            .filterIsInstance<TopologyMediaEdgeLifecycleEvent.Invalidated>()
            .single { it.ref.edge.remoteModuleId == "M02" }
        assertEquals(MediaEdgeInvalidationReason.ROSTER_REMOVED, invalidated.reason)
        assertEquals(11L, harness.currentSnapshot!!.meshGeneration)
        assertEquals(MediaExecutionLifecycleState.REMOVED, harness.mediaState(edgeRef))
    }

    @Test
    fun l3_anchorMigration_oldIdInvalidatedNewIdCreated() {
        val harness = MediaEdgeLifecycleHarness()
        harness.publishAnchor(anchorInput(meshGeneration = 5L, anchorEpoch = 10L))
        val oldRef = TopologyMediaEdgeRef.fromSnapshot(
            harness.currentSnapshot!!,
            MediaEdge("M01", "M02")
        )

        harness.publishFailover("M02")

        val invalidated = harness.topologyLifecycleEvents()
            .filterIsInstance<TopologyMediaEdgeLifecycleEvent.Invalidated>()
            .single { it.ref.edge.remoteModuleId == "M02" && it.ref.edge.anchorModuleId == "M01" }
        assertEquals(MediaEdgeInvalidationReason.ANCHOR_MIGRATION, invalidated.reason)

        val newRef = TopologyMediaEdgeRef.fromSnapshot(
            harness.currentSnapshot!!,
            MediaEdge("M02", "M01")
        )
        assertNotEquals(oldRef.mediaEdgeId, newRef.mediaEdgeId)
        assertTrue(
            harness.topologyLifecycleEvents().any {
                it is TopologyMediaEdgeLifecycleEvent.Admitted && it.ref == newRef
            }
        )
    }

    @Test
    fun l4_negotiationEdgePresent_mediaEdgeAbsent_isValid() {
        val snapshot = ConferenceTopologyContract.composeAnchorAdmission(
            anchorInput(members = listOf("M01", "M02"))
        )
        val meshSnapshot = snapshot.copy(
            topologyMode = ConferenceTopologyMode.MESH,
            anchorId = null,
            anchorEpoch = 0L,
            actualMediaEdges = emptySet()
        )
        val negotiation = NegotiationEdgeRef(
            sessionId = "c1",
            localModuleId = "M02",
            remoteModuleId = "M03"
        )
        val result = MediaEdgeLifecycleContract.mapNegotiationToMediaEdge(negotiation, meshSnapshot)
        assertTrue(result is NegotiationMediaMappingResult.MediaEdgeAbsent)
    }

    @Test
    fun l5_staleMediaEvent_rejected() {
        val acceptance = MediaEdgeLifecycleContract.acceptMediaEdgeEvent(
            currentMeshGeneration = 5L,
            eventMeshGeneration = 4L,
            currentAnchorEpoch = 10L,
            eventAnchorEpoch = 10L
        )
        assertTrue(acceptance is MediaEdgeEventAcceptance.Stale)
        assertEquals(
            MediaEdgeLifecycleContract.STALE_MEDIA_EDGE_EVENT,
            (acceptance as MediaEdgeEventAcceptance.Stale).reason
        )
    }

    @Test
    fun mediaExecution_followsAdmittedActiveDrainRemoved() {
        val ref = TopologyMediaEdgeRef(
            conferenceId = "c1",
            meshGeneration = 1L,
            anchorEpoch = 1L,
            edge = MediaEdge("M01", "M02")
        )
        var state = MediaExecutionLifecycleState.ADMITTED
        val activate = MediaEdgeLifecycleContract.transitionMediaExecution(
            state,
            MediaExecutionTransition.ACTIVATE,
            ref
        )
        assertTrue(activate is MediaExecutionTransitionResult.Transitioned)
        state = (activate as MediaExecutionTransitionResult.Transitioned).to
        assertEquals(MediaExecutionLifecycleState.ACTIVE, state)

        val drain = MediaEdgeLifecycleContract.transitionMediaExecution(
            state,
            MediaExecutionTransition.BEGIN_DRAIN,
            ref
        )
        state = (drain as MediaExecutionTransitionResult.Transitioned).to
        assertEquals(MediaExecutionLifecycleState.DRAINING, state)

        val remove = MediaEdgeLifecycleContract.transitionMediaExecution(
            state,
            MediaExecutionTransition.COMPLETE_REMOVE,
            ref
        )
        state = (remove as MediaExecutionTransitionResult.Transitioned).to
        assertEquals(MediaExecutionLifecycleState.REMOVED, state)
    }

    @Test
    fun validateGeneration_rejectsMissingBump() {
        val previous = ConferenceTopologyContract.composeAnchorAdmission(anchorInput(meshGeneration = 3L))
        val next = previous.copy(
            meshGeneration = 3L,
            actualMediaEdges = previous.actualMediaEdges + MediaEdge("M01", "M05")
        )
        val validity = MediaEdgeLifecycleContract.validateGenerationOnEdgeSetChange(previous, next)
        assertTrue(validity is SnapshotValidity.Invalid)
    }
}
