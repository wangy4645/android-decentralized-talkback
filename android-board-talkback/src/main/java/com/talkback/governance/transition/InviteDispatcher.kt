package com.talkback.governance.transition

/**
 * ADR-0017: Policy-owned invite dispatch with bounded retry. Coordinator supplies per-target send;
 * this class owns retry/backoff only.
 */
class InviteDispatcher(
    private val policy: InviteDispatchPolicy,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleeper: (Long) -> Unit = Thread::sleep
) {
    fun <T> dispatch(
        targets: List<T>,
        send: (T) -> InviteDispatchSendResult
    ): InviteDispatchResult {
        if (targets.isEmpty()) {
            return InviteDispatchResult(
                outcome = InviteDispatchOutcome.SUCCESS,
                sentCount = 0,
                targetCount = 0
            )
        }
        var sentCount = 0
        for (target in targets) {
            val deadlineAt = clock() + policy.deadlineMs
            val targetResult = dispatchTarget(target, send, deadlineAt)
            when (targetResult.outcome) {
                InviteDispatchOutcome.SUCCESS -> sentCount++
                InviteDispatchOutcome.FAILED_NON_RETRYABLE ->
                    return InviteDispatchResult(
                        outcome = InviteDispatchOutcome.FAILED_NON_RETRYABLE,
                        sentCount = sentCount,
                        targetCount = targets.size,
                        lastError = targetResult.lastError
                    )
                InviteDispatchOutcome.FAILED_RETRY_EXHAUSTED ->
                    return InviteDispatchResult(
                        outcome = InviteDispatchOutcome.FAILED_RETRY_EXHAUSTED,
                        sentCount = sentCount,
                        targetCount = targets.size,
                        lastError = targetResult.lastError
                    )
            }
        }
        return InviteDispatchResult(
            outcome = InviteDispatchOutcome.SUCCESS,
            sentCount = sentCount,
            targetCount = targets.size
        )
    }

    private fun <T> dispatchTarget(
        target: T,
        send: (T) -> InviteDispatchSendResult,
        deadlineAt: Long
    ): InviteDispatchResult {
        var attempt = 0
        var lastError: InviteDispatchError? = null
        while (clock() < deadlineAt && attempt <= policy.maxRetry) {
            when (val result = send(target)) {
                InviteDispatchSendResult.Sent ->
                    return InviteDispatchResult(
                        outcome = InviteDispatchOutcome.SUCCESS,
                        sentCount = 1,
                        targetCount = 1
                    )
                is InviteDispatchSendResult.Failed -> {
                    lastError = result.error
                    when {
                        policy.isNonRetryable(result.error) ->
                            return InviteDispatchResult(
                                outcome = InviteDispatchOutcome.FAILED_NON_RETRYABLE,
                                sentCount = 0,
                                targetCount = 1,
                                lastError = result.error
                            )
                        !policy.isRetryable(result.error) ->
                            return InviteDispatchResult(
                                outcome = InviteDispatchOutcome.FAILED_NON_RETRYABLE,
                                sentCount = 0,
                                targetCount = 1,
                                lastError = result.error
                            )
                    }
                }
            }
            attempt++
            if (attempt <= policy.maxRetry && clock() < deadlineAt) {
                sleeper(backoffDelayMs(attempt))
            }
        }
        return InviteDispatchResult(
            outcome = InviteDispatchOutcome.FAILED_RETRY_EXHAUSTED,
            sentCount = 0,
            targetCount = 1,
            lastError = lastError
        )
    }

    private fun backoffDelayMs(attempt: Int): Long = when (policy.backoff) {
        InviteDispatchBackoff.FIXED -> policy.initialDelayMs
        InviteDispatchBackoff.EXPONENTIAL ->
            policy.initialDelayMs * (1L shl (attempt - 1).coerceAtMost(10))
    }
}
