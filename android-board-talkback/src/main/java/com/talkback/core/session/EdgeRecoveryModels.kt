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
    val anyRecovering: Boolean = false
)

internal data class EdgeRecoveryRecord(
    val key: ConferenceEdgeKey,
    var phase: EdgeRecoveryPhase,
    var channelId: String,
    var recoveryAttemptId: Long,
    var recoveryStartedAtMs: Long,
    var epochRefreshUsed: Boolean = false,
    var iceRestartIssued: Boolean = false,
    var initiatesReattach: Boolean = false
)

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
