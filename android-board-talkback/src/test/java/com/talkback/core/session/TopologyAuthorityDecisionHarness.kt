package com.talkback.core.session

/**
 * Phase 1b-8 harness — proposal → decision → authority publish (test only).
 */
class TopologyAuthorityDecisionHarness(
    val authority: ConferenceTopologyAuthority = ConferenceTopologyAuthority()
) {
    val lifecycleEvents = mutableListOf<TopologyMediaEdgeLifecycleEvent>()
    private var previousSnapshot: ConferenceTopologySnapshot? = null

    fun seedAnchor(
        conferenceId: String = "conf-decision",
        anchor: String = "M01",
        members: List<String> = listOf("M01", "M02", "M03", "M04"),
        meshGeneration: Long = 5L,
        anchorEpoch: Long = 100L
    ): ConferenceTopologyAuthority.PublishResult {
        val result = authority.publishTopologyProjection(
            AnchorAdmissionInput(
                conferenceId = conferenceId,
                hostModuleId = "M01",
                anchorId = anchor,
                members = members,
                meshGeneration = meshGeneration,
                anchorEpoch = anchorEpoch
            )
        )
        if (result is ConferenceTopologyAuthority.PublishResult.Published) {
            previousSnapshot = null
            recordLifecycle(null, result.snapshot)
            previousSnapshot = result.snapshot
        }
        return result
    }

    fun decide(proposal: TopologyChangeProposal): TopologyDecisionResult {
        val current = authority.currentSnapshot(proposal.conferenceId)
        return TopologyAuthorityDecisionContract.decideProposal(current, proposal)
    }

    fun decideAndPublish(
        proposal: TopologyChangeProposal
    ): Pair<TopologyDecisionResult, ConferenceTopologyAuthority.PublishResult?> {
        val decision = decide(proposal)
        if (decision !is TopologyDecisionResult.AcceptForAuthorityPublish) {
            return decision to null
        }
        val publish = executePublish(decision)
        if (publish is ConferenceTopologyAuthority.PublishResult.Published) {
            recordLifecycle(previousSnapshot, publish.snapshot)
            previousSnapshot = publish.snapshot
        }
        return decision to publish
    }

    private fun executePublish(
        accepted: TopologyDecisionResult.AcceptForAuthorityPublish
    ): ConferenceTopologyAuthority.PublishResult {
        val proposal = accepted.proposal
        return when (accepted.command) {
            AuthorityPublishCommand.PUBLISH_TOPOLOGY_PROJECTION -> {
                val snapshot = proposal.candidateSnapshot
                    ?: return ConferenceTopologyAuthority.PublishResult.Rejected("missing candidate")
                authority.publishValidatedForTest(snapshot)
            }
            AuthorityPublishCommand.PUBLISH_ROSTER_EDGE_CHANGE -> {
                val members = proposal.newMembers
                    ?: return ConferenceTopologyAuthority.PublishResult.Rejected("missing members")
                authority.publishRosterEdgeChange(proposal.conferenceId, members)
            }
            AuthorityPublishCommand.PUBLISH_ANCHOR_FAILOVER -> {
                val anchor = proposal.newAnchorId
                    ?: return ConferenceTopologyAuthority.PublishResult.Rejected("missing anchor")
                authority.publishAnchorFailover(proposal.conferenceId, anchor)
            }
            AuthorityPublishCommand.PUBLISH_MODE_TRANSITION -> {
                val snapshot = proposal.candidateSnapshot
                    ?: return ConferenceTopologyAuthority.PublishResult.Rejected("missing candidate")
                val transition = ConferenceTopologyModeTransitionContract.detectTransition(
                    authority.previousSnapshot(proposal.conferenceId),
                    AnchorAdmissionDecision.Mesh
                )
                authority.publishModeTransition(snapshot, transition)
            }
        }
    }

    private fun recordLifecycle(
        previous: ConferenceTopologySnapshot?,
        next: ConferenceTopologySnapshot
    ) {
        lifecycleEvents.addAll(MediaEdgeLifecycleContract.diffTopologySnapshots(previous, next))
    }
}
