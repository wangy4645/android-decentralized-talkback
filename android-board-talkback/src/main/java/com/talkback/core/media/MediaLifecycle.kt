package com.talkback.core.media

/**
 * Observability-only media connectivity phase (ADR-0017 §9).
 * MUST NOT drive Transition state.
 */
enum class MediaLifecycle {
    IDLE,
    BOOTSTRAPPING,
    NEGOTIATING,
    PARTIAL_CONNECTED,
    CONNECTED,
    DEGRADED,
    FAILED
}
