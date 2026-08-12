package com.talkback.core.session

/**
 * #179 — bootstrap admission intent (not membership, not roster).
 * Tracks producer-side obligation to complete first GROUP_INVITE admission for a peer.
 */
data class BootstrapAdmissionIntentKey(
    val channelId: String,
    val targetModuleId: String
) {
    fun storageKey(): String = "$channelId|$targetModuleId"
}

enum class BootstrapAdmissionRequiredAction {
    GROUP_INVITE
}

enum class BootstrapAdmissionIntentState {
    PENDING,
    WAITING_EDGE_READY,
    INVITE_SENT,
    ACCEPTED,
    FAILED,
    EXPIRED
}

data class BootstrapAdmissionIntent(
    val key: BootstrapAdmissionIntentKey,
    val createdAtMs: Long,
    val createReason: String,
    val requiredAction: BootstrapAdmissionRequiredAction = BootstrapAdmissionRequiredAction.GROUP_INVITE,
    val state: BootstrapAdmissionIntentState = BootstrapAdmissionIntentState.PENDING,
    val waitingReason: String? = null,
    val sessionId: String? = null,
    val updatedAtMs: Long = createdAtMs
)
