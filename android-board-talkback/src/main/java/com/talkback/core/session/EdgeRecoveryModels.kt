package com.talkback.core.session

/**
 * Edge recovery models for ADR-0021 / #73 Conference Edge Recovery Lifecycle.
 */
data class ConferenceEdgeKey(
    val sessionId: String,
    val remoteModuleId: String
)

enum class EdgeRecoveryPhase {
    CONNECTED,
    DISCONNECTED_DEBOUNCING,
    RECOVERY_PENDING,
    REATTACH_REQUESTED,
    REATTACH_ACCEPTED,
    ICE_RESTARTING,
    RECOVERED,
    FAILED_MEDIA_RECOVERY,
    FAILED_IDENTITY_MISMATCH,
    FAILED_STALE_LINEAGE,
    FAILED_REQUIRES_USER_ACTION,
    CANCELLED;

    fun isActivelyRecovering(): Boolean = when (this) {
        DISCONNECTED_DEBOUNCING,
        RECOVERY_PENDING,
        REATTACH_REQUESTED,
        REATTACH_ACCEPTED,
        ICE_RESTARTING -> true
        else -> false
    }

    /** Terminal media-recovery failure retained for R24 Strategy A residency. */
    fun isFailedMediaRecovery(): Boolean = when (this) {
        FAILED_MEDIA_RECOVERY,
        FAILED_REQUIRES_USER_ACTION -> true
        else -> false
    }
}

data class EdgeRecoveryEligibility(
    val lifecycleEstablished: Boolean,
    val localJoined: Boolean,
    val remoteJoined: Boolean,
    val conferenceTerminated: Boolean
) {
    fun isEligible(): Boolean =
        lifecycleEstablished && localJoined && remoteJoined && !conferenceTerminated
}

/** Read-only attempt lineage for conference recovery ownership observation (ADR-0022). */
data class EdgeAttemptLineageRaw(
    val attemptId: Long,
    val attemptStartedAtMs: Long,
    val phase: EdgeRecoveryPhase,
    val mediaRestored: Boolean,
    val obligationOpen: Boolean,
    val pendingCompletion: Boolean,
    val obligationGeneration: Long = 0L,
    /** ADR-0040 PR-LIFE-2: prior attempt when attemptId incremented (retry/supersede). */
    val parentAttemptId: Long? = null,
    /** ADR-0040 PR-LIFE-2: same attemptId resumed after capability defer (not a retry). */
    val resumeFromDeferred: Boolean = false,
    /** Last capability-class defer trigger (e.g. CAPABILITY_UNAVAILABLE_AT_FIRE). */
    val deferTrigger: String? = null,
    val deferredReason: String? = null,
    /** Monotonic per-edge transition counter for duplicate-sink aggregation. */
    val transitionSeq: Long = 0L
)

/** E.18.2: whether membership epoch probe produced checked evidence vs unwired gap. */
internal enum class MembershipEpochProbeDisposition {
    CHECKED,
    UNWIRED
}

/** ADR-0036 RCA-4: precise recovery failure taxonomy (logs / terminal paths). */
enum class RecoveryFailureClass {
    MEDIA_PATH_FAILED,
    TRANSPORT_RECONNECT_FAILED,
    MEMBERSHIP_CONVERGENCE_TIMEOUT,
    CONTROL_RECONCILIATION_TIMEOUT,
    UNKNOWN_RECOVERY_TIMEOUT,
    EXPLICIT_ABORT
}

