package com.talkback.core.session

/**
 * ADR-0050 Option A — negotiation lease helpers (INV-1..3).
 * Pure episode/edge binding; does not transfer negotiation ownership.
 */
internal object NegotiationAdmissionLease {

    fun isEligibleMediaAction(owner: MediaActionOwner, obligationClosed: Boolean): Boolean {
        if (obligationClosed) return false
        return owner == MediaActionOwner.PENDING || owner == MediaActionOwner.HOST_RESTART
    }

    fun isValid(
        record: EdgeRecoveryRecord,
        nowMs: Long,
        onExpired: () -> Unit
    ): Boolean {
        val attemptId = record.negotiationLeaseAttemptId ?: return false
        val gen = record.negotiationLeaseObligationGeneration ?: return false
        if (attemptId != record.recoveryAttemptId) return false
        if (gen != record.obligationGeneration) return false
        val expiresAt = record.negotiationLeaseExpiresAtMs
        if (expiresAt != null && nowMs > expiresAt) {
            onExpired()
            clear(record)
            return false
        }
        return true
    }

    fun grant(record: EdgeRecoveryRecord, expiresAtMs: Long) {
        record.negotiationLeaseAttemptId = record.recoveryAttemptId
        record.negotiationLeaseObligationGeneration = record.obligationGeneration
        record.negotiationLeaseExpiresAtMs = expiresAtMs
    }

    fun clear(record: EdgeRecoveryRecord) {
        record.negotiationLeaseAttemptId = null
        record.negotiationLeaseObligationGeneration = null
        record.negotiationLeaseExpiresAtMs = null
    }
}
