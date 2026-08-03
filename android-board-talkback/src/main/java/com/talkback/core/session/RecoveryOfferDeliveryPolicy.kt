package com.talkback.core.session

import com.talkback.core.util.RecoveryDeliveryFact
import com.talkback.core.util.RecoveryIngressObservation
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * ADR-0035 PR2: Episode-owned bounded recovery-offer retransmission.
 * D1 Slice-2: ABSENT → evaluateRetry → admission → dispatch/budget (2C).
 *
 * Budget consumes only on successful dispatch (INV-D1-015 / INV-D1-016).
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
    private val evaluateRecoveryAdmission: (
        sessionId: String,
        remoteModuleId: String
    ) -> PeerSignalingReachabilityProjection = { _, _ -> defaultRecoveryAdmissionProjection() },
    private val onDeliveryExhausted: (
        sessionId: String,
        remoteModuleId: String,
        offerLineageId: String
    ) -> Unit = { _, _, _ -> }
) {
    private val deliveryRetryTimers = ConcurrentHashMap<ConferenceEdgeKey, ScheduledFuture<*>>()
    private val absentEvaluatedKeys = ConcurrentHashMap.newKeySet<String>()

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
        clearAbsentDedupe(record.recoveryOfferLineageId)
    }

    fun onDeliveryHint(record: EdgeRecoveryRecord, trigger: String) {
        if (!record.recoveryOfferDeliveryPhase.isAwaitingAck()) return
        observeRetryHint(record, trigger)
    }

    fun onRemoteIngressAbsent(
        record: EdgeRecoveryRecord,
        identity: RecoveryDeliveryFact.Identity,
        sessionId: String?
    ) {
        if (record.recoveryOfferDeliveryPhase == RecoveryOfferDeliveryPhase.EXHAUSTED) return
        if (!record.recoveryOfferDeliveryPhase.isAwaitingAck()) return
        if (record.recoveryOfferLineageId != identity.offerLineageId) return
        if (record.recoveryOfferDeliveryAttemptId != identity.deliveryAttemptId) return
        val dedupeKey = absentDedupeKey(identity.offerLineageId, identity.deliveryAttemptId)
        if (!absentEvaluatedKeys.add(dedupeKey)) return
        evaluateRetry(record, identity, sessionId)
    }

    /**
     * Q5 supersede: old lineage closed; new lineage starts with a fresh delivery budget.
     */
    fun onLineageSuperseded(
        record: EdgeRecoveryRecord,
        newIdentity: RecoveryDeliveryFact.Identity,
        sessionId: String
    ) {
        val oldLineage = record.recoveryOfferLineageId
        cancelDeliveryRetry(record.key)
        clearAbsentDedupe(oldLineage)
        onOutboundDeliveryPending(record, newIdentity, sessionId)
    }

    fun cancel(record: EdgeRecoveryRecord) {
        cancelDeliveryRetry(record.key)
        clearDeliveryState(record)
    }

    /**
     * ADR-0022 §E.17: sole authority path for terminating a delivery lineage on attempt supersede.
     * Mutates lifecycle → [RecoveryOfferDeliveryPhase.SUPERSEDED], emits audit fact, closes observation.
     */
    fun supersedeLineage(record: EdgeRecoveryRecord, reason: String) {
        val lineageId = record.recoveryOfferLineageId ?: return
        if (record.recoveryOfferDeliveryPhase == RecoveryOfferDeliveryPhase.NONE) return
        if (record.recoveryOfferDeliveryPhase.isTerminal()) return

        cancelDeliveryRetry(record.key)
        clearAbsentDedupe(lineageId)
        val identity = deliveryIdentity(record, record.recoveryOfferDeliveryAttemptId)
        record.recoveryOfferDeliveryPhase = RecoveryOfferDeliveryPhase.SUPERSEDED
        RecoveryDeliveryFact.emitLineageSuperseded(identity, record.key.sessionId, reason)
        RecoveryIngressObservation.onLineageSuperseded(lineageId)
    }

    /** @deprecated Production supersede path must use [supersedeLineage]. Retained for cancel/test only. */
    @Deprecated("Use supersedeLineage for attempt supersede; NONE must not be a terminal state")
    fun clearDeliveryState(record: EdgeRecoveryRecord) {
        clearAbsentDedupe(record.recoveryOfferLineageId)
        record.recoveryOfferDeliveryPhase = RecoveryOfferDeliveryPhase.NONE
        record.recoveryOfferLineageId = null
        record.recoveryOfferDeliveryAttemptId = 0L
        record.recoveryOfferLastDispatchAtMs = null
    }

    internal fun evaluateDeliveryRetryForTest(record: EdgeRecoveryRecord, trigger: String) {
        evaluateDeliveryRetry(record, trigger)
    }

    private fun evaluateRetry(
        record: EdgeRecoveryRecord,
        identity: RecoveryDeliveryFact.Identity,
        sessionId: String?
    ) {
        onLog(
            "RECOVERY_DELIVERY_RETRY_EVALUATE session=${record.key.sessionId} " +
                "edge=${record.key.remoteModuleId} trigger=REMOTE_INGRESS_ABSENT " +
                "offerLineageId=${identity.offerLineageId} " +
                "deliveryAttemptId=${identity.deliveryAttemptId}" +
                sessionId?.takeIf { it.isNotBlank() }?.let { " session=$it" }.orEmpty()
        )
        val sid = sessionId?.takeIf { it.isNotBlank() } ?: record.key.sessionId
        val key = record.key
        val lineageId = record.recoveryOfferLineageId ?: return
        val nextAttempt = record.recoveryOfferDeliveryAttemptId + 1L

        if (nextAttempt > maxDeliveryAttempts) {
            enterDeliveryExhausted(record)
            return
        }

        val admission = checkAdmission(key.sessionId, key.remoteModuleId)
        if (!admission.dispatchNow) {
            // INV-D1-014 / INV-D1-016: no budget burn on admission block.
            RecoveryDeliveryFact.emitRetryDeferred(identity, sid, ADMISSION_NOT_READY_REASON)
            return
        }

        val admittedIdentity = deliveryIdentity(record, nextAttempt)
        RecoveryDeliveryFact.emitRetryAdmitted(admittedIdentity, sid)

        if (!canDispatchRecoverySignal(key.sessionId, key.remoteModuleId)) {
            RecoveryDeliveryFact.emitRetryDeferred(identity, sid, "dispatch_gate")
            return
        }

        val dispatched = onDispatchRecoveryOffer(
            key.sessionId,
            key.remoteModuleId,
            lineageId,
            nextAttempt
        )
        if (!dispatched) {
            // INV-D1-016: transport not-sent does not consume attempt.
            RecoveryDeliveryFact.emitRetryDeferred(admittedIdentity, sid, "dispatch_failed")
            return
        }
        // Budget consume = successful dispatch. Caller updates attempt via
        // onOutboundDeliveryPending(N+1); if it does not, policy applies locally.
        if (record.recoveryOfferDeliveryAttemptId == identity.deliveryAttemptId) {
            onOutboundDeliveryPending(record, admittedIdentity, sid)
        }
    }

    private fun checkAdmission(sessionId: String, remoteModuleId: String): RecoveryAdmissionDecision =
        evaluateRecoveryAdmission(sessionId, remoteModuleId).toRecoveryAdmissionDecision()

    private fun observeRetryHint(record: EdgeRecoveryRecord, trigger: String) {
        onLog(
            "RECOVERY_RETRY_HINT_OBSERVED session=${record.key.sessionId} " +
                "edge=${record.key.remoteModuleId} trigger=$trigger " +
                "offerLineageId=${record.recoveryOfferLineageId} " +
                "deliveryAttemptId=${record.recoveryOfferDeliveryAttemptId}"
        )
    }

    private fun observeRetryTimer(record: EdgeRecoveryRecord) {
        onLog(
            "RECOVERY_RETRY_TIMER_OBSERVED session=${record.key.sessionId} " +
                "edge=${record.key.remoteModuleId} trigger=delivery_retry_timer " +
                "offerLineageId=${record.recoveryOfferLineageId} " +
                "deliveryAttemptId=${record.recoveryOfferDeliveryAttemptId}"
        )
    }

    /** Legacy PR2 path retained for existing UT; production retry is ABSENT-only. */
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
        val admission = evaluateRecoveryAdmission(key.sessionId, key.remoteModuleId)
        if (admission.decision != AdmissionDecisionProjection.DISPATCH_NOW) {
            val identity = deliveryIdentity(record, nextAttempt)
            RecoveryDeliveryFact.emitRetryDeferred(
                identity,
                key.sessionId,
                admission.admissionRetryDeferReason()
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
        clearAbsentDedupe(lineageId)
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
            observeRetryTimer(current)
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

    private fun absentDedupeKey(offerLineageId: String, deliveryAttemptId: Long): String =
        "$offerLineageId:$deliveryAttemptId"

    private fun clearAbsentDedupe(offerLineageId: String?) {
        if (offerLineageId.isNullOrBlank()) return
        absentEvaluatedKeys.removeIf { it.startsWith("$offerLineageId:") }
    }

    private companion object {
        const val ADMISSION_NOT_READY_REASON = "ADMISSION_NOT_READY"
    }
}