/** PR5-2b: Q6-2 control reconciliation snapshot on edge record. */
internal data class ControlReconciliationFact(
    val controlHandshakeCompleted: Boolean,
    val sessionEpochMatched: Boolean,
    val membershipEpochConverged: Boolean,
    val membershipProbeDisposition: MembershipEpochProbeDisposition = MembershipEpochProbeDisposition.CHECKED,
    val computedAtMs: Long,
    val attemptId: Long,
    val obligationGeneration: Long
) {
    val result: Boolean =
        controlHandshakeCompleted &&
            sessionEpochMatched &&
            membershipProbeDisposition == MembershipEpochProbeDisposition.CHECKED &&
            membershipEpochConverged

    fun mismatchReason(): String? = when {
        !controlHandshakeCompleted -> "CONTROL_HANDSHAKE_PENDING"
        !sessionEpochMatched -> "SESSION_EPOCH_MISMATCH"
        membershipProbeDisposition == MembershipEpochProbeDisposition.UNWIRED -> "MEMBERSHIP_AUTHORITY_UNWIRED"
        !membershipEpochConverged -> "MEMBERSHIP_EPOCH_MISMATCH"
        else -> null
    }

    fun isCurrentFor(record: EdgeRecoveryRecord): Boolean =
        attemptId == record.recoveryAttemptId &&
            obligationGeneration == record.obligationGeneration
}

data class EdgeRecoveryFacts(
    val recoveringRemoteModuleIds: Set<String> = emptySet(),
    val anyRecovering: Boolean = false,
    /** Remotes whose last attempt ended in FAILED_MEDIA_RECOVERY* (ADR-0021 R24-A). */
    val failedRemoteModuleIds: Set<String> = emptySet(),
    val anyFailedMediaRecovery: Boolean = false,
    /**
     * Media health advisory facts (ADR-0023 R29-C). MUST NOT participate in membership convergence.
     */
    val mediaUnavailableRemoteModuleIds: Set<String> = emptySet()
)

/**
 * Exclusive close set for Recovery Edge Obligation (ADR-0022 R28-H).
 * Prune eligibility is owned by ADR-0024 R29-E v2 ([isPruneEligible]).
 */
enum class ObligationCloseReason {
    RECOVERED,
    MEMBERSHIP_LEFT,
    CONFERENCE_TERMINATED,
    OBLIGATION_DEADLINE;

    /**
     * ADR-0024 R29-E v2: recovery close reasons do **not** alone authorize membership prune.
     * v1 `OBLIGATION_DEADLINE → isPruneEligible() → AUTHORITY_PRUNE` is deprecated.
     * Prune requires an explicit Membership Eviction decision (not yet implemented).
     */
    fun isPruneEligible(): Boolean = false
}

/** Media-action ownership sub-state on an attempt (ADR-0022 Appendix C / C-2). */
internal enum class MediaActionOwner {
    UNASSIGNED,
    PENDING,
    HOST_RESTART,
    PARTICIPANT_REATTACH,
    ABORTED;

    fun isAssigned(): Boolean = this != UNASSIGNED && this != PENDING

    fun logLabel(): String = when (this) {
        HOST_RESTART -> "HOST_RESTART"
        PARTICIPANT_REATTACH -> "PARTICIPANT_REATTACH"
        ABORTED -> "ABORTED"
        else -> name
    }
}

/** Closed enum — do not add SENT/DISPATCHING/COMPLETED (those live in [EdgeRecoveryPhase]). */
internal enum class MediaActionDisposition {
    UNASSIGNED,
    ACTIVE,
    DEFERRED,
    ABORTED
}

enum class DeferredReason {
    ROUTE_NOT_READY,
    AUTHORITY_NOT_READY,
    MEDIA_NOT_READY,
    /**
     * Negotiation Stabilization Gate umbrella disposition (INV-NEG-004).
     * Step A-1: finer block reason is logged as ICE_RESTART_GATE_BLOCKED
     * (ANSWERER_SETTLING | SIGNALING_NOT_STABLE) — not collapsed into this enum alone.
     */
    NEGOTIATION_SETTLING,
}

/**
 * ADR-0022 Q12 M-1 / INV-REC-026: deferred intent blocking domain.
 * Completion evidence must cover this domain before closeObligation may expire the intent.
 */
enum class DeferredIntentDomain {
    NEGOTIATION,
    MEDIA,
    TRANSPORT,
    CONTROL,
    ALL
}

