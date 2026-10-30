package com.talkback.core.session.failure

/** L2 DOMAIN_BLOCKED causeFact — not LEASE_BUSY (input only). */
enum class ConferenceFailureCauseFact {
    LEASE_HELD_BY_CAUSE,
    NATIVE_DOMAIN_OBSTRUCTED,
}

enum class ConferenceFailureCausePhase {
    HANGING_OBSERVED,
    EDGE_FAILED,
}
