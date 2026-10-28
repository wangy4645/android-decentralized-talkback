package com.talkback.core.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADR-0056 Phase 1b-8 — Topology authority decision contract (D1–D8). */
class TopologyAuthorityDecisionContractTest {

    private val roster4 = listOf("M01", "M02", "M03", "M04")
    private val conferenceId = "conf-decision"

    private fun currentSnapshot(harness: TopologyAuthorityDecisionHarness): ConferenceTopologySnapshot =
        harness.authority.currentSnapshot(conferenceId)!!

    @Test
    fun d1_rosterChangeOnly_noTopologyPublish() {
        val current = ConferenceTopologyContract.composeAnchorAdmission(
            AnchorAdmissionInput(
                conferenceId = conferenceId,
                hostModuleId = "M01",
                anchorId = "M01",
                members = roster4
            )
        )
        assertEquals(
            RosterTopologyImpact.MembershipOnly,
            TopologyAuthorityDecisionContract.evaluateRosterTopologyImpact(current, roster4)
        )
        val decision = TopologyAuthorityDecisionContract.decideProposal(
            current,
            TopologyChangeProposal(
                conferenceId = conferenceId,
                producer = TopologyProposalProducer.COORDINATOR,
                kind = TopologyProposalKind.MEMBERSHIP_OBSERVATION,
                newMembers = roster4
            )
        )
        assertEquals(TopologyDecisionResult.NoTopologyPublishRequired, decision)
    }

    @Test
    fun d2_edgeImpact_proposalAcceptedAndPublished() {
        val harness = TopologyAuthorityDecisionHarness()
        harness.seedAnchor(conferenceId = conferenceId, meshGeneration = 8L)

        val (decision, publish) = harness.decideAndPublish(
            TopologyChangeProposal(
                conferenceId = conferenceId,
                producer = TopologyProposalProducer.COORDINATOR,
                kind = TopologyProposalKind.ROSTER_EDGE_CHANGE,
                newMembers = roster4 + "M05"
            )
        )
        assertTrue(decision is TopologyDecisionResult.AcceptForAuthorityPublish)
        assertTrue(publish is ConferenceTopologyAuthority.PublishResult.Published)
        assertEquals(9L, currentSnapshot(harness).meshGeneration)
        assertTrue(
            harness.lifecycleEvents.any { it is TopologyMediaEdgeLifecycleEvent.Admitted }
        )
    }

    @Test
    fun d3_recoveryCannotDirectPublish() {
        val direct = TopologyAuthorityDecisionContract.validateDirectPublishAttempt(
            TopologyProposalProducer.RECOVERY
        )
        assertTrue(direct is TopologyDecisionResult.RejectProposal)
        assertEquals(
            TopologyDecisionRejectCode.PRODUCER_CANNOT_PUBLISH,
            (direct as TopologyDecisionResult.RejectProposal).code
        )

        val harness = TopologyAuthorityDecisionHarness()
        harness.seedAnchor(conferenceId = conferenceId)
        val recoverySnapshot = currentSnapshot(harness).copy(anchorId = "M02")
        val decision = harness.decide(
            TopologyChangeProposal(
                conferenceId = conferenceId,
                producer = TopologyProposalProducer.RECOVERY,
                kind = TopologyProposalKind.ANCHOR_FAILOVER,
                newAnchorId = "M02",
                candidateSnapshot = recoverySnapshot
            )
        )
        assertTrue(decision is TopologyDecisionResult.RejectProposal)
        assertEquals(
            TopologyDecisionRejectCode.RECOVERY_CANNOT_MUTATE_AUTHORITY,
            (decision as TopologyDecisionResult.RejectProposal).code
        )
    }

    @Test
    fun d4_anchorFailover_epochBumpAndAuthorizedTransition() {
        val harness = TopologyAuthorityDecisionHarness()
        harness.seedAnchor(conferenceId = conferenceId, meshGeneration = 12L, anchorEpoch = 20L)
        val previous = currentSnapshot(harness)

        val (_, publish) = harness.decideAndPublish(
            TopologyChangeProposal(
                conferenceId = conferenceId,
                producer = TopologyProposalProducer.COORDINATOR,
                kind = TopologyProposalKind.ANCHOR_FAILOVER,
                newAnchorId = "M02"
            )
        )
        assertTrue(publish is ConferenceTopologyAuthority.PublishResult.Published)
        val published = publish as ConferenceTopologyAuthority.PublishResult.Published
        assertTrue(
            TopologyAuthorityDecisionContract.validateAnchorFailoverPublish(previous, published)
                is SnapshotValidity.Valid
        )
        assertEquals(21L, published.snapshot.anchorEpoch)
        assertTrue(published.authorizedTransition != null)
    }