internal fun DeferredReason.toDeferredIntentDomain(): DeferredIntentDomain = when (this) {
    DeferredReason.NEGOTIATION_SETTLING -> DeferredIntentDomain.NEGOTIATION
    DeferredReason.MEDIA_NOT_READY -> DeferredIntentDomain.MEDIA
    DeferredReason.ROUTE_NOT_READY -> DeferredIntentDomain.TRANSPORT
    DeferredReason.AUTHORITY_NOT_READY -> DeferredIntentDomain.CONTROL
}

enum class IceRestartGateBlockReason {
    /** Waiting Answerer transaction commit → capability recompute (P1). */
    ANSWERER_SETTLING,
    /** Waiting signaling STABLE → capability recompute (P2). */
    SIGNALING_NOT_STABLE,
    /**
     * Diagnostic split under [SIGNALING_NOT_STABLE]: local offer awaiting remote answer
     * (`HAVE_LOCAL_OFFER`). Logs/binding only — not a second capability owner (INV-NEG-020).
     */
    OFFER_AWAITING_ANSWER,
}

/** PR5-2c-C: distinguish HELD(negotiation) vs HELD(dispatch_not_ready). */
internal enum class DeferredIntentHoldReason {
    NEGOTIATION,
    DISPATCH
}

/** PR5-2c-C: drain entry — negotiation wakeup vs dispatch-readiness retry. */
internal enum class DeferredIntentDrainTrigger {
    NEGOTIATION_CAN_EXECUTE,
    DISPATCH_READINESS_RETRY
}

/** Step A-1 observation: Coordinator probe for Negotiation Stabilization Gate. */
data class IceRestartGateProbe(
    val executable: Boolean,
    val blockReason: IceRestartGateBlockReason? = null,
    val signalingState: String? = null,
    val localRole: String? = null
)

internal enum class WakeupSourceType {
    ROUTE_CONVERGED,
    PEER_DISCOVERED,
    AUTHORITY_REACHABLE,
    /**
     * B3: edge-local negotiation capability available (INV-NEG-011/012/014).
     * Sole Recovery wakeup for negotiation deferrals — not an audit event.
     */
    NEGOTIATION_CAN_EXECUTE,
    /**
     * Audit-only legacy token. MUST NOT be used as Recovery wakeup (INV-NEG-014).
     * Kept so old logs/scripts remain recognizable.
     */
    @Deprecated("Audit only; Recovery binds NEGOTIATION_CAN_EXECUTE")
    NEGOTIATION_RELEASED,
}

internal data class WakeupBinding(
    val sourceType: WakeupSourceType,
    val sourceKey: String
) {
    fun logLabel(): String = "${sourceType.name}/$sourceKey"

    /**
     * Appendix C-3.2 (C-12): whether an external fact trigger matches this deferred wakeup binding.
     */
    fun matchesTrigger(
        trigger: RecoveryReevaluateTrigger,
        sessionId: String,
        remoteModuleId: String
    ): Boolean {
        val edgeKey = edgeWakeupKey(sessionId, remoteModuleId)
        if (sourceKey != edgeKey && sourceKey != moduleWakeupKey(remoteModuleId)) return false
        return when (sourceType) {
            WakeupSourceType.ROUTE_CONVERGED -> when (trigger) {
                RecoveryReevaluateTrigger.ROUTE_CONVERGED,
                RecoveryReevaluateTrigger.PEER_DISCOVERED,
                RecoveryReevaluateTrigger.PEER_REACHABILITY_RESTORED,
                RecoveryReevaluateTrigger.REMOTE_MODULE_RECOVERED,
                RecoveryReevaluateTrigger.ICE_CHECKING,
                RecoveryReevaluateTrigger.ICE_RESTORED -> true
                else -> false
            }
            WakeupSourceType.PEER_DISCOVERED -> trigger == RecoveryReevaluateTrigger.PEER_DISCOVERED ||
                trigger == RecoveryReevaluateTrigger.REMOTE_MODULE_RECOVERED
            WakeupSourceType.AUTHORITY_REACHABLE ->
                trigger == RecoveryReevaluateTrigger.AUTHORITY_REACHABLE
            // Drain is via Coordinator → drainPendingIceRestart, not R28-G reevaluate.
            WakeupSourceType.NEGOTIATION_CAN_EXECUTE,
            WakeupSourceType.NEGOTIATION_RELEASED -> false
        }
    }
}

