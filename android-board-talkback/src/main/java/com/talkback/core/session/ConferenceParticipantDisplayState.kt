package com.talkback.core.session

/**
 * UI-facing conference participant presence (ADR-0010 R44).
 * Interpreted exclusively by [ConferenceParticipantProjector]; UI must not branch on invite/media.
 */
enum class ConferenceParticipantDisplayState {
    VISIBLE_LOCAL,
    VISIBLE_CONNECTING,
    VISIBLE_CONNECTED,
    VISIBLE_RECONNECTING,
    /** L1 EDGE_FAILED participant projection (Phase A — distinct from legacy media FAILED path). */
    VISIBLE_EDGE_FAILED,
    /** L2 DOMAIN_BLOCKED participant projection — not edge self-failure. */
    VISIBLE_DOMAIN_BLOCKED,
    /** Legacy media-state FAILED; prefer [VISIBLE_EDGE_FAILED] / [VISIBLE_DOMAIN_BLOCKED] when classified. */
    VISIBLE_FAILED
}
