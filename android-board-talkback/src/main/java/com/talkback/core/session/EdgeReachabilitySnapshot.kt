package com.talkback.core.session

/**
 * Recovery-internal reachability facts for one edge (ADR-0022 R28-D; ADR-0032 R28-N).
 * Aggregated read-only; recovery controller MUST NOT write back to fact writers.
 * Not for UI — distinct from membership [ReachabilitySnapshot].
 *
 * Each field belongs to exactly one plane and may only be consumed by predicates
 * below that plane (ADR-0032 INV-REC-011):
 *
 * | field                  | plane     | consumers              |
 * |------------------------|-----------|------------------------|
 * | [linkReady]            | transport | dispatch, completion   |
 * | [peerDiscovered]       | discovery | dispatch, completion   |
 * | [peerSignalingReachable] | signaling | dispatch, completion |
 * | [mediaRouteConnected]  | media     | completion, materiality|
 * | [authorityReachable]   | authority | completion             |
 */
data class EdgeReachabilitySnapshot(
    /** Transport plane: channel readiness — NOT LinkQualificationState. */
    val linkReady: Boolean,
    /** Discovery plane: a dialable address for the peer exists. */
    val peerDiscovered: Boolean,
    /** Signaling plane: inbound signal from the peer within the staleness window. */
    val peerSignalingReachable: Boolean,
    /** Media plane: mesh ICE connected. MUST NOT gate recovery initiation. */
    val mediaRouteConnected: Boolean,
    /** Authority plane: conference authority reachable (self-authority is tautological). */
    val authorityReachable: Boolean
) {
    /**
     * Recovery initiation permission (ADR-0022 P0).
     * Does not require [mediaRouteConnected] — recovery actions exist to establish the route.
     */
    fun canAttemptRecovery(): Boolean =
        linkReady && peerDiscovered

    /**
     * Recovery action admission (ADR-0032 INV-REC-010).
     * Consumes transport, discovery and signaling facts only: the media route is what
     * recovery actions restore, so it MUST NOT be a prerequisite for dispatching them.
     */
    fun canDispatchRecoverySignal(): Boolean =
        linkReady && peerDiscovered && peerSignalingReachable

    /**
     * Completion is an observation of success rather than an action, so it MAY consume
     * the media plane (ADR-0032 § 5).
     */
    fun canCompleteRecovery(): Boolean =
        canDispatchRecoverySignal() && mediaRouteConnected && authorityReachable

    /** Why recovery initiation is blocked (link / peer discovery only). */
    fun attemptWaitingReason(): RecoveryWaitingReason? = when {
        !linkReady -> RecoveryWaitingReason.WAITING_FOR_LINK
        !peerDiscovered -> RecoveryWaitingReason.WAITING_FOR_DISCOVERY
        else -> null
    }

    /** Why action dispatch is blocked. Never reports a media-plane reason (INV-REC-012). */
    fun dispatchWaitingReason(): RecoveryWaitingReason? = when {
        !linkReady -> RecoveryWaitingReason.WAITING_FOR_LINK
        !peerDiscovered -> RecoveryWaitingReason.WAITING_FOR_DISCOVERY
        !peerSignalingReachable -> RecoveryWaitingReason.WAITING_FOR_PEER_SIGNALING
        else -> null
    }

    /** Why completion is blocked. Media- and authority-plane reasons only (INV-REC-012). */
    fun completionWaitingReason(): RecoveryWaitingReason? = when {
        dispatchWaitingReason() != null -> dispatchWaitingReason()
        !mediaRouteConnected -> RecoveryWaitingReason.WAITING_FOR_ROUTE
        !authorityReachable -> RecoveryWaitingReason.WAITING_FOR_AUTHORITY
        else -> null
    }

    fun formatProbeFields(): String =
        "linkReady=$linkReady peerDiscovered=$peerDiscovered " +
            "peerSignalingReachable=$peerSignalingReachable " +
            "mediaRouteConnected=$mediaRouteConnected authorityReachable=$authorityReachable"
}

enum class RecoveryWaitingReason {
    WAITING_FOR_LINK,
    WAITING_FOR_DISCOVERY,
    /** ADR-0032: no inbound signal from the peer; blocks dispatch. */
    WAITING_FOR_PEER_SIGNALING,
    /** Media convergence only — MUST NOT appear on a dispatch path (ADR-0032 INV-REC-012). */
    WAITING_FOR_ROUTE,
    WAITING_FOR_AUTHORITY,
    WAITING_FOR_INBOUND,
    WAITING_FOR_ACCEPT,
    /** PR3-1: admission projection LOW — no current-epoch inbound or exceeded module stale. */
    ADMISSION_CONFIDENCE_LOW,
    /** PR3-1: admission projection MEDIUM — inbound older than T_dispatch_fresh. */
    ADMISSION_CONFIDENCE_STALE,
}

