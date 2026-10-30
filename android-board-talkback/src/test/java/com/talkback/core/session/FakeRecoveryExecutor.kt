package com.talkback.core.session

/** Phase 3-0 controlled test executor — simulates outcome only. */
class FakeRecoveryExecutor(
    private var programmedKind: RecoveryExecutionOutcomeKind = RecoveryExecutionOutcomeKind.ACTION_COMPLETED,
    private var programmedReason: String? = null
) : ConferenceRecoveryExecutor {
    var invokeCount: Int = 0
        private set

    fun program(kind: RecoveryExecutionOutcomeKind, reason: String? = null) {
        programmedKind = kind
        programmedReason = reason
    }

    override fun execute(request: RecoveryExecutionRequest): RecoveryExecutionFact.Outcome {
        invokeCount++
        return RecoveryExecutionBoundaryContract.factFromExecutorResult(
            request = request,
            kind = programmedKind,
            reason = programmedReason
        )
    }
}