    @Test
    fun d5_staleGeneration_rejectedWithoutSideEffects() {
        val harness = TopologyAuthorityDecisionHarness()
        harness.seedAnchor(conferenceId = conferenceId, meshGeneration = 10L)
        val staleMembers = roster4 + "M05"
        val admission = ConferenceMediaEdgeAdmissionContract.admitFromRosterChange(
            currentSnapshot(harness),
            staleMembers
        )
        val staleSnapshot = (admission as MediaEdgeAdmissionResult.Admitted).snapshot
            .copy(meshGeneration = 9L)

        val eventsBefore = harness.lifecycleEvents.size
        val decision = TopologyAuthorityDecisionContract.decideProposal(
            currentSnapshot(harness),
            TopologyChangeProposal(
                conferenceId = conferenceId,
                producer = TopologyProposalProducer.COORDINATOR,
                kind = TopologyProposalKind.TOPOLOGY_PROJECTION,
                candidateSnapshot = staleSnapshot
            )
        )
        assertTrue(decision is TopologyDecisionResult.RejectProposal)
        assertEquals(
            TopologyDecisionRejectCode.REJECT_STALE_PROPOSAL,
            (decision as TopologyDecisionResult.RejectProposal).code
        )
        assertEquals(10L, currentSnapshot(harness).meshGeneration)
        assertEquals(eventsBefore, harness.lifecycleEvents.size)
    }

    @Test
    fun d6_competingProposal_deterministicWinner() {
        val harness = TopologyAuthorityDecisionHarness()
        harness.seedAnchor(conferenceId = conferenceId, meshGeneration = 3L)
        val current = currentSnapshot(harness)

        val stale = TopologyChangeProposal(
            conferenceId = conferenceId,
            producer = TopologyProposalProducer.OBSERVER,
            kind = TopologyProposalKind.TOPOLOGY_PROJECTION,
            candidateSnapshot = current.copy(meshGeneration = 2L)
        )
        val fresh = TopologyChangeProposal(
            conferenceId = conferenceId,
            producer = TopologyProposalProducer.COORDINATOR,
            kind = TopologyProposalKind.ROSTER_EDGE_CHANGE,
            newMembers = roster4 + "M05"
        )
        val result = TopologyAuthorityDecisionContract.selectWinningProposal(current, listOf(stale, fresh))
        assertTrue(result is TopologyProposalCompetitionResult.Winner)
        assertEquals(fresh, (result as TopologyProposalCompetitionResult.Winner).proposal)
    }

    @Test
    fun d7_recoveryCannotMutateAuthorityState() {
        assertTrue(
            TopologyAuthorityDecisionContract.validateRecoveryEmission(
                RecoveryEmissionKind.EDGE_RECOVERY_RESULT
            ) is RecoveryAuthorityBoundaryResult.Permitted
        )
        assertTrue(
            TopologyAuthorityDecisionContract.validateRecoveryEmission(
                RecoveryEmissionKind.TOPOLOGY_SNAPSHOT
            ) is RecoveryAuthorityBoundaryResult.Forbidden
        )
        assertTrue(
            TopologyAuthorityDecisionContract.validateRecoveryEmission(
                RecoveryEmissionKind.ANCHOR_EPOCH_MUTATION
            ) is RecoveryAuthorityBoundaryResult.Forbidden
        )
    }

    @Test
    fun d8_snapshotPublish_idempotent() {
        val harness = TopologyAuthorityDecisionHarness()
        harness.seedAnchor(conferenceId = conferenceId)
        val current = currentSnapshot(harness)

        val decision = TopologyAuthorityDecisionContract.evaluateIdempotentPublish(current, current)
        assertEquals(TopologyDecisionResult.NoTopologyPublishRequired, decision)

        val republish = harness.authority.publishTopologyProjection(
            AnchorAdmissionInput(
                conferenceId = conferenceId,
                hostModuleId = "M01",
                anchorId = "M01",
                members = roster4,
                meshGeneration = current.meshGeneration,
                anchorEpoch = current.anchorEpoch
            )
        )
        assertEquals(ConferenceTopologyAuthority.PublishResult.Unchanged, republish)
        assertNull(harness.authority.currentAuthorizedTransition(conferenceId))
    }
}
