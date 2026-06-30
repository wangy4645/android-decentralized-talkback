package com.talkback.core.ptt

enum class OperationDomain {
    FLOOR
}

enum class OperationTokenValidity {
    VALID,
    INVALIDATED,
    COMPLETED
}

data class FloorOperationIdentity(
    val sessionId: String,
    val requesterKey: String
)

data class FloorOperationToken(
    val identity: FloorOperationIdentity,
    val version: Long,
    val validity: OperationTokenValidity
) {
    fun isTerminal(): Boolean =
        validity == OperationTokenValidity.INVALIDATED ||
            validity == OperationTokenValidity.COMPLETED

    internal fun withValidity(next: OperationTokenValidity): FloorOperationToken =
        copy(validity = next)
}