internal fun edgeWakeupKey(sessionId: String, remoteModuleId: String): String =
    "edge($sessionId,$remoteModuleId)"

internal fun moduleWakeupKey(moduleId: String): String = "module($moduleId)"

/** REATTACH control-plane delivery facts — orthogonal to obligation state (ADR-0022 Appendix D). */
/** ADR-0035 PR2: outbound recovery-offer delivery lifecycle (Episode-owned). */
enum class RecoveryOfferDeliveryPhase {
    NONE,
    PENDING,
    RETRY_PENDING,
    CONFIRMED,
    EXHAUSTED,
    /** ADR-0022 §E.17: lineage existed and was explicitly terminated (not adoption). */
    SUPERSEDED;

    fun isAwaitingAck(): Boolean = this == PENDING || this == RETRY_PENDING

    /** INV-REC-032: NONE is initial only; terminal states must stay distinguishable. */
    fun isTerminal(): Boolean = this == CONFIRMED || this == EXHAUSTED || this == SUPERSEDED
}

enum class ReattachDeliveryState {
    QUEUED,
    TRANSPORT_SENT,
    REMOTE_RECEIPT_ACKED,
    RECEIVED,
    ACCEPTED,
    REJECTED,
    DEFERRED
}

enum class InboundReattachLineageVerdict {
    ACCEPT,
    STALE_OBLIGATION_GENERATION,
    OBLIGATION_CLOSED
}

/** Inbound recovery-offer delivery identity (PR5-2c-Q1-7 / INV-PR52c-008). */
data class InboundReattachDeliveryIdentity(
    val offerLineageId: String,
    val obligationGeneration: Long,
    val deliveryAttemptId: Long,
    val from: String,
    val to: String
)

/** Outbound REATTACH reject reasons that require completion reevaluation (ADR-0022 R28-L INV-REC-007). */
enum class OutboundReattachRejectReason {
    OBLIGATION_CLOSED;

    companion object {
        fun fromPayload(payload: String): OutboundReattachRejectReason? =
            when {
                payload.equals(OBLIGATION_CLOSED.name, ignoreCase = true) -> OBLIGATION_CLOSED
                else -> null
            }
    }
}

data class ReattachDispatchLineage(
    val attemptId: Long,
    val obligationGeneration: Long
)

