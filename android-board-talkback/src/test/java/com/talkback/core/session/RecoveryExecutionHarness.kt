package com.talkback.core.session

/**
 * Phase 2-2 harness — single-flight slot book + decision→request pipeline (test only).
 */
class RecoveryExecutionHarness {
    private val activeByMediaEdgeId = mutableMapOf<String, RecoveryExecutionRequest>()
    val emittedFacts = mutableListOf<RecoveryExecutionFact>()

    fun decideAndAdmit(
        input: RecoveryEdgeDecisionInput,
        consumer: String = RecoveryExecutionBoundaryContract.SOLE_EXECUTION_CONSUMER
    ): RecoveryExecutionAdmission {
        val decision = RecoveryEdgeProvider.decide(input)
        val active = activeByMediaEdgeId[decisionMediaEdgeId(decision, input)]
        val admission = RecoveryExecutionBoundaryContract.admitExecutionRequest(
            consumer = consumer,
            decision = decision,
            currentSnapshot = input.currentSnapshot,
            activeRequest = active
        )
        if (admission is RecoveryExecutionAdmission.Accepted) {
            activeByMediaEdgeId[admission.request.mediaEdgeId] = admission.request
        }
        return admission
    }

    fun completeExecution(
        request: RecoveryExecutionRequest,
        success: Boolean,
        reason: String? = null
    ): RecoveryExecutionFactValidity {
        activeByMediaEdgeId.remove(request.mediaEdgeId)
        val kind = RecoveryExecutionBoundaryContract.classifyExecutorResult(
            success = success,
            deferred = false,
            rejectedInput = false
        )
        val fact = RecoveryExecutionBoundaryContract.factFromExecutorResult(
            request = request,
            kind = kind,
            reason = reason
        )
        emittedFacts += fact
        return RecoveryExecutionBoundaryContract.validateExecutionFact(fact)
    }

    fun activeRequest(mediaEdgeId: String): RecoveryExecutionRequest? =
        activeByMediaEdgeId[mediaEdgeId]

    private fun decisionMediaEdgeId(
        decision: RecoveryEdgeDecision,
        input: RecoveryEdgeDecisionInput
    ): String? = when (decision) {
        is RecoveryEdgeDecision.Allow ->
            TopologyMediaEdgeRef.fromSnapshot(
                input.currentSnapshot,
                decision.target.mediaEdge
            ).mediaEdgeId
        else -> null
    }
}
