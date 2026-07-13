package com.talkback.core.session

/**
 * Read-only Conference runtime availability projection (RO-M2 PR-2 / ADR-0020 뿯½4).
 * Drives meeting UI in PR-3; soak / diagnostics in PR-2.
 */
data class ConferenceRuntimeState(
    val phase: ConferenceRuntimePhase,
    /**
     * True when session-level or edge-level recovery is actively in progress
     * (ADR-0021 R16). Distinct from [conferenceDegraded] after FAILED_MEDIA_RECOVERY.
     */
    val mediaRecovering: Boolean = false,
    /**
     * Edge-level recovery in progress without promoting conference phase to RECOVERING
     * (ADR-0021 R15).
     */
    val edgeRecovering: Boolean = false,
    /**
     * Meeting is still live but connectivity is degraded (ADR-0021 R24 Strategy A).
     * True while edge recovering **or** after FAILED_MEDIA_RECOVERY residency.
     * Must not be conflated with [mediaRecovering] (active media repair).
     */
    val conferenceDegraded: Boolean = false,
    /** From [ConferenceParticipantProjector]; may coexist with [ConferenceRuntimePhase.ACTIVE]. */
    val awaitingAdditionalParticipants: Boolean = false,
    /** MEETING_START transition reached TERMINAL_READY (control plane). */
    val transitionTerminalReady: Boolean = false,
    /** Mesh ICE CONNECTED remote count (data plane). */
    val connectedRemoteMediaCount: Int = 0
)
