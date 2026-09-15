package com.talkback.core.conference.session.integration.cutover

enum class AudibleOwnershipState {
    ANCHOR_ACTIVE,
    CUTOVER_ARMED,
    MULTICAST_ACTIVE,
}

enum class RollbackTrigger {
    MANUAL,
    PRODUCTION_AUDIOTRACK_ACQUIRE_FAILURE,
    REPLACEMENT_RUNTIME_FATAL_FAILURE,
    OWNERSHIP_INVARIANT_VIOLATION,
    /** Meeting/session end — release multicast without requiring Anchor reacquire. */
    SESSION_TEARDOWN,
}

enum class CutoverOutcome {
    APPLIED,
    REJECTED_INVALID_STATE,
    REJECTED_PREREQUISITE,
    REJECTED_PILOT_DISABLED,
    REJECTED_HANDOFF_FAILURE,
    REJECTED_INVARIANT_VIOLATION,
}
