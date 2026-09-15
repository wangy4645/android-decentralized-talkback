package com.talkback.core.session.failure

/**
 * B1 control-plane recovery intent ([edge-srd-recovery-ia-v0.1-b1]).
 * Does not mutate media engines, leases, or native runtime.
 */
enum class EdgeSrdRecoveryState {
    IDLE,
    RECOVERY_REQUESTED,
    WAITING_FOR_SAFE_ADMISSION,
    TERMINAL_FAILED,
}

data class EdgeSrdRecoveryScopeKey(
    val conferenceSessionId: String,
    val edgeKey: String,
    val meshGeneration: Long,
    val pcGeneration: Long?,
) {
    companion object {
        fun fromScope(edgeKey: String, scope: ConferenceFailureGenerationScope): EdgeSrdRecoveryScopeKey =
            EdgeSrdRecoveryScopeKey(
                conferenceSessionId = scope.conferenceSessionId,
                edgeKey = edgeKey,
                meshGeneration = scope.meshGeneration,
                pcGeneration = scope.pcGeneration,
            )
    }
}

data class EdgeSrdRecoveryIntent(
    val scopeKey: EdgeSrdRecoveryScopeKey,
    val remoteModuleId: String,
    val conferenceGeneration: Long?,
    val runtimeDomainRef: String,
    val offerLineageId: String?,
    val realizationAttemptId: String?,
    val causeFact: String,
    val requestedAtMs: Long,
    val intentGeneration: Long,
    val state: EdgeSrdRecoveryState,
    val terminalReason: String? = null,
)

data class EdgeSrdRecoveryTrigger(
    val terminal: ConferenceFailureTerminal.EdgeFailed,
    val remoteModuleId: String,
    val conferenceGeneration: Long? = null,
    val offerLineageId: String? = null,
    val realizationAttemptId: String? = null,
    val causeFact: String,
)
