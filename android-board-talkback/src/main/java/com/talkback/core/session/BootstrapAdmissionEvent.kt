package com.talkback.core.session

/**
 * Explicit bootstrap admission events consumed by the #179 intent state machine.
 * Not inferred from [com.talkback.core.model.SignalType] alone.
 */
sealed interface BootstrapAdmissionEvent {

    data class BootstrapInviteAttemptRef(
        val offerLineageId: String?,
        val deliveryAttemptId: Long
    )

    data class BootstrapInviteIssued(
        val peerModuleId: String,
        val sessionId: String,
        val attemptRef: BootstrapInviteAttemptRef
    ) : BootstrapAdmissionEvent
}
