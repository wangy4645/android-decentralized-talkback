package com.talkback.core.session

/**
 * Aggregated channel state for PTT cold-start UX (orthogonal to [ChannelMode] / conference FSM).
 */
enum class ChannelReadiness {
    NO_SERVICE,
    DISCOVERING,
    /** Non-bootstrap node waiting for primary host to create GROUP mesh. */
    AWAITING_PRIMARY,
    DIRECTORY_SYNC,
    CONNECTING,
    READY,
    BLOCKED
}
