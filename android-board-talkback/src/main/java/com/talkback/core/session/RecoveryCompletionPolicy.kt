package com.talkback.core.session

/**
 * PR5-2: single writer for episode completion terminal mutation (ADR-0022 Q1-A).
 * evaluate() reuses frozen Q2 predicate via [CompletionObservationProjection].
 */
internal object RecoveryCompletionPolicy {

    interface MutationHost {
        fun currentRecord(key: ConferenceEdgeKey): EdgeRecoveryRecord?
        fun clock(): Long
        fun log(message: String)
        fun cancelDebounce(key: ConferenceEdgeKey)
        fun cancelWatchdog(key: ConferenceEdgeKey)
        fun cancelDeadline(key: ConferenceEdgeKey)
        fun logPhaseTransition(
            record: EdgeRecoveryRecord,
            oldPhase: EdgeRecoveryPhase,
            newPhase: EdgeRecoveryPhase,
            reason: String
        )
        fun expireDeferredIceRestartIntent(record: EdgeRecoveryRecord, reason: String)
        fun notifyAttemptLineageObservation(record: EdgeRecoveryRecord, reason: String)
        fun notifyChanged(sessionId: String)
        fun logObligationCloseRequested(
            record: EdgeRecoveryRecord,
            reason: ObligationCloseReason,
            closeEvidence: String?
        )
        fun onObligationEpisodeClosed(
            record: EdgeRecoveryRecord,
            reason: ObligationCloseReason
        ) {}
    }

    fun evaluate(
        record: EdgeRecoveryRecord,
        snapshot: EdgeReachabilitySnapshot,
        iceConnected: Boolean,
        mediaUnavailableAdvisory: Boolean,
        hasUncoveredDeferredIntent: Boolean
    ): CompletionObservationProjection.CompletionObservationResult =
        CompletionObservationProjection.project(
            record = record,
            snapshot = snapshot,
            iceConnected = iceConnected,
            mediaUnavailableAdvisory = mediaUnavailableAdvisory,
            hasUncoveredDeferredIntent = hasUncoveredDeferredIntent
        )

    fun logCompletionDecision(
        host: MutationHost,
        result: CompletionObservationProjection.CompletionObservationResult,
        trigger: RecoveryReevaluateTrigger?
    ) {
        val triggerPart = trigger?.let { " trigger=$it" } ?: ""
        host.log(
            "RECOVERY_COMPLETION_DECISION session=${result.sessionId} remote=${result.remoteModuleId} " +
                "attemptId=${result.attemptId} writer=CompletionPolicy candidate=${result.candidate} " +
                "reason=${result.waitingReason} deliveryConfirmed=${result.deliveryConfirmed} " +
                "iceConnected=${result.iceConnected} controlReconciled=${result.controlReconciled} " +
                "topologySatisfied=${result.topologySatisfied}$triggerPart"
        )
    }

    /**
     * @return true iff obligation closed as [ObligationCloseReason.RECOVERED]
     */
    fun markRecovered(
        host: MutationHost,
        record: EdgeRecoveryRecord,
        closeEvidence: String = "EDGE_RECOVERED"
    ): Boolean {
        val key = record.key
        val current = host.currentRecord(key) ?: return false
        if (
            record.recoveryAttemptId != current.recoveryAttemptId ||
            record.obligationGeneration != current.obligationGeneration
        ) {
            host.log(
                "IGNORE_STALE_TERMINAL_FACT session=${key.sessionId} remote=${key.remoteModuleId} " +
                    "factAttempt=${record.recoveryAttemptId} factGen=${record.obligationGeneration} " +
                    "currentAttempt=${current.recoveryAttemptId} currentGen=${current.obligationGeneration} " +
                    "evidence=$closeEvidence"
            )
            return false
        }
        if (current.obligationClosedAtMs != null) {
            host.log(
                "IGNORE_STALE_TERMINAL_FACT session=${key.sessionId} remote=${key.remoteModuleId} " +
                    "factAttempt=${record.recoveryAttemptId} factGen=${record.obligationGeneration} " +
                    "reason=obligation_already_closed closeReason=${current.obligationCloseReason} " +
                    "evidence=$closeEvidence"
            )
            return false
        }
        if (!canClose(record, ObligationCloseReason.RECOVERED, closeEvidence)) {
            val domain = record.deferredReason?.toDeferredIntentDomain()?.name
                ?: if (record.iceRestartIssued) "RESTART_FRESHNESS" else "NONE"
            host.log(
                "RECOVERY_COMPLETION_HELD session=${key.sessionId} remote=${key.remoteModuleId} " +
                    "attempt=${record.recoveryAttemptId} evidence=$closeEvidence domain=$domain " +
                    "phase=${record.phase} deferredReason=${record.deferredReason ?: "NONE"} " +
                    "iceRestartIssued=${record.iceRestartIssued} " +
                    "restartDispatchAtMs=${record.restartDispatchAtMs ?: "NONE"} " +
                    "mediaRestoredObservedAtMs=${record.mediaRestoredObservedAtMs ?: "NONE"}"
            )
            return false
        }
        host.cancelDebounce(key)
        host.cancelWatchdog(key)
        host.cancelDeadline(key)
        val oldPhase = record.phase
        record.phase = EdgeRecoveryPhase.RECOVERED
        host.logPhaseTransition(record, oldPhase, record.phase, "EDGE_RECOVERED")
        closeObligation(host, record, ObligationCloseReason.RECOVERED, closeEvidence)
        val durationMs = host.clock() - record.recoveryStartedAtMs
        host.log(
            "RECOVERY_EDGE_RECOVERED session=${key.sessionId} remote=${key.remoteModuleId} " +
                "attempt=${record.recoveryAttemptId} durationMs=$durationMs"
        )
        host.notifyAttemptLineageObservation(record, "edge_recovered")
        host.notifyChanged(key.sessionId)
        return true
    }

