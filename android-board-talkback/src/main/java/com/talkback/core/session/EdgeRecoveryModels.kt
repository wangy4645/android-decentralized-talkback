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
    val obligationGeneration: Long = 0L
)

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

internal enum class DeferredReason {
    ROUTE_NOT_READY,
    AUTHORITY_NOT_READY,
    MEDIA_NOT_READY,
}

internal enum class WakeupSourceType {
    ROUTE_CONVERGED,
    PEER_DISCOVERED,
    AUTHORITY_REACHABLE,
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
                RecoveryReevaluateTrigger.REMOTE_MODULE_RECOVERED,
                RecoveryReevaluateTrigger.ICE_CHECKING,
                RecoveryReevaluateTrigger.ICE_RESTORED -> true
                else -> false
            }
            WakeupSourceType.PEER_DISCOVERED -> trigger == RecoveryReevaluateTrigger.PEER_DISCOVERED ||
                trigger == RecoveryReevaluateTrigger.REMOTE_MODULE_RECOVERED
            WakeupSourceType.AUTHORITY_REACHABLE ->
                trigger == RecoveryReevaluateTrigger.AUTHORITY_REACHABLE
        }
    }
}

internal fun edgeWakeupKey(sessionId: String, remoteModuleId: String): String =
    "edge($sessionId,$remoteModuleId)"

internal fun moduleWakeupKey(moduleId: String): String = "module($moduleId)"

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
    /** True when current attempt crossed inbound [onRecoveryReattachAccepted] (C-1.1 handoff guard). */
    var recoveryViaInboundReattach: Boolean = false,
    var epochRefreshUsed: Boolean = false,
    var iceRestartIssued: Boolean = false,
    /** Media-plane ICE restored fact for current attempt (ADR-0022 R28-E). */
    var mediaRestored: Boolean = false,
    var initiatesReattach: Boolean = false,
    /** Failure episode id on this edge; independent of [recoveryAttemptId] (ADR-0022 P1). */
    var obligationGeneration: Long = 0L,
    /** Single-writer obligation facts (ADR-0022 R28-H.1). */
    var obligationOpenedAtMs: Long? = null,
    var obligationDeadlineAtMs: Long? = null,
    var obligationClosedAtMs: Long? = null,
    var obligationCloseReason: ObligationCloseReason? = null,
    var hasPendingCompletionDecision: Boolean = false
) {
    /** True while this record owns an active recovery attempt (ADR-0022 P0.5). */
    fun hasActiveAttempt(): Boolean = phase.isActivelyRecovering()

    /** True once attempt crossed the control-plane boundary (ADR-0022 R28-E). */
    fun controlPlaneStarted(): Boolean = when (phase) {
        EdgeRecoveryPhase.REATTACH_REQUESTED,
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
