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

/** Recovery-domain actions permitted for an edge (ADR-0022 R28-G). */
enum class RecoveryAction {
    DISPATCH_REATTACH,
    COMPLETE_EDGE,
    ICE_RESTART
}

/**
 * Projection of [EdgeReachabilitySnapshot] for materiality detection (ADR-0022 R28-G).
 * Material transition ⇔ [permittedActions] or [waitingReason] changes.
 */
data class RecoveryCapabilitySignature(
    val permittedActions: Set<RecoveryAction> = emptySet(),
    val waitingReason: RecoveryWaitingReason? = null
) {
    fun isMaterialChangeFrom(previous: RecoveryCapabilitySignature?): Boolean {
        if (previous == null) return true
        return permittedActions != previous.permittedActions ||
            waitingReason != previous.waitingReason
    }

    /** Compact label for RECOVERY_REEVALUATE logs (not a raw action-set dump). */
    fun formatCapabilityLabel(): String = when {
        waitingReason != null && permittedActions.isEmpty() -> waitingReason.name
        permittedActions.contains(RecoveryAction.DISPATCH_REATTACH) -> "DISPATCH_REATTACH"
        permittedActions.contains(RecoveryAction.COMPLETE_EDGE) -> "COMPLETE_EDGE"
        permittedActions.contains(RecoveryAction.ICE_RESTART) -> "ICE_RESTART"
        else -> "NONE"
    }
}

/** Coordinator-side trigger for capability materiality (ADR-0022 R28-G). */
enum class RecoveryReevaluateTrigger {
    ROUTE_CONVERGED,
    ROUTE_LOST,
    LINK_READY,
    LINK_LOST,
    PEER_DISCOVERED,
    PEER_LOST,
    AUTHORITY_REACHABLE,
    AUTHORITY_LOST
}

/**
 * Projects recovery capability from reachability facts and edge role (ADR-0022 R28-G).
 * [controlPlaneStarted] — attempt crossed REATTACH_REQUESTED / REATTACH_ACCEPTED / ICE_RESTARTING.
 */
fun projectRecoveryCapabilitySignature(
    snapshot: EdgeReachabilitySnapshot,
    initiatesReattach: Boolean,
    controlPlaneStarted: Boolean
): RecoveryCapabilitySignature {
    if (!snapshot.canDispatchRecoverySignal()) {
        return RecoveryCapabilitySignature(
            permittedActions = emptySet(),
            waitingReason = snapshot.dispatchWaitingReason()
        )
    }
    if (initiatesReattach) {
        val actions = linkedSetOf(RecoveryAction.DISPATCH_REATTACH)
        if (snapshot.canCompleteRecovery()) {
            actions.add(RecoveryAction.COMPLETE_EDGE)
        }
        val waiting = if (!snapshot.authorityReachable) {
            RecoveryWaitingReason.WAITING_FOR_AUTHORITY
        } else {
            null
        }
        return RecoveryCapabilitySignature(actions, waiting)
    }
    if (controlPlaneStarted) {
        return RecoveryCapabilitySignature(
            permittedActions = setOf(RecoveryAction.ICE_RESTART),
            waitingReason = null
        )
    }
    return RecoveryCapabilitySignature(
        permittedActions = emptySet(),
        waitingReason = RecoveryWaitingReason.WAITING_FOR_INBOUND
    )
}
