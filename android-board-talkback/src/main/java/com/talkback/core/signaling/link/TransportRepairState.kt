package com.talkback.core.signaling.link

/** R28-L.1.4: transport repair coordinator lifecycle; not visible to Recovery. */
enum class TransportRepairState {
    IDLE,
    REPAIR_REQUESTED,
    REPAIR_IN_PROGRESS,
    QUALIFICATION_WAIT,
    REPAIR_EXHAUSTED
}