internal data class EdgeRecoveryRecord(
    val key: ConferenceEdgeKey,
    var phase: EdgeRecoveryPhase,
    var channelId: String,
    var recoveryAttemptId: Long,
    var recoveryStartedAtMs: Long,
    /** Appendix C: owner must be assigned before attempt deadline without silent FAILED. */
    var mediaActionOwner: MediaActionOwner = MediaActionOwner.UNASSIGNED,
    /** Appendix C-2: orthogonal to [mediaActionOwner] and [EdgeRecoveryPhase]. */
    var mediaActionDisposition: MediaActionDisposition = MediaActionDisposition.UNASSIGNED,
    var deferredReason: DeferredReason? = null,
    var wakeupBinding: WakeupBinding? = null,
    /** Step A-1: finer gate block when [deferredReason] is NEGOTIATION_SETTLING. */
    var deferredGateBlockReason: IceRestartGateBlockReason? = null,
    /**
     * Commit Seam Trace: stable id for one ICE-restart deferred intent (R1, R2, …).
     * Distinguishes successor/supersede intents that share edge+attempt+gen.
     */
    var iceRestartIntentId: String? = null,
    /**
     * INV-NEG-019: observation seq stamped at DEFER_ADMISSION baseline.
     * Drain may consume only capability events with seq strictly greater than this.
     */
    var deferAdmissionObservationSeq: Long? = null,
    /** PR5-2c-C: HELD(negotiation) vs HELD(dispatch_not_ready); null while waiting first drain. */
    var deferredIntentHoldReason: DeferredIntentHoldReason? = null,
    /** PR5-2c-C: audit counter for dispatch-readiness retries (same lineage). */
    var deferredIntentDrainRetryCount: Int = 0,
    /** True when current attempt crossed inbound [onRecoveryReattachAccepted] (C-1.1 handoff guard). */
    var recoveryViaInboundReattach: Boolean = false,
    var epochRefreshUsed: Boolean = false,
    var iceRestartIssued: Boolean = false,
    /**
     * Q14 C-3 / INV-NEG-016: wall-clock when bounded ICE restart was actually dispatched.
     * Completion evidence for RECOVERED must be observed after this instant.
     */
    var restartDispatchAtMs: Long? = null,
    /** Media-plane ICE restored fact for current attempt (ADR-0022 R28-E). */
    var mediaRestored: Boolean = false,
    /**
     * Observation time of the latest [mediaRestored]=true stamp (ICE CONNECTED / media fact).
     * Used with [restartDispatchAtMs] for post-dispatch freshness (Q14); bool is not cleared.
     */
    var mediaRestoredObservedAtMs: Long? = null,
    var initiatesReattach: Boolean = false,
    /** Failure episode id on this edge; independent of [recoveryAttemptId] (ADR-0022 P1). */
    var obligationGeneration: Long = 0L,
    /** Single-writer obligation facts (ADR-0022 R28-H.1). */
    var obligationOpenedAtMs: Long? = null,
    var obligationDeadlineAtMs: Long? = null,
    var obligationClosedAtMs: Long? = null,
    var obligationCloseReason: ObligationCloseReason? = null,
    var hasPendingCompletionDecision: Boolean = false,
    /** Delivery-plane state for outbound/inbound REATTACH (ADR-0022 Appendix D). */
    var reattachDeliveryState: ReattachDeliveryState = ReattachDeliveryState.QUEUED,
    /** Envelope nonce of the current outbound REATTACH dispatch. */
    var reattachNonce: String? = null,
    /** Lineage bound at last outbound REATTACH transport send (ADR-0022 R28-L reject guard). */
    var outboundDispatchAttemptId: Long? = null,
    var outboundDispatchObligationGeneration: Long? = null,
    /** ADR-0035 PR2: recovery offer delivery assurance on this edge. */
    var recoveryOfferDeliveryPhase: RecoveryOfferDeliveryPhase = RecoveryOfferDeliveryPhase.NONE,
    var recoveryOfferLineageId: String? = null,
    var recoveryOfferDeliveryAttemptId: Long = 0L,
    var recoveryOfferLastDispatchAtMs: Long? = null,
    /** ADR-0035 PR4: last confirmed handler outcome from RECOVERY_REATTACH_ACK. */
    var deliveryConfirmedOutcome: com.talkback.core.model.RecoveryHandlerOutcome? = null,
    /** PR5-1: attempt-scoped state owned by RecoveryAttemptOwner (orthogonal to episode phase). */
    var attemptContext: RecoveryAttemptContext? = null,
    /** PR5-2b: last emitted control reconciliation fact (ADR-0022 Q6-2). */
    var controlReconciliationFact: ControlReconciliationFact? = null,
    /** ADR-0037 Phase 3.2: canonical negotiation owner for this episode+edge. */
    var canonicalNegotiationOwnerModuleId: String? = null,
    /**
     * RNA-5 v2 / Gate 3C: single terminal writer guard — set when
     * [RECOVERY_NEGOTIATION_INTENT_TERMINAL] is emitted for the current intent episode.
     */
    var negotiationIntentTerminalEmitted: Boolean = false,
    var negotiationIntentTerminalState: String? = null,
    /**
     * Gate 3C-D: independent negotiation-intent budget deadline (dual-clock option C).
     * Orthogonal to attempt watchdog; does not enlarge [attemptBudgetMs].
     */
    var negotiationIntentDeadlineAtMs: Long? = null,
    /**
     * E16 ActivationEvidence: successor pathway emit guard (once per episode).
     * Set when [RECOVERY_SUCCESSOR_STARTED] is logged; not transport-ready.
     */
    var successorActivationEmitted: Boolean = false,
    /**
     * ADR-0040 PR-LIFE-1: attempt watchdog deferred for capability (no live timer).
     * Cleared when [RECOVERY_WATCHDOG_STARTED] successfully arms; resumed on L2/wakeup
     * after capability restore — hangup must not be the sole CLEAR path.
     */
    var attemptClockOwnershipDeferred: Boolean = false,
    /** ADR-0040 PR-LIFE-2: lineage — set on retry/supersede (attemptId++). */
    var parentAttemptId: Long? = null,
    /** ADR-0040 PR-LIFE-2: true when same attemptId resumes after capability defer. */
    var resumeFromDeferred: Boolean = false,
    /** ADR-0040 PR-LIFE-2: last capability defer trigger for audit (e.g. CAPABILITY_UNAVAILABLE_AT_FIRE). */
    var deferTrigger: String? = null,
    /** ADR-0040 PR-LIFE-2: last wakeup trigger observed on this attempt. */
    var lastWakeupTrigger: String? = null,
    /** ADR-0040 PR-LIFE-2: monotonic transition seq for duplicate-sink aggregation. */
    var lineageTransitionSeq: Long = 0L,
    /** Wall-clock when [attemptClockOwnershipDeferred] was set (diagnostic only). */
    var attemptClockOwnershipDeferredSinceMs: Long? = null,
    /** PR-LIFE-2-B: emit [RECOVERY_ATTEMPT_OWNERSHIP_LOST] at most once per attempt episode. */
    var ownershipLostDiagnosticEmitted: Boolean = false
) {
    /** True while this record owns an active recovery attempt (ADR-0022 P0.5). */
    fun hasActiveAttempt(): Boolean = phase.isActivelyRecovering()

    /** True once attempt crossed the control-plane boundary (ADR-0022 R28-E / Appendix D). */
    fun controlPlaneStarted(): Boolean = when (phase) {
        EdgeRecoveryPhase.REATTACH_ACCEPTED,
        EdgeRecoveryPhase.ICE_RESTARTING -> true
        else -> false
    }

    /**
     * Obligation open predicate (ADR-0022 R28-H):
     * active attempt or failed-media residency until exclusive close stamp
     * (including OBLIGATION_DEADLINE).
     */
    fun edgeObligationOpen(): Boolean {
        if (obligationClosedAtMs != null) return false
        return phase.isActivelyRecovering() || phase.isFailedMediaRecovery()
    }
}

