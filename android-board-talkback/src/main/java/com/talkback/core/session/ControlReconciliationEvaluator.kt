package com.talkback.core.session

/** PR5-2b: Q6-2 control reconciliation predicate (ADR-0022). */
internal object ControlReconciliationEvaluator {

    fun evaluate(
        record: EdgeRecoveryRecord,
        membershipEpochConverged: Boolean,
        clock: () -> Long = { System.currentTimeMillis() }
    ): ControlReconciliationFact {
        val handshake = controlHandshakeCompleted(record)
        val sessionEpoch = sessionEpochMatched(record)
        return ControlReconciliationFact(
            controlHandshakeCompleted = handshake,
            sessionEpochMatched = sessionEpoch,
            membershipEpochConverged = membershipEpochConverged,
            computedAtMs = clock(),
            attemptId = record.recoveryAttemptId,
            obligationGeneration = record.obligationGeneration
        )
    }

    /** Recovery control handshake seam — control-plane boundary crossed (ADR-0022 R28-E). */
    internal fun controlHandshakeCompleted(record: EdgeRecoveryRecord): Boolean =
        record.controlPlaneStarted()

    internal fun sessionEpochMatched(record: EdgeRecoveryRecord): Boolean {
        val ctx = record.attemptContext
        if (ctx != null && ctx.attemptId != record.recoveryAttemptId) return false
        val outboundAttempt = record.outboundDispatchAttemptId
        if (outboundAttempt != null && outboundAttempt != record.recoveryAttemptId) return false
        val outboundGen = record.outboundDispatchObligationGeneration
        if (outboundGen != null && outboundGen != record.obligationGeneration) return false
        return true
    }
}