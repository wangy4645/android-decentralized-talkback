package com.talkback.governance.transition

enum class InviteDispatchError {
    TRANSPORT_NOT_READY,
    SIGNALING_RECONNECTING,
    GATE_BLOCKED,
    INVALID_DECLARATION,
    UNKNOWN_ENDPOINT,
    SDP_BUILD_FAILED
}

enum class InviteDispatchBackoff {
    EXPONENTIAL,
    FIXED
}

data class InviteDispatchPolicy(
    val retryableErrors: Set<InviteDispatchError>,
    val nonRetryableErrors: Set<InviteDispatchError>,
    val maxRetry: Int,
    val deadlineMs: Long,
    val backoff: InviteDispatchBackoff,
    val initialDelayMs: Long
) {
    fun isRetryable(error: InviteDispatchError): Boolean = error in retryableErrors

    fun isNonRetryable(error: InviteDispatchError): Boolean = error in nonRetryableErrors

    companion object {
        val MEETING_START_DEFAULT: InviteDispatchPolicy = InviteDispatchPolicy(
            retryableErrors = setOf(
                InviteDispatchError.TRANSPORT_NOT_READY,
                InviteDispatchError.SIGNALING_RECONNECTING,
                InviteDispatchError.GATE_BLOCKED
            ),
            nonRetryableErrors = setOf(
                InviteDispatchError.INVALID_DECLARATION,
                InviteDispatchError.UNKNOWN_ENDPOINT,
                InviteDispatchError.SDP_BUILD_FAILED
            ),
            maxRetry = 3,
            deadlineMs = 3_000L,
            backoff = InviteDispatchBackoff.EXPONENTIAL,
            initialDelayMs = 100L
        )
    }
}

enum class InviteDispatchOutcome {
    SUCCESS,
    FAILED_RETRY_EXHAUSTED,
    FAILED_NON_RETRYABLE
}

data class InviteDispatchResult(
    val outcome: InviteDispatchOutcome,
    val sentCount: Int,
    val targetCount: Int,
    val lastError: InviteDispatchError? = null
)

sealed interface InviteDispatchSendResult {
    data object Sent : InviteDispatchSendResult
    data class Failed(val error: InviteDispatchError) : InviteDispatchSendResult
}
