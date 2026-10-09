package com.talkback.core.session

import com.talkback.core.session.failure.ConferenceFailureParticipantProjection

/**
 * Canonical conference participant row for UI projection (ADR-0010 R44).
 */
data class ConferenceParticipantViewState(
    val key: String,
    val moduleId: String,
    val displayState: ConferenceParticipantDisplayState,
    val isLocal: Boolean,
    val countsTowardParticipantTotal: Boolean = true,
    /** Phase A L-participant failure overlay; null when no classified terminal applies. */
    val failureProjection: ConferenceFailureParticipantProjection? = null,
)
