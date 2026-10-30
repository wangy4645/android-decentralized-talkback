package com.talkback.core.session

/**
 * ADR-0057 GIDR — delivery obligation vs outbound attempt lifecycle.
 *
 * Staleness is owned by [OutboundGroupInviteAttemptSupport]; reconciliation only consumes it.
 */
object GroupInviteDeliveryRecoverySupport {

    enum class ReconciliationGate {
        /** Active attempt within bounded window — suppress new wire. */
        SuppressActive,

        /** Peer acceptance evidence observed — suppress new wire. */
        SuppressSatisfied,

        /** No active attempt, or prior attempt stale — may dispatch via GIE. */
        PermitRetry,
    }

    enum class DeliveryPhase {
        NONE,
        ACTIVE,
        STALE_RETRY_ELIGIBLE,
        SATISFIED,
    }

    /**
     * Late-peer / mesh reconcile entry: expire stale attempts first, then classify.
     * Does not own time semantics beyond delegating to attempt lifecycle.
     */
    fun evaluateReconciliationGate(
        session: TalkbackSession,
        remoteModuleId: String,
        nowMs: Long = System.currentTimeMillis(),
    ): ReconciliationGate =
        when (deliveryPhase(session, remoteModuleId, nowMs)) {
            DeliveryPhase.ACTIVE -> ReconciliationGate.SuppressActive
            DeliveryPhase.SATISFIED -> ReconciliationGate.SuppressSatisfied
            DeliveryPhase.NONE,
            DeliveryPhase.STALE_RETRY_ELIGIBLE,
            -> ReconciliationGate.PermitRetry
        }

    fun deliveryPhase(
        session: TalkbackSession,
        remoteModuleId: String,
        nowMs: Long = System.currentTimeMillis(),
    ): DeliveryPhase {
        OutboundGroupInviteAttemptSupport.expireStaleAttempt(session, remoteModuleId, nowMs)
        val attempt = session.outboundGroupInviteAttemptsByRemoteModule[remoteModuleId]
        return when {
            OutboundGroupInviteAttemptSupport.isActive(attempt) -> DeliveryPhase.ACTIVE
            OutboundGroupInviteAttemptSupport.isDeliverySatisfied(attempt) -> DeliveryPhase.SATISFIED
            attempt?.terminalReason == OutboundGroupInviteAttemptSupport.TERMINAL_REASON_TIMEOUT ->
                DeliveryPhase.STALE_RETRY_ELIGIBLE
            attempt == null -> DeliveryPhase.NONE
            else -> DeliveryPhase.STALE_RETRY_ELIGIBLE
        }
    }

    fun isDeliveryRetryEligible(
        session: TalkbackSession,
        remoteModuleId: String,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean {
        if (!GroupBootstrapAdmissionSupport.peerAdmissionIncomplete(session, remoteModuleId)) {
            return false
        }
        return evaluateReconciliationGate(session, remoteModuleId, nowMs) ==
            ReconciliationGate.PermitRetry
    }
}
