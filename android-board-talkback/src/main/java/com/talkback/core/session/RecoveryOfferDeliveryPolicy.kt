package com.talkback.core.session

import com.talkback.core.util.RecoveryDeliveryFact
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * ADR-0035 PR2: Episode-owned bounded recovery-offer retransmission.
 * Independent of RECOVERY_WATCHDOG and obligationDeadline.
 */
internal class RecoveryOfferDeliveryPolicy(
    private val localModuleId: String,
    private val maxDeliveryAttempts: Int,
    private val deliveryRetryIntervalMs: Long,
    private val deliveryRetryMinGapMs: Long,
    private val clock: () -> Long,
    private val scheduler: ScheduledExecutorService,
    private val onLog: (String) -> Unit,
    private val onDispatchRecoveryOffer: (
        sessionId: String,
        remoteModuleId: String,
        offerLineageId: String,
        deliveryAttemptId: Long
    ) -> Boolean,
    private val canDispatchRecoverySignal: (sessionId: String, remoteModuleId: String) -> Boolean,
    private val onDeliveryExhausted: (
        sessionId: String,
        remoteModuleId: String,
        offerLineageId: String
    ) -> Unit = { _, _, _ -> }
) {
    private val deliveryRetryTimers = ConcurrentHashMap<ConferenceEdgeKey, ScheduledFuture<*>>()

    fun onOutboundDeliveryPending(
        record: EdgeRecoveryRecord,
        identity: RecoveryDeliveryFact.Identity,
        sessionId: String
    ) {
        record.recoveryOfferLineageId = identity.offerLineageId
        record.recoveryOfferDeliveryAttemptId = identity.deliveryAttemptId
        record.recoveryOfferLastDispatchAtMs = clock()
        record.recoveryOfferDeliveryPhase = when {
            identity.deliveryAttemptId > 1L -> RecoveryOfferDeliveryPhase.RETRY_PENDING
            else -> RecoveryOfferDeliveryPhase.PENDING
        }
        if (identity.deliveryAttemptId > 1L) {
            RecoveryDeliveryFact.emit(
                RecoveryDeliveryFact.Phase.DELIVERY_RETRY_PENDING,
                identity,
                sessionId
            )
        }
        scheduleDeliveryRetry(record)
    }

    fun onDeliveryConfirmed(record: EdgeRecoveryRecord, offerLineageId: String) {
        if (record.recoveryOfferLineageId != offerLineageId) return
        cancelDeliveryRetry(record.key)
        record.recoveryOfferDeliveryPhase = RecoveryOfferDeliveryPhase.CONFIRMED
    }

    fun onDeliveryHint(record: EdgeRecoveryRecord, trigger: String) {
        if (!record.recoveryOfferDeliveryPhase.isAwaitingAck()) return
        evaluateDeliveryRetry(record, trigger)
    }

    fun cancel(record: EdgeRecoveryRecord) {
        cancelDeliveryRetry(record.key)
        clearDeliveryState(record)
    }

    fun clearDeliveryState(record: EdgeRecoveryRecord) {
        record.recoveryOfferDeliveryPhase = RecoveryOfferDeliveryPhase.NONE
        record.recoveryOfferLineageId = null
        record.recoveryOfferDeliveryAttemptId = 0L
        record.recoveryOfferLastDispatchAtMs = null
    }

    internal fun evaluateDeliveryRetryForTest(record: EdgeRecoveryRecord, trigger: String) {
        evaluateDeliveryRetry(record, trigger)
    }

    private fun evaluateDeliveryRetry(record: EdgeRecoveryRecord, trigger: String) {
        if (!record.recoveryOfferDeliveryPhase.isAwaitingAck()) return
        val lineageId = record.recoveryOfferLineageId ?: return
        val key = record.key
        val nextAttempt = record.recoveryOfferDeliveryAttemptId + 1L
        if (nextAttempt > maxDeliveryAttempts) {
            enterDeliveryExhausted(record)
            return
        }
        val lastDispatch = record.recoveryOfferLastDispatchAtMs ?: 0L
        if (clock() - lastDispatch < deliveryRetryMinGapMs) {
            scheduleDeliveryRetry(record)
            return
        }
        if (!canDispatchRecoverySignal(key.sessionId, key.remoteModuleId)) {
            val identity = deliveryIdentity(record, nextAttempt)
            RecoveryDeliveryFact.emitRetryDeferred(
                identity,
                key.sessionId,
                "dispatch_gate"
            )
            scheduleDeliveryRetry(record)
            return
        }
        val dispatched = onDispatchRecoveryOffer(
            key.sessionId,
            key.remoteModuleId,
            lineageId,
            nextAttempt
        )
        if (!dispatched) {
            val identity = deliveryIdentity(record, nextAttempt)
            RecoveryDeliveryFact.emitRetryDeferred(
                identity,
                key.sessionId,
                "dispatch_failed"
            )
            scheduleDeliveryRetry(record)
        }
    }

    private fun enterDeliveryExhausted(record: EdgeRecoveryRecord) {
        cancelDeliveryRetry(record.key)
        val lineageId = record.recoveryOfferLineageId ?: return
        val identity = deliveryIdentity(record, record.recoveryOfferDeliveryAttemptId)
        record.recoveryOfferDeliveryPhase = RecoveryOfferDeliveryPhase.EXHAUSTED
        RecoveryDeliveryFact.emit(
            RecoveryDeliveryFact.Phase.DELIVERY_EXHAUSTED,
            identity,
            record.key.sessionId
        )
        onLog(
            "RECOVERY_WAITING session=${record.key.sessionId} edge=${record.key.remoteModuleId} " +
                "reason=DELIVERY_EXHAUSTED offerLineageId=$lineageId " +
                "deliveryAttempt=${record.recoveryOfferDeliveryAttemptId}"
        )
        onLog(
            "RECOVERY_DECISION session=${record.key.sessionId} edge=${record.key.remoteModuleId} " +
                "attempt=${record.recoveryAttemptId} obligationGen=${record.obligationGeneration} " +
                "decision=WAITING reason=DELIVERY_EXHAUSTED approved=true"
        )
        onDeliveryExhausted(record.key.sessionId, record.key.remoteModuleId, lineageId)
    }

    private fun scheduleDeliveryRetry(record: EdgeRecoveryRecord) {
        if (!record.recoveryOfferDeliveryPhase.isAwaitingAck()) return
        val key = record.key
        val lineageId = record.recoveryOfferLineageId
        val attemptId = record.recoveryOfferDeliveryAttemptId
        val obligationGen = record.obligationGeneration
        cancelDeliveryRetry(key)
        val future = scheduler.schedule({
            val current = edgesSnapshot(key) ?: return@schedule
            if (current.recoveryOfferLineageId != lineageId) return@schedule
            if (current.recoveryOfferDeliveryAttemptId != attemptId) return@schedule
            if (current.obligationGeneration != obligationGen) return@schedule
            if (!current.recoveryOfferDeliveryPhase.isAwaitingAck()) return@schedule
            evaluateDeliveryRetry(current, "delivery_retry_timer")
        }, deliveryRetryIntervalMs, TimeUnit.MILLISECONDS)
        deliveryRetryTimers[key] = future
    }

    private var edgesSnapshot: (ConferenceEdgeKey) -> EdgeRecoveryRecord? = { null }

    fun bindEdgesLookup(lookup: (ConferenceEdgeKey) -> EdgeRecoveryRecord?) {
        edgesSnapshot = lookup
    }

    private fun cancelDeliveryRetry(key: ConferenceEdgeKey) {
        deliveryRetryTimers.remove(key)?.cancel(false)
    }

    private fun deliveryIdentity(record: EdgeRecoveryRecord, deliveryAttemptId: Long): RecoveryDeliveryFact.Identity {
        return RecoveryDeliveryFact.Identity(
            offerLineageId = record.recoveryOfferLineageId ?: "NONE",
            recoveryAttemptId = record.recoveryAttemptId,
            obligationGeneration = record.obligationGeneration,
            deliveryAttemptId = deliveryAttemptId,
            from = localModuleId,
            to = record.key.remoteModuleId
        )
    }
}