package com.talkback.core.session

/**
 * Phase 4-0 harness — legacy observation → proposal → shadow authority path (no production wiring).
 */
class TopologyRuntimeAdapterHarness(
    val authority: ConferenceTopologyAuthority = ConferenceTopologyAuthority(),
    val adapter: LegacyRuntimeObservationAdapter = LegacyRuntimeObservationAdapter(
        mode = TopologyRuntimeAdapterMode.SHADOW
    )
) {
    private val decisionHarness = TopologyAuthorityDecisionHarness(authority)

    fun seedAnchor(
        conferenceId: String = "conf-runtime-adapter",
        members: List<String> = listOf("M01", "M02", "M03", "M04"),
        meshGeneration: Long = 8L,
        anchorEpoch: Long = 100L
    ): ConferenceTopologySnapshot {
        decisionHarness.seedAnchor(
            conferenceId = conferenceId,
            members = members,
            meshGeneration = meshGeneration,
            anchorEpoch = anchorEpoch
        )
        return authority.currentSnapshot(conferenceId)!!
    }

    fun legacyState(
        conferenceId: String = "conf-runtime-adapter",
        meshCompleted: Set<String> = setOf("M02", "M03"),
        peers: Map<String, LegacyPeerRuntimeState> = mapOf(
            "M02" to LegacyPeerRuntimeState("M02", iceConnected = true, inMesh = true),
            "M03" to LegacyPeerRuntimeState("M03", iceConnected = false, inMesh = true)
        )
    ) = LegacyRuntimeState(
        conferenceId = conferenceId,
        accepted = true,
        meshCompletedModules = meshCompleted,
        peerStates = peers
    )

    fun membershipObservation(
        conferenceId: String,
        members: List<String>
    ) = LegacyRuntimeObservation(
        conferenceId = conferenceId,
        kind = LegacyRuntimeObservationKind.MEMBERSHIP,
        observedMembers = members
    )

    fun authorityDecisionForProposal(
        proposal: TopologyChangeProposal
    ): TopologyDecisionResult =
        TopologyAuthorityDecisionContract.decideProposal(
            authority.currentSnapshot(proposal.conferenceId),
            proposal
        )
}
