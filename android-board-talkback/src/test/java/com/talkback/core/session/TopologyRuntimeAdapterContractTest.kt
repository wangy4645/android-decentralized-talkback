package com.talkback.core.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADR-0056 Phase 4-0 — Topology runtime adapter contract (T4A–T4F). */
class TopologyRuntimeAdapterContractTest {

    private val conferenceId = "conf-runtime-adapter"
    private val roster4 = listOf("M01", "M02", "M03", "M04")

    @Test
    fun t4a_observationProducesProposal() {
        val harness = TopologyRuntimeAdapterHarness()
        val snap = harness.seedAnchor(conferenceId = conferenceId, members = roster4)
        val observation = harness.membershipObservation(conferenceId, roster4)
        val adapted = harness.adapter.adapt(observation, snap)
        assertTrue(adapted is TopologyRuntimeAdaptationResult.ProposalReady)
        val proposal = (adapted as TopologyRuntimeAdaptationResult.ProposalReady).proposal
        assertEquals(TopologyProposalKind.MEMBERSHIP_OBSERVATION, proposal.kind)
        assertEquals(TopologyProposalProducer.OBSERVER, proposal.producer)
    }

    @Test
    fun t4b_proposalCannotPublishDirectly() {
        val rejected = TopologyRuntimeAdapterContract.validateForbiddenAdapterAction(
            TopologyRuntimeForbiddenAdapterAction.PUBLISH_TOPOLOGY
        )
        assertEquals(TopologyRuntimeAdapterRejectCode.FORBIDDEN_ADAPTER_ACTION, rejected.code)
        val direct = TopologyAuthorityDecisionContract.validateDirectPublishAttempt(
            TopologyProposalProducer.OBSERVER
        )
        assertTrue(direct is TopologyDecisionResult.RejectProposal)
    }

    @Test
    fun t4c_staleObservationRejected() {
        val harness = TopologyRuntimeAdapterHarness()
        val snap = harness.seedAnchor(conferenceId = conferenceId, meshGeneration = 12L)
        val stale = harness.membershipObservation(conferenceId, roster4).copy(
            observedMeshGeneration = 10L
        )
        val result = harness.adapter.adapt(stale, snap)
        assertTrue(result is TopologyRuntimeAdaptationResult.DropStaleObservation)
        assertEquals(
            TopologyRuntimeAdapterContract.DROP_STALE_OBSERVATION,
            (result as TopologyRuntimeAdaptationResult.DropStaleObservation).reason
        )
    }

    @Test
    fun t4d_legacyStateIsNotTopologyTruth() {
        val harness = TopologyRuntimeAdapterHarness()
        val snap = harness.seedAnchor(conferenceId = conferenceId, members = roster4)
        val legacy = harness.legacyState(conferenceId = conferenceId, meshCompleted = setOf("M02", "M03"))
        val meshObservation = LegacyRuntimeObservation(
            conferenceId = conferenceId,
            kind = LegacyRuntimeObservationKind.MESH_COMPLETED_PEER,
            meshCompletedModules = legacy.meshCompletedModules,
            observedMembers = roster4
        )
        val rejected = harness.adapter.adapt(meshObservation, snap)
        assertTrue(rejected is TopologyRuntimeAdaptationResult.Rejected)
        assertEquals(
            TopologyRuntimeAdapterRejectCode.LEGACY_STATE_NOT_TOPOLOGY_TRUTH,
            (rejected as TopologyRuntimeAdaptationResult.Rejected).code
        )
        val legacyEdges = legacy.meshCompletedModules.map { MediaEdge("M01", it) }.toSet()
        assertNotEquals(snap.actualMediaEdges, legacyEdges)
    }

    @Test
    fun t4e_shadowOutputDeterministic() {
        val harness = TopologyRuntimeAdapterHarness()
        harness.seedAnchor(conferenceId = conferenceId, members = roster4)
        val legacy = harness.legacyState(conferenceId = conferenceId)
        val first = harness.adapter.shadowProject(legacy, harness.authority)
        val second = harness.adapter.shadowProject(legacy, harness.authority)
        assertEquals(first.signature, second.signature)
        assertEquals(first.decisions.map { TopologyRuntimeAdapterContract.decisionLabel(it) },
            second.decisions.map { TopologyRuntimeAdapterContract.decisionLabel(it) })
    }

    @Test
    fun t4f_oldNewProjectionEquality() {
        val harness = TopologyRuntimeAdapterHarness()
        val snap = harness.seedAnchor(conferenceId = conferenceId, members = roster4)
        val legacy = harness.legacyState(
            conferenceId = conferenceId,
            meshCompleted = setOf("M02"),
            peers = mapOf(
                "M02" to LegacyPeerRuntimeState("M02", iceConnected = true, inMesh = true),
                "M03" to LegacyPeerRuntimeState("M03", iceConnected = true, inMesh = true),
                "M04" to LegacyPeerRuntimeState("M04", iceConnected = true, inMesh = true)
            )
        )
        val shadow = harness.adapter.shadowProject(legacy, harness.authority)
        val membershipProposal = shadow.proposals.single {
            it.kind == TopologyProposalKind.MEMBERSHIP_OBSERVATION
        }
        val authorityDecision = harness.authorityDecisionForProposal(membershipProposal)
        assertTrue(shadow.decisions.contains(authorityDecision))
        assertEquals(
            TopologyRuntimeAdapterContract.decisionLabel(authorityDecision),
            TopologyRuntimeAdapterContract.decisionLabel(
                TopologyAuthorityDecisionContract.decideProposal(snap, membershipProposal)
            )
        )
        assertFalse(shadow.wouldPublish)
        assertEquals(snap, harness.authority.currentSnapshot(conferenceId))
    }

    @Test
    fun iceObservation_doesNotProduceTopologyProposal() {
        val harness = TopologyRuntimeAdapterHarness()
        val snap = harness.seedAnchor(conferenceId = conferenceId)
        val ice = LegacyRuntimeObservation(
            conferenceId = conferenceId,
            kind = LegacyRuntimeObservationKind.ICE_STATE,
            iceObservation = IceEdgeObservation("M01", "M02", iceConnected = false)
        )
        assertEquals(
            TopologyRuntimeAdaptationResult.NoProposalRequired,
            harness.adapter.adapt(ice, snap)
        )
    }
}
