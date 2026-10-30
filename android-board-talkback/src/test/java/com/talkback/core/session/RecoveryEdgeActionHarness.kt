package com.talkback.core.session

/**
 * Phase 2-0b harness — recovery action evaluation without Controller / topology publish.
 */
class RecoveryEdgeActionHarness {
    fun anchorSnapshot(
        conferenceId: String = "c-recovery",
        anchor: String = "M01",
        members: List<String> = listOf("M01", "M02", "M03"),
        meshGeneration: Long = 5L,
        anchorEpoch: Long = 10L
    ): ConferenceTopologySnapshot =
        ConferenceTopologyContract.composeAnchorAdmission(
            AnchorAdmissionInput(
                conferenceId = conferenceId,
                hostModuleId = "M01",
                anchorId = anchor,
                members = members,
                meshGeneration = meshGeneration,
                anchorEpoch = anchorEpoch
            )
        )

    fun contextForRemote(
        snapshot: ConferenceTopologySnapshot,
        remoteModuleId: String,
        lifecycleState: MediaExecutionLifecycleState = MediaExecutionLifecycleState.ACTIVE,
        authorizedTransition: AuthorizedTransition? = null
    ): RecoveryEdgeContext {
        val edge = MediaEdge(
            anchorModuleId = snapshot.anchorId!!,
            remoteModuleId = remoteModuleId
        )
        return RecoveryEdgeContext(
            mediaEdgeRef = TopologyMediaEdgeRef.fromSnapshot(snapshot, edge),
            lifecycleState = lifecycleState,
            negotiation = NegotiationEdgeRef(
                sessionId = snapshot.conferenceId,
                localModuleId = snapshot.anchorId!!,
                remoteModuleId = remoteModuleId
            ),
            authorizedTransition = authorizedTransition
        )
    }
}
