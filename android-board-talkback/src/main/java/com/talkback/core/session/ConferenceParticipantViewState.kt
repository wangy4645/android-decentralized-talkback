package com.talkback.core.session

/**
 * Canonical conference participant row for UI projection (ADR-0010 R44).
 */
data class ConferenceParticipantViewState(
    val key: String,
    val moduleId: String,
    val displayState: ConferenceParticipantDisplayState,
    val isLocal: Boolean,
    val countsTowardParticipantTotal: Boolean = true
)
