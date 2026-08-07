package com.talkback.core.session

/**
 * ADR-X1: pure predicates for post-delivery control admission contract.
 * Delivery fact, conflict fact, and admission deadline — not phase promotion.
 */
internal object ControlAdmissionPredicate {

    fun isControlAdmitted(record: EdgeRecoveryRecord): Boolean = record.controlPlaneStarted()

    /**
     * DELIVERED_BUT_NOT_ADMITTED (ADR-X1 Fact 1 third state).
     */
    fun isDeliveredButNotAdmitted(record: EdgeRecoveryRecord): Boolean =
        record.reattachDeliveryState == ReattachDeliveryState.REMOTE_RECEIPT_ACKED &&
            !isControlAdmitted(record) &&
            !record.terminalAdmissionRejected

    fun isAdmissionPending(record: EdgeRecoveryRecord): Boolean =
        isDeliveredButNotAdmitted(record) && record.phase.isActivelyRecovering()

    fun hasUnresolvedNegotiationOwnerConflict(record: EdgeRecoveryRecord): Boolean =
        record.unresolvedNegotiationOwnerConflict

    fun shouldSuppressE2Shortcut(record: EdgeRecoveryRecord): Boolean =
        hasUnresolvedNegotiationOwnerConflict(record)

    fun admissionDeadlineExpired(
        record: EdgeRecoveryRecord,
        nowMs: Long,
        attemptBudgetMs: Long
    ): Boolean = nowMs >= record.recoveryStartedAtMs + attemptBudgetMs

    /**
     * Attempt watchdog may terminate only when one of the ADR-X1 terminal conditions holds.
     */
    fun isRecoveryAttemptTimeoutEligible(
        record: EdgeRecoveryRecord,
        nowMs: Long,
        attemptBudgetMs: Long
    ): Boolean =
        record.terminalAdmissionRejected ||
            record.explicitOwnershipResolutionFailure ||
            admissionDeadlineExpired(record, nowMs, attemptBudgetMs)
}
