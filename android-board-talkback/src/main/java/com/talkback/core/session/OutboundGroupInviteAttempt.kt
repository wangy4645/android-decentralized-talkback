package com.talkback.core.session

/**
 * Session-scoped outbound GROUP_INVITE delivery evidence for admission (#180 F2).
 * Not persisted; not wired to recovery delivery machinery.
 */
data class OutboundGroupInviteAttempt(
    val offerLineageId: String,
    val deliveryAttemptId: Long,
    val sessionId: String,
    val remoteModuleId: String,
    val semantic: GroupInvitePayloadSemantic,
    val issuedAtMs: Long,
    val handoffSucceeded: Boolean,
    val terminalReason: String? = null
) {
    val attemptRef: AttemptRef
        get() = AttemptRef(offerLineageId, deliveryAttemptId)

    data class AttemptRef(
        val offerLineageId: String,
        val deliveryAttemptId: Long
    )
}