enum class ReattachDispatchOutcome {
    SENT,
    /** Gate blocked: [EdgeReachabilitySnapshot.canAttemptRecovery] or dispatch admission false. */
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
    /** Known peer signaling path restored after moduleStaleMs silence (ADR-0022 Appendix M-B.1). */
    PEER_REACHABILITY_RESTORED,
    PEER_LOST,
    /** HELLO / module rediscovery (ADR-0022 Appendix C-3.2 C-13). */
    REMOTE_MODULE_RECOVERED,
    AUTHORITY_REACHABLE,
    AUTHORITY_LOST,
    /**
     * ADR-0036 Phase 2.4 Fix-D: authority digest observation changed.
     * Forces control reconciliation recompute without capability materiality gate.
     * Does NOT imply membership converged — only re-triggers the existing predicate.
     */
    DIGEST_REFRESH,
    /** ICE CONNECTED / equivalent media restoration (ADR-0022 R28-E / #83). */
    ICE_RESTORED,
    /** ICE CHECKING — early resurrection signal while obligation OPEN (ADR-0022). */
    ICE_CHECKING,
    /** ADR-0035 PR4: peer handler processed recovery offer (delivery CONFIRMED). */
    DELIVERY_CONFIRMED,
    /** ADR-X1: outbound REATTACH receipt acknowledged; admission reevaluation required. */
    REMOTE_RECEIPT_ACKED,
    /**
     * RCA-002: delivery observation closed without receipt; in-flight latch released.
     * Not RETRY_REQUIRED — evaluation may open a **new** delivery attempt only when
     * path/dispatch evidence allows (delivery opportunity reacquisition).
     */
    DELIVERY_OPPORTUNITY_REACQUIRED,
    /**
     * ADR-0054: first post-terminal observation that this edge can still dispatch.
     * Coordinator emits only when obligation is open, attempt is FAILED_MEDIA terminal,
     * and [EdgeReachabilitySnapshot.canDispatchRecoverySignal] is true.
     * Ordinary HELLO MUST NOT use this trigger.
     */
    POST_TERMINAL_DISPATCH_CAPABLE
}

/** ADR-0054 Q7 gate. Ordinary HELLO is not sufficient by itself. */
fun isPostTerminalDispatchCapableFact(
    obligationOpen: Boolean,
    failedMediaTerminal: Boolean,
    canDispatchRecoverySignal: Boolean
): Boolean = obligationOpen && failedMediaTerminal && canDispatchRecoverySignal

/**
 * Resurrection admission evidence riding the R28-G notify seam (ADR-0022 §13.2.4 C2).
 * Coordinator stamps [observedAtMs]; Controller MUST NOT invent it.
 * MUST only accompany [RecoveryReevaluateTrigger.REMOTE_MODULE_RECOVERED].
 */
data class RecoveryResurrectionEvidence(
    val kind: RecoveryReevaluateTrigger,
    val observedAtMs: Long
)

/** Result of admitting a Successor Obligation Episode (ADR-0022 §13.2.4). */
data class SuccessorObligationAdmission(
    val obligationGeneration: Long,
    val recoveryAttemptId: Long
)

/**
 * Projects recovery capability from reachability facts and edge role (ADR-0022 R28-G).
 * [controlPlaneStarted] — attempt crossed REATTACH_REQUESTED / REATTACH_ACCEPTED / ICE_RESTARTING.
 *
 * Both role branches admit actions from [EdgeReachabilitySnapshot.canDispatchRecoverySignal],
 * which excludes the media plane (ADR-0032 INV-REC-010). A host without a started control
 * plane waits for inbound reattach, never for the media route.
 */
fun projectRecoveryCapabilitySignature(
    snapshot: EdgeReachabilitySnapshot,
    initiatesReattach: Boolean,
    controlPlaneStarted: Boolean
): RecoveryCapabilitySignature {
    if (!snapshot.canAttemptRecovery()) {
        return RecoveryCapabilitySignature(
            permittedActions = emptySet(),
            waitingReason = snapshot.attemptWaitingReason()
        )
    }
    if (initiatesReattach) {
        if (!snapshot.canDispatchRecoverySignal()) {
            return RecoveryCapabilitySignature(
                permittedActions = emptySet(),
                waitingReason = snapshot.dispatchWaitingReason()
            )
        }
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
    if (!snapshot.canDispatchRecoverySignal()) {
        return RecoveryCapabilitySignature(
            permittedActions = emptySet(),
            waitingReason = snapshot.dispatchWaitingReason()
        )
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
