package com.talkback.core.session

/**
 * Phase 3-0 harness — controller binding + executor adapter (no WebRTC / production).
 */
class RecoveryExecutorAdapterHarness {
    private val controllerHarness = RecoveryControllerBindingHarness()
    private val executor = FakeRecoveryExecutor()
    private var activeSlot: RecoveryExecutionRequest? = null

    val controllerState: RecoveryControllerBindingState
        get() = controllerHarness.state

    fun anchorSnapshot(
        meshGeneration: Long = 10L,
        anchorEpoch: Long = 100L
    ) = controllerHarness.anchorSnapshot(meshGeneration, anchorEpoch)

    fun acceptAndBegin(snapshot: ConferenceTopologySnapshot): RecoveryExecutionRequest? {
        controllerHarness.acceptAllowDecision(snapshot)
        controllerHarness.beginExecution(snapshot)
        return controllerHarness.state.acceptedRequest
    }

    fun executeThroughAdapter(
        snapshot: ConferenceTopologySnapshot,
        request: RecoveryExecutionRequest,
        inFlightForDuplicateCheck: RecoveryExecutionRequest? = activeSlot
    ): RecoveryExecutorAdapterResult {
        val result = ConferenceRecoveryExecutorAdapter.execute(
            request = request,
            currentSnapshot = snapshot,
            executor = executor,
            inFlightRequest = inFlightForDuplicateCheck
        )
        if (inFlightForDuplicateCheck == null &&
            result is RecoveryExecutorAdapterResult.FactEmitted &&
            result.executorInvoked
        ) {
            activeSlot = request
        }
        return result
    }

    fun releaseActiveSlot() {
        activeSlot = null
    }

    fun fakeExecutor(): FakeRecoveryExecutor = executor
}