/**
 * Connectivity event that reached the recovery controller (ADR-0021 R20).
 * Describes **what happened**, not why recovery was approved.
 */
internal enum class RecoveryDecisionTrigger {
    ICE_DISCONNECTED,
    ICE_FAILED,
    REATTACH_ACCEPTED,
    ICE_RESTART,
    SESSION_CANCELLED
}

/**
 * Policy-source classification for recovery (ADR-0021 R20 / addendum).
 * Connectivity-plane only — Membership intents (USER_REJOIN) MUST NOT appear here.
 */
enum class RecoveryReason {
    NETWORK_RECOVERY,
    HOST_REATTACH,
    ICE_FAILED,
    ICE_DISCONNECTED,
    SESSION_CANCELLED,
    /** Rejected Membership / non-connectivity attempt to enter Recovery. */
    NON_CONNECTIVITY,
    UNKNOWN
}

/** Who may start Recovery (ADR-0021 addendum Phase B). */
enum class RecoverySource {
    ICE_MONITOR,
    TRANSPORT_MONITOR,
    RECOVERY_TIMER,
    /** Illegal for production Recovery start — Membership / invite / user action. */
    JOIN_HANDLER,
    INVITE_HANDLER,
    USER_ACTION
}

internal enum class RecoveryTerminationReason {
    NETWORK_LOSS,
    USER_LEAVE,
    CONFERENCE_TERMINATED,
    NOT_ESTABLISHED,
    UNKNOWN
}

internal enum class RecoveryDecisionPolicy {
    NO_RECOVERY,
    REATTACH_THEN_ICE_RESTART,
    ICE_RESTART_ONLY
}
