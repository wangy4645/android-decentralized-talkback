package com.talkback.core.session

/**
 * Phase 3-4 harness — multi-edge decision → slot arbitration (no executor / no production wiring).
 */
class MultiEdgeRecoveryHarness(
    maxConcurrentSlots: Int = 3
) {
    val coordinator = MultiEdgeRecoveryCoordinator(maxConcurrentSlots)
    private val actionHarness = RecoveryEdgeActionHarness()

    fun anchorSnapshot(
        conferenceId: String = "c-multi-edge",
        meshGeneration: Long = 10L,
        anchorEpoch: Long = 100L,
        members: List<String> = listOf("M01", "M02", "M03", "M04", "M05")
    ) = actionHarness.anchorSnapshot(
        conferenceId = conferenceId,
        members = members,
        meshGeneration = meshGeneration,
        anchorEpoch = anchorEpoch
    )

    fun decisionInputForRemote(
        snapshot: ConferenceTopologySnapshot,
        remote: String,
        action: RecoveryEdgeAction = RecoveryEdgeAction.ICE_RESTART,
        meshGenerationOverride: Long? = null,
        anchorEpochOverride: Long? = null
    ): RecoveryEdgeDecisionInput {
        val context = actionHarness.contextForRemote(snapshot, remote)
        val ref = context.mediaEdgeRef.copy(
            meshGeneration = meshGenerationOverride ?: context.mediaEdgeRef.meshGeneration,
            anchorEpoch = anchorEpochOverride ?: context.mediaEdgeRef.anchorEpoch
        )
        return RecoveryEdgeDecisionInput(
            context = context.copy(mediaEdgeRef = ref),
            requestedAction = action,
            currentSnapshot = snapshot
        )
    }

    fun coordinateRemotes(
        snapshot: ConferenceTopologySnapshot,
        remotes: List<String>
    ): MultiEdgeRecoveryCoordinationResult =
        coordinator.coordinate(
            remotes.map { decisionInputForRemote(snapshot, it) }
        )

    fun admissionSignature(result: MultiEdgeRecoveryCoordinationResult): Map<String, String> =
        MultiEdgeRecoveryCoordinationContract.admissionSignature(result.outcomes)

    fun failFactForEdge(
        request: RecoveryExecutionRequest,
        reason: String = "edge-local failure"
    ): RecoveryExecutionFact.Outcome =
        RecoveryExecutionBoundaryContract.factFromExecutorResult(
            request = request,
            kind = RecoveryExecutionOutcomeKind.ACTION_FAILED,
            reason = reason
        )
}
