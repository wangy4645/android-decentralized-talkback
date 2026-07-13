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
 * v1 prune-eligible set is owned by ADR-0024 R29-E ([isPruneEligible]).
 */
enum class ObligationCloseReason {
    RECOVERED,
    MEMBERSHIP_LEFT,
    CONFERENCE_TERMINATED,
    OBLIGATION_DEADLINE;

    /** ADR-0024 R29-E: v1 prune-eligible reasons only. */
    fun isPruneEligible(): Boolean = this == OBLIGATION_DEADLINE
}

internal data class EdgeRecoveryRecord(
    val key: ConferenceEdgeKey,
    var phase: EdgeRecoveryPhase,
    var channelId: String,
    var recoveryAttemptId: Long,
    var recoveryStartedAtMs: Long,
    var epochRefreshUsed: Boolean = false,
    var iceRestartIssued: Boolean = false,
    /** Media-plane ICE restored fact for current attempt (ADR-0022 R28-E). */
    var mediaRestored: Boolean = false,
    var initiatesReattach: Boolean = false,
    /** Single-writer obligation facts (ADR-0022 R28-H.1). */
    var obligationOpenedAtMs: Long? = null,
    var obligationDeadlineAtMs: Long? = null,
    var obligationClosedAtMs: Long? = null,
    var obligationCloseReason: ObligationCloseReason? = null,
    var hasPendingCompletionDecision: Boolean = false
) {
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
