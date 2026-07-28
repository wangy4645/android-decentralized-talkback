package com.talkback.core.signaling.link

/** R28-L.1: transport-owned link qualification; not a recovery outcome. */
enum class LinkQualificationState {
    UNKNOWN,
    BOUND,
    RECEIVE_READY,
    BIDIRECTIONAL_READY,
    /** Stable: epoch failed qualification; repair permitted. */
    UNQUALIFIED,
    /** In-flight transport repair for current epoch. */
    QUALIFICATION_REPAIRING,
    /** Terminal until network_changed / socket_error / manual reconnect. */
    UNQUALIFIED_STABLE
}
