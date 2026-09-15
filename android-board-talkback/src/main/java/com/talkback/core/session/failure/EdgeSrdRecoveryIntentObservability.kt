package com.talkback.core.session.failure

object EdgeSrdRecoveryIntentObservability {

    fun formatRequested(intent: EdgeSrdRecoveryIntent): String =
        formatEvent("RECOVERY_REQUESTED", intent)

    fun formatWaitingForDomain(intent: EdgeSrdRecoveryIntent): String =
        formatEvent("RECOVERY_WAITING_FOR_DOMAIN", intent)

    fun formatSuppressed(
        scopeKey: EdgeSrdRecoveryScopeKey,
        remoteModuleId: String,
        reason: String,
        runtimeDomainRef: String? = null,
        causeFact: String? = null,
    ): String {
        val domain = runtimeDomainRef?.let { " runtimeDomainRef=$it" }.orEmpty()
        val cause = causeFact?.let { " causeFact=$it" }.orEmpty()
        return "RECOVERY_SUPPRESSED session=${scopeKey.conferenceSessionId} " +
            "edgeKey=${scopeKey.edgeKey} remote=$remoteModuleId " +
            "meshGeneration=${scopeKey.meshGeneration} pcGeneration=${scopeKey.pcGeneration} " +
            "reason=$reason$domain$cause"
    }

    fun formatTerminal(intent: EdgeSrdRecoveryIntent): String =
        formatEvent(
            "RECOVERY_TERMINAL",
            intent,
            extra = intent.terminalReason?.let { " terminalReason=$it" }.orEmpty(),
        )

    private fun formatEvent(
        event: String,
        intent: EdgeSrdRecoveryIntent,
        extra: String = "",
    ): String {
        val key = intent.scopeKey
        val lineage = buildString {
            intent.offerLineageId?.let { append(" offerLineageId=$it") }
            intent.realizationAttemptId?.let { append(" realizationAttemptId=$it") }
        }
        val confGen = intent.conferenceGeneration?.let { " conferenceGeneration=$it" }.orEmpty()
        return "$event session=${key.conferenceSessionId} edgeKey=${key.edgeKey} " +
            "remote=${intent.remoteModuleId} meshGeneration=${key.meshGeneration} " +
            "pcGeneration=${key.pcGeneration}$confGen runtimeDomainRef=${intent.runtimeDomainRef} " +
            "causeFact=${intent.causeFact} intentGeneration=${intent.intentGeneration} " +
            "recoveryState=${intent.state}$lineage$extra"
    }
}
