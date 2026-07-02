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
    VISIBLE_FAILED
}
