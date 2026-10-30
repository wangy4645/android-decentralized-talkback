package com.talkback.core.session

/**
 * Phase B L4 room-facing state (AUTH-B-2).
 * Distinct from per-participant [com.talkback.appprod.ui.EndpointStatus.DEGRADED].
 */
enum class ConferenceL4RoomState {
    ONLINE,
    DEGRADED,
    CONFERENCE_FAILED,
    NOT_ESTABLISHED,
}
