package com.talkback.core.session

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * RRA-005 Phase-2: thin REATTACH delivery-progress facade (truth adapter).
 *
 * Owns observation lifecycle only:
 *   CREATED/WAITING → EVIDENCE_OBTAINED | EXPIRED
 *
 * MUST NOT: retry(), fail(), complete(), or change ProgressWindow / Completion ownership.
 * Consumes ADR-0035 delivery-truth invariants without inheriting OfferDeliveryPolicy ownership.
 *
 * RCA-002: [onObservationExpired] is a fact seam only (EXPIRED ≠ RETRY_REQUIRED).
 * Controller may reacquire a **new** delivery opportunity separately when path evidence returns.
 */
internal class ReattachDeliveryProgressFacade(
    private val clock: () -> Long,
    private val scheduler: ScheduledExecutorService,
    private val onLog: (String) -> Unit,
    private val observationBudgetMs: () -> Long,
    private val onObservationExpired: ((EdgeRecoveryRecord) -> Unit)? = null
) {
    private val timers = ConcurrentHashMap<ConferenceEdgeKey, ScheduledFuture<*>>()

    fun arm(
        record: EdgeRecoveryRecord,
        edges: ConcurrentHashMap<ConferenceEdgeKey, EdgeRecoveryRecord>
    ) {
        val key = record.key
        cancel(key)
        val now = clock()
        val budgetMs = observationBudgetMs()
        val obligationDeadline = record.obligationDeadlineAtMs
        val deadlineAt = if (obligationDeadline != null) {
            minOf(now + budgetMs, obligationDeadline)
        } else {
            now + budgetMs
        }
        val delayMs = (deadlineAt - now).coerceAtLeast(0L)
        val attemptId = record.recoveryAttemptId
        val obligationGen = record.obligationGeneration
        record.reattachDeliveryProgressState = ReattachDeliveryProgressState.WAITING_REMOTE_EVIDENCE
        record.reattachDeliveryProgressStartedAtMs = now
        record.reattachDeliveryProgressDeadlineAtMs = deadlineAt
        onLog(
            "REATTACH_DELIVERY_PROGRESS_ARMED session=${key.sessionId} edge=${key.remoteModuleId} " +
                "attempt=$attemptId obligationGen=$obligationGen budgetMs=$budgetMs " +
                "deadlineAtMs=$deadlineAt nonce=${record.reattachNonce ?: "none"}"
        )
        val future = scheduler.schedule({
            val current = edges[key] ?: return@schedule
            if (current.recoveryAttemptId != attemptId) return@schedule
            if (current.obligationGeneration != obligationGen) return@schedule
            expireIfWaiting(current)
        }, delayMs, TimeUnit.MILLISECONDS)
        timers[key] = future
    }

    fun markEvidenceObtained(record: EdgeRecoveryRecord) {
        if (record.reattachDeliveryProgressState != ReattachDeliveryProgressState.WAITING_REMOTE_EVIDENCE) {
            return
        }
        cancel(record.key)
        record.reattachDeliveryProgressState = ReattachDeliveryProgressState.EVIDENCE_OBTAINED
        onLog(
            "REATTACH_DELIVERY_PROGRESS_OBTAINED session=${record.key.sessionId} " +
                "edge=${record.key.remoteModuleId} attempt=${record.recoveryAttemptId} " +
                "obligationGen=${record.obligationGeneration} nonce=${record.reattachNonce ?: "none"}"
        )
    }

    fun expireIfWaiting(record: EdgeRecoveryRecord) {
        if (record.reattachDeliveryProgressState != ReattachDeliveryProgressState.WAITING_REMOTE_EVIDENCE) {
            return
        }
        cancel(record.key)
        record.reattachDeliveryProgressState = ReattachDeliveryProgressState.EXPIRED
        onLog(
            "REATTACH_DELIVERY_PROGRESS_EXPIRED session=${record.key.sessionId} " +
                "edge=${record.key.remoteModuleId} attempt=${record.recoveryAttemptId} " +
                "obligationGen=${record.obligationGeneration} " +
                "deadlineAtMs=${record.reattachDeliveryProgressDeadlineAtMs}"
        )
        // Fact only — MUST NOT retry / fail-media / complete.
        onObservationExpired?.invoke(record)
    }

    fun clear(record: EdgeRecoveryRecord) {
        cancel(record.key)
        record.reattachDeliveryProgressState = ReattachDeliveryProgressState.NONE
        record.reattachDeliveryProgressStartedAtMs = null
        record.reattachDeliveryProgressDeadlineAtMs = null
    }

    fun cancel(key: ConferenceEdgeKey) {
        timers.remove(key)?.cancel(false)
    }

    fun clearAll() {
        timers.values.forEach { it.cancel(false) }
        timers.clear()
    }
}
