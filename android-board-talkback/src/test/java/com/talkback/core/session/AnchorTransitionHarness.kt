package com.talkback.core.session

/**
 * Phase 4-1 harness — anchor topology simulation (no WebRTC / production wiring).
 */
class AnchorTransitionHarness(
    private val internalAuthority: ConferenceTopologyAuthority = ConferenceTopologyAuthority()
) {
    val simulator: AnchorTopologySimulator = AnchorTopologySimulator(internalAuthority)

    fun seedMeshThenAnchor(
        conferenceId: String = "conf-anchor-sim",
        members: List<String> = listOf("M01", "M02", "M03", "M04"),
        anchorId: String = "M01"
    ): AnchorTransitionSimulationOutcome =
        simulator.simulateMeshToAnchor(
            conferenceId = conferenceId,
            hostModuleId = "M01",
            members = members,
            anchorId = anchorId
        )

    fun seedAnchor(
        conferenceId: String = "conf-anchor-sim",
        members: List<String> = listOf("M01", "M02", "M03", "M04"),
        anchorId: String = "M01",
        meshGeneration: Long = 8L,
        anchorEpoch: Long = 100L
    ): ConferenceTopologySnapshot {
        internalAuthority.publishTopologyProjection(
            AnchorAdmissionInput(
                conferenceId = conferenceId,
                hostModuleId = "M01",
                anchorId = anchorId,
                members = members,
                meshGeneration = meshGeneration,
                anchorEpoch = anchorEpoch
            )
        )
        return internalAuthority.currentSnapshot(conferenceId)!!
    }

    fun failover(
        conferenceId: String = "conf-anchor-sim",
        newAnchorId: String
    ): AnchorTransitionSimulationOutcome =
        simulator.simulateAnchorFailover(conferenceId, newAnchorId)

    fun currentSnapshot(conferenceId: String = "conf-anchor-sim"): ConferenceTopologySnapshot? =
        internalAuthority.currentSnapshot(conferenceId)

    fun selectionObservations(
        conferenceId: String,
        candidate: String,
        networkScore: Long = 80L
    ) = listOf(
        AnchorSelectionObservation(
            conferenceId = conferenceId,
            kind = AnchorSelectionObservationKind.TOPOLOGY_OBSERVATION,
            candidateAnchorId = candidate
        ),
        AnchorSelectionObservation(
            conferenceId = conferenceId,
            kind = AnchorSelectionObservationKind.NODE_CAPABILITY,
            candidateAnchorId = candidate,
            nodeCapabilityRank = 1
        ),
        AnchorSelectionObservation(
            conferenceId = conferenceId,
            kind = AnchorSelectionObservationKind.NETWORK_QUALITY_FACT,
            candidateAnchorId = candidate,
            networkQualityScore = networkScore
        )
    )
}
