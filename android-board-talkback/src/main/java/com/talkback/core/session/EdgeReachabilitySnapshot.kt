package com.talkback.core.session

/**
 * Recovery-internal reachability facts for one edge (ADR-0022 R28-D).
 * Aggregated read-only; recovery controller MUST NOT write back to fact writers.
 * Not for UI — distinct from membership [ReachabilitySnapshot].
 */
data class EdgeReachabilitySnapshot(
    val linkReady: Boolean,
    val peerDiscovered: Boolean,
    val routeConverged: Boolean,
    val authorityReachable: Boolean
) {
    fun canDispatchRecoverySignal(): Boolean =
        linkReady && peerDiscovered && routeConverged

    fun canCompleteRecovery(): Boolean =
        canDispatchRecoverySignal() && authorityReachable

    fun dispatchWaitingReason(): RecoveryWaitingReason? = when {
        !linkReady -> RecoveryWaitingReason.WAITING_FOR_LINK
        !peerDiscovered -> RecoveryWaitingReason.WAITING_FOR_DISCOVERY
        !routeConverged -> RecoveryWaitingReason.WAITING_FOR_ROUTE
        else -> null
    }

    fun formatProbeFields(): String =
        "linkReady=$linkReady peerDiscovered=$peerDiscovered " +
            "routeConverged=$routeConverged authorityReachable=$authorityReachable"
}

enum class RecoveryWaitingReason {
    WAITING_FOR_LINK,
    WAITING_FOR_DISCOVERY,
    WAITING_FOR_ROUTE,
    WAITING_FOR_AUTHORITY,
    WAITING_FOR_INBOUND,
    WAITING_FOR_ACCEPT
}

enum class ReattachDispatchOutcome {
    SENT,
    /** Gate blocked: [EdgeReachabilitySnapshot.canDispatchRecoverySignal] false. */
    DEFERRED,
    SEND_FAILED,
    PEER_UNREACHABLE,
    SESSION_CANCELLED
}
