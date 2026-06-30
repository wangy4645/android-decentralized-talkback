package com.talkback.core.grouphealth

/** Why a TopologySnapshot was emitted (ADR-0008). */
enum class TopologySnapshotReason {
    APP_START,
    MEMBERSHIP_CHANGED,
    PLANNER_SCHEDULED,
    MESH_OFFERED,
    ICE_STATE_CHANGED,
    RECONNECT,
    CONFERENCE_END,
    READINESS_CHANGED,
    PTT_BLOCKED,
    PERIODIC_BUILDING
}
