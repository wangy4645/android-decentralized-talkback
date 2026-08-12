package com.talkback.core.session

/**
 * ADR-0052 Phase 2 (PR-C1): projection of initial conference edge admission — not ICE/media readiness.
 */
enum class ConferenceAdmissionPhase {
    INVITED,
    ACCEPTING,
    NEGOTIATING,
    READY,
    FAILED,
    TERMINATED
}

enum class ConferenceAdmissionTransitionReason {
    INVITE_RECEIVED,
    USER_ACCEPT,
    CREATE_CONFERENCE_ENGINE,
    APPLY_REMOTE_OFFER,
    ANSWER_COMMITTED,
    ACCEPT_FAILED,
    SESSION_TERMINATED
}

data class ConferenceAdmissionKey(
    val sessionId: String,
    val peerId: String
)
