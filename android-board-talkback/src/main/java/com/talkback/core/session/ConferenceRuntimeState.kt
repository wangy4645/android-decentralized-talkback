package com.talkback.core.session

/**
 * Read-only Conference runtime availability projection (RO-M2 PR-2 / ADR-0021 R15).
 * Drives meeting UI in PR-3; soak / diagnostics in PR-2.
 */
data class ConferenceRuntimeState(
    val phase: ConferenceRuntimePhase,
    /** True when session-level or edge-level recovery is active (ADR-0021 R16). */
    val mediaRecovering: Boolean = false,
    /** Edge-level recovery without promoting conference phase to RECOVERING (ADR-0021 R15). */
    val edgeRecovering: Boolean = false,
    /** From [ConferenceParticipantProjector]; may coexist with [ConferenceRuntimePhase.ACTIVE]. */
    val awaitingAdditionalParticipants: Boolean = false,
    /** MEETING_START transition reached TERMINAL=READY (control plane). */
    val transitionTerminalReady: Boolean = false,
    /** Mesh ICE CONNECTED remote count (data plane). */
    val connectedRemoteMediaCount: Int = 0
)