    fun closeObligation(
        host: MutationHost,
        record: EdgeRecoveryRecord,
        reason: ObligationCloseReason,
        closeEvidence: String? = null
    ) {
        if (record.obligationClosedAtMs != null) return
        if (!canClose(record, reason, closeEvidence)) {
            val domain = record.deferredReason?.toDeferredIntentDomain()?.name
                ?: if (record.iceRestartIssued) "RESTART_FRESHNESS" else "NONE"
            host.log(
                "RECOVERY_OBLIGATION_CLOSE_HELD session=${record.key.sessionId} " +
                    "remote=${record.key.remoteModuleId} attempt=${record.recoveryAttemptId} " +
                    "reason=$reason evidence=${closeEvidence ?: "NONE"} " +
                    "domain=$domain disposition=${record.mediaActionDisposition} " +
                    "deferredReason=${record.deferredReason ?: "NONE"} " +
                    "iceRestartIssued=${record.iceRestartIssued} " +
                    "restartDispatchAtMs=${record.restartDispatchAtMs ?: "NONE"} " +
                    "mediaRestoredObservedAtMs=${record.mediaRestoredObservedAtMs ?: "NONE"}"
            )
            return
        }
        host.logObligationCloseRequested(record, reason, closeEvidence)
        host.expireDeferredIceRestartIntent(record, "OBLIGATION_CLOSE:$reason")
        host.cancelDeadline(record.key)
        record.obligationClosedAtMs = host.clock()
        record.obligationCloseReason = reason
        record.hasPendingCompletionDecision = false
        host.log(
            "RECOVERY_OBLIGATION_CLOSED session=${record.key.sessionId} " +
                "remote=${record.key.remoteModuleId} reason=$reason"
        )
        host.onObligationEpisodeClosed(record, reason)
    }

  private fun canClose(
        record: EdgeRecoveryRecord,
        reason: ObligationCloseReason,
        closeEvidence: String?
    ): Boolean {
        if (isAllDomainObligationClose(reason)) return true
        if (hasDeferredMediaAction(record)) {
            val domain = record.deferredReason?.toDeferredIntentDomain()
                ?: DeferredIntentDomain.ALL
            if (!evidenceCoversDeferredDomain(closeEvidence, domain)) {
                return false
            }
        }
        if (
            reason == ObligationCloseReason.RECOVERED &&
            record.iceRestartIssued &&
            record.restartDispatchAtMs != null
        ) {
            return isPostDispatchRestartResolvedEvidence(record, closeEvidence)
        }
        return true
    }

    private fun isAllDomainObligationClose(reason: ObligationCloseReason): Boolean =
        reason == ObligationCloseReason.MEMBERSHIP_LEFT ||
            reason == ObligationCloseReason.CONFERENCE_TERMINATED ||
            reason == ObligationCloseReason.OBLIGATION_DEADLINE

    private fun evidenceCoversDeferredDomain(
        evidence: String?,
        domain: DeferredIntentDomain
    ): Boolean {
        if (evidence.isNullOrBlank()) return false
        return when (domain) {
            DeferredIntentDomain.NEGOTIATION -> false
            DeferredIntentDomain.MEDIA ->
                evidence == "ICE_CONNECTED" ||
                    evidence == "MEDIA_RESTORED" ||
                    evidence == "EDGE_RECOVERED"
            DeferredIntentDomain.TRANSPORT ->
                evidence == "ICE_CONNECTED" ||
                    evidence == "MEDIA_RESTORED" ||
                    evidence == "ROUTE_CONVERGED" ||
                    evidence == "EDGE_RECOVERED"
            DeferredIntentDomain.CONTROL ->
                evidence == "ROUTE_CONVERGED" ||
                    evidence.contains("AUTHORITY", ignoreCase = true) ||
                    evidence == "EDGE_RECOVERED"
            DeferredIntentDomain.ALL -> true
        }
    }

    private fun isPostDispatchRestartResolvedEvidence(
        record: EdgeRecoveryRecord,
        evidence: String?
    ): Boolean {
        val dispatchAt = record.restartDispatchAtMs ?: return false
        val observedAt = record.mediaRestoredObservedAtMs ?: return false
        if (observedAt <= dispatchAt) return false
        return evidence == "ICE_CONNECTED" ||
            evidence == "MEDIA_RESTORED" ||
            evidence == "EDGE_RECOVERED"
    }

    private fun hasDeferredMediaAction(record: EdgeRecoveryRecord): Boolean =
        record.deferredReason != null || record.iceRestartIntentId != null
}