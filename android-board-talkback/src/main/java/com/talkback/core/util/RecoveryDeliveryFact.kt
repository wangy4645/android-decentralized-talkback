package com.talkback.core.util

import com.talkback.core.model.RecoveryHandlerOutcome

/** ADR-0035 PR1/PR2/PR4 - Recovery delivery phase facts (observation only). */
object RecoveryDeliveryFact {

    enum class Phase {
        REQUESTED,
        LOCAL_ACCEPTED,
        DELIVERY_PENDING,
        DELIVERY_RETRY_PENDING,
        DELIVERY_RETRY_DEFERRED,
        DELIVERY_EXHAUSTED,
        DELIVERY_CONFIRMED,
        DELIVERY_LINEAGE_SUPERSEDED
    }

    data class Identity(
        val offerLineageId: String,
        val recoveryAttemptId: Long,
        val obligationGeneration: Long,
        val deliveryAttemptId: Long,
        val from: String,
        val to: String
    )

    private var logSink: ((String) -> Unit)? = null
    private var ingressAbsentHandler: ((Identity, String?) -> Unit)? = null

    internal fun resetForTest(sink: ((String) -> Unit)? = null) {
        logSink = sink
        ingressAbsentHandler = null
    }

    internal fun bindIngressAbsentHandler(handler: ((Identity, String?) -> Unit)?) {
        ingressAbsentHandler = handler
    }

    private fun log(message: String) {
        val sink = logSink
        if (sink != null) {
            sink(message)
        } else {
            TalkbackLog.i(message)
        }
    }

    fun emit(phase: Phase, identity: Identity, sessionId: String? = null) {
        val tag = when (phase) {
            Phase.REQUESTED -> "RECOVERY_DELIVERY_REQUESTED"
            Phase.LOCAL_ACCEPTED -> "RECOVERY_DELIVERY_LOCAL_ACCEPTED"
            Phase.DELIVERY_PENDING -> "RECOVERY_DELIVERY_PENDING"
            Phase.DELIVERY_RETRY_PENDING -> "RECOVERY_DELIVERY_RETRY_PENDING"
            Phase.DELIVERY_RETRY_DEFERRED -> "RECOVERY_DELIVERY_RETRY_DEFERRED"
            Phase.DELIVERY_EXHAUSTED -> "RECOVERY_DELIVERY_EXHAUSTED"
            Phase.DELIVERY_CONFIRMED -> "RECOVERY_DELIVERY_CONFIRMED"
            Phase.DELIVERY_LINEAGE_SUPERSEDED -> "RECOVERY_DELIVERY_LINEAGE_SUPERSEDED"
        }
        val sb = identityFields(tag, identity)
        sessionId?.takeIf { it.isNotBlank() }?.let { sb.append(" session=").append(it) }
        log(sb.toString())
        when (phase) {
            Phase.LOCAL_ACCEPTED -> RecoveryIngressObservation.onLocalAccepted(identity, sessionId)
            Phase.DELIVERY_EXHAUSTED -> RecoveryIngressObservation.onDeliveryExhausted(identity, sessionId)
            Phase.REQUESTED,
            Phase.DELIVERY_PENDING,
            Phase.DELIVERY_RETRY_PENDING,
            Phase.DELIVERY_RETRY_DEFERRED,
            Phase.DELIVERY_CONFIRMED,
            Phase.DELIVERY_LINEAGE_SUPERSEDED -> Unit
        }
    }

    fun emitLineageSuperseded(
        identity: Identity,
        sessionId: String?,
        reason: String
    ) {
        val sb = identityFields("RECOVERY_DELIVERY_LINEAGE_SUPERSEDED", identity)
        sb.append(" reason=").append(reason)
        sessionId?.takeIf { it.isNotBlank() }?.let { sb.append(" session=").append(it) }
        log(sb.toString())
    }

    fun emitRemoteIngressObserved(identity: Identity, sessionId: String? = null) {
        val sb = identityFields("RECOVERY_REMOTE_INGRESS_OBSERVED", identity)
        sessionId?.takeIf { it.isNotBlank() }?.let { sb.append(" session=").append(it) }
        log(sb.toString())
    }

    fun emitRemoteIngressAbsent(
        identity: Identity,
        sessionId: String? = null,
        reason: String = "WINDOW_DEADLINE"
    ) {
        val sb = identityFields("RECOVERY_REMOTE_INGRESS_ABSENT", identity)
        sb.append(" reason=").append(reason)
        sessionId?.takeIf { it.isNotBlank() }?.let { sb.append(" session=").append(it) }
        log(sb.toString())
        ingressAbsentHandler?.invoke(identity, sessionId)
    }

    fun emitDeliveryConfirmed(
        identity: Identity,
        sessionId: String?,
        handlerOutcome: RecoveryHandlerOutcome
    ) {
        val sb = identityFields("RECOVERY_DELIVERY_CONFIRMED", identity)
        sb.append(" handlerOutcome=").append(handlerOutcome.name)
        sessionId?.takeIf { it.isNotBlank() }?.let { sb.append(" session=").append(it) }
        log(sb.toString())
        RecoveryIngressObservation.onDeliveryConfirmed(identity, sessionId)
    }

