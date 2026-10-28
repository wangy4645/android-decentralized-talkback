package com.talkback.core.session

/**
 * Phase 2-3 harness — simulates controller seam without production Controller / ICE.
 */
class RecoveryControllerBindingHarness {
    var state = RecoveryControllerBindingState()
    val facts = mutableListOf<RecoveryExecutionFact>()

    private val actionHarness = RecoveryEdgeActionHarness()

    fun anchorSnapshot(
        meshGeneration: Long = 10L,
        anchorEpoch: Long = 100L
    ) = ConferenceTopologyContract.composeAnchorAdmission(
        AnchorAdmissionInput(
            conferenceId = "c-controller",
            hostModuleId = "M01",
            anchorId = "M01",
            members = listOf("M01", "M02", "M03", "M04"),
            meshGeneration = meshGeneration,
            anchorEpoch = anchorEpoch
        )
    )

    fun decisionInput(
        snapshot: ConferenceTopologySnapshot,
        remote: String = "M02"
    ) = RecoveryEdgeDecisionInput(
        context = actionHarness.contextForRemote(
            snapshot = snapshot,
            remoteModuleId = remote,
            lifecycleState = MediaExecutionLifecycleState.ACTIVE
        ),
        requestedAction = RecoveryEdgeAction.ICE_RESTART,
        currentSnapshot = snapshot
    )

    fun acceptAllowDecision(snapshot: ConferenceTopologySnapshot): RecoveryControllerBindingResult {
        val decision = RecoveryEdgeProvider.decide(decisionInput(snapshot))
        val result = RecoveryControllerBindingContract.acceptDecision(
            state = state,
            decision = decision,
            currentSnapshot = snapshot,
            activeRequest = state.acceptedRequest
        )
        state = RecoveryControllerBindingContract.apply(result, state)
        return result
    }

    fun beginExecution(snapshot: ConferenceTopologySnapshot): RecoveryControllerBindingResult {
        val result = RecoveryControllerBindingContract.beginExecution(state, snapshot)
        state = RecoveryControllerBindingContract.apply(result, state)
        return result
    }

    fun completeWithFact(
        request: RecoveryExecutionRequest,
        success: Boolean
    ): RecoveryControllerBindingResult {
        val kind = RecoveryExecutionBoundaryContract.classifyExecutorResult(
            success = success,
            deferred = false,
            rejectedInput = false
        )
        val fact = RecoveryExecutionBoundaryContract.factFromExecutorResult(
            request = request,
            kind = kind
        )
        facts += fact
        val result = RecoveryControllerBindingContract.receiveFact(state, fact)
        state = RecoveryControllerBindingContract.apply(result, state)
        return result
    }
}
