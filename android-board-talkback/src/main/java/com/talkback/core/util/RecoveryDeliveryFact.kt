package com.talkback.core.util

/**
 * ADR-0035 PR1/PR2 - Recovery delivery phase facts (observation only).
 *
 * Grep: RECOVERY_DELIVERY_
 */
object RecoveryDeliveryFact {

    enum class Phase {
        REQUESTED,
        LOCAL_ACCEPTED,
        DELIVERY_PENDING,
        DELIVERY_RETRY_PENDING,
        DELIVERY_RETRY_DEFERRED,
        DELIVERY_EXHAUSTED,
        DELIVERY_CONFIRMED
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

    internal fun resetForTest(sink: ((String) -> Unit)? = null) {
        logSink = sink
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
        }
        val sb = StringBuilder(tag)
        sb.append(" offerLineageId=").append(identity.offerLineageId)
        sb.append(" recoveryAttemptId=").append(identity.recoveryAttemptId)
        sb.append(" obligationGeneration=").append(identity.obligationGeneration)
        sb.append(" deliveryAttemptId=").append(identity.deliveryAttemptId)
        sb.append(" from=").append(identity.from)
        sb.append(" to=").append(identity.to)
        sessionId?.takeIf { it.isNotBlank() }?.let { sb.append(" session=").append(it) }
        log(sb.toString())
    }

    fun emitRetryDeferred(identity: Identity, sessionId: String?, reason: String) {
        val sb = StringBuilder("RECOVERY_DELIVERY_RETRY_DEFERRED")
        sb.append(" offerLineageId=").append(identity.offerLineageId)
        sb.append(" recoveryAttemptId=").append(identity.recoveryAttemptId)
        sb.append(" obligationGeneration=").append(identity.obligationGeneration)
        sb.append(" deliveryAttemptId=").append(identity.deliveryAttemptId)
        sb.append(" from=").append(identity.from)
        sb.append(" to=").append(identity.to)
        sb.append(" reason=").append(reason)
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

    fun ackSent(identity: Identity, sessionId: String?) {
        val sb = StringBuilder("RECOVERY_REATTACH_ACK_SENT")
        sb.append(" offerLineageId=").append(identity.offerLineageId)
        sb.append(" to=").append(identity.to)
        sb.append(" deliveryAttemptId=").append(identity.deliveryAttemptId)
        sessionId?.takeIf { it.isNotBlank() }?.let { sb.append(" session=").append(it) }
        log(sb.toString())
    }

    fun ackReceived(
        identity: Identity,
        sessionId: String?,
        accepted: Boolean,
        detail: String? = null
    ) {
        val sb = StringBuilder("RECOVERY_REATTACH_ACK_RECEIVED")
        sb.append(" offerLineageId=").append(identity.offerLineageId)
        sb.append(" from=").append(identity.from)
        sb.append(" deliveryAttemptId=").append(identity.deliveryAttemptId)
        sb.append(" accepted=").append(accepted)
        sessionId?.takeIf { it.isNotBlank() }?.let { sb.append(" session=").append(it) }
        detail?.let { sb.append(" detail=").append(it) }
        log(sb.toString())
    }

    fun matchesAck(identity: Identity, ack: RecoveryReattachAckFields): Boolean {
        return identity.offerLineageId == ack.offerLineageId &&
            identity.recoveryAttemptId == ack.recoveryAttemptId &&
            identity.obligationGeneration == ack.obligationGeneration &&
            identity.deliveryAttemptId == ack.deliveryAttemptId
    }
}

data class RecoveryReattachAckFields(
    val offerLineageId: String,
    val recoveryAttemptId: Long,
    val obligationGeneration: Long,
    val deliveryAttemptId: Long
)