    fun emitRetryDeferred(identity: Identity, sessionId: String?, reason: String) {
        val sb = identityFields("RECOVERY_DELIVERY_RETRY_DEFERRED", identity)
        sb.append(" reason=").append(reason)
        sessionId?.takeIf { it.isNotBlank() }?.let { sb.append(" session=").append(it) }
        log(sb.toString())
    }

    /** D1 Slice-2B: retry opportunity + admission permission (no dispatch). */
    fun emitRetryAdmitted(identity: Identity, sessionId: String? = null) {
        val sb = identityFields("RECOVERY_DELIVERY_RETRY_ADMITTED", identity)
        sessionId?.takeIf { it.isNotBlank() }?.let { sb.append(" session=").append(it) }
        log(sb.toString())
    }

    fun emitHandlerRejected(
        remoteModuleId: String,
        reason: String,
        offerLineageId: String? = null,
        sessionId: String? = null,
        detail: String? = null
    ) {
        val sb = StringBuilder("RECOVERY_HANDLER_REJECTED")
        sb.append(" remote=").append(remoteModuleId)
        sb.append(" reason=").append(reason)
        offerLineageId?.takeIf { it.isNotBlank() }?.let { sb.append(" offerLineageId=").append(it) }
        sessionId?.takeIf { it.isNotBlank() }?.let { sb.append(" session=").append(it) }
        detail?.takeIf { it.isNotBlank() }?.let { sb.append(" detail=").append(it) }
        log(sb.toString())
    }

    fun emitHandlerAccepted(
        remoteModuleId: String,
        offerLineageId: String?,
        sessionId: String? = null
    ) {
        val sb = StringBuilder("RECOVERY_HANDLER_ACCEPTED")
        sb.append(" remote=").append(remoteModuleId)
        offerLineageId?.takeIf { it.isNotBlank() }?.let { sb.append(" offerLineageId=").append(it) }
        sessionId?.takeIf { it.isNotBlank() }?.let { sb.append(" session=").append(it) }
        log(sb.toString())
    }

    fun reattachReceived(identity: Identity, sessionId: String?) {
        val sb = StringBuilder("RECOVERY_REATTACH_RECEIVED")
        sb.append(" offerLineageId=").append(identity.offerLineageId)
        sb.append(" from=").append(identity.from)
        sb.append(" deliveryAttemptId=").append(identity.deliveryAttemptId)
        sessionId?.takeIf { it.isNotBlank() }?.let { sb.append(" session=").append(it) }
        log(sb.toString())
    }

    fun ackSent(identity: Identity, sessionId: String?, handlerOutcome: RecoveryHandlerOutcome) {
        val sb = identityFields("RECOVERY_REATTACH_ACK_SENT", identity)
        sb.append(" handlerOutcome=").append(handlerOutcome.name)
        sessionId?.takeIf { it.isNotBlank() }?.let { sb.append(" session=").append(it) }
        log(sb.toString())
    }

    fun ackReceived(
        identity: Identity,
        sessionId: String?,
        accepted: Boolean,
        detail: String? = null,
        handlerOutcome: RecoveryHandlerOutcome? = null
    ) {
        val sb = StringBuilder("RECOVERY_REATTACH_ACK_RECEIVED")
        sb.append(" offerLineageId=").append(identity.offerLineageId)
        sb.append(" from=").append(identity.from)
        sb.append(" deliveryAttemptId=").append(identity.deliveryAttemptId)
        sb.append(" accepted=").append(accepted)
        handlerOutcome?.let { sb.append(" handlerOutcome=").append(it.name) }
        detail?.let { sb.append(" detail=").append(it) }
        sessionId?.takeIf { it.isNotBlank() }?.let { sb.append(" session=").append(it) }
        log(sb.toString())
    }

    fun ackIgnored(identity: Identity, sessionId: String?, reason: String) {
        val sb = identityFields("RECOVERY_ACK_IGNORED", identity)
        sb.append(" reason=").append(reason)
        sessionId?.takeIf { it.isNotBlank() }?.let { sb.append(" session=").append(it) }
        log(sb.toString())
    }

    fun matchesAck(identity: Identity, ack: RecoveryReattachAckFields): Boolean {
        return identity.offerLineageId == ack.offerLineageId &&
            identity.obligationGeneration == ack.obligationGeneration &&
            identity.deliveryAttemptId == ack.deliveryAttemptId
    }

    private fun identityFields(tag: String, identity: Identity): StringBuilder {
        val sb = StringBuilder(tag)
        sb.append(" offerLineageId=").append(identity.offerLineageId)
        sb.append(" recoveryAttemptId=").append(identity.recoveryAttemptId)
        sb.append(" obligationGeneration=").append(identity.obligationGeneration)
        sb.append(" deliveryAttemptId=").append(identity.deliveryAttemptId)
        sb.append(" from=").append(identity.from)
        sb.append(" to=").append(identity.to)
        return sb
    }
}

data class RecoveryReattachAckFields(
    val offerLineageId: String,
    val recoveryAttemptId: Long,
    val obligationGeneration: Long,
    val deliveryAttemptId: Long,
    val handlerOutcome: RecoveryHandlerOutcome
)
