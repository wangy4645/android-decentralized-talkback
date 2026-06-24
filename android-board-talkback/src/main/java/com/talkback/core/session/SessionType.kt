package com.talkback.core.session

enum class SessionType {
    UNICAST,
    GROUP,
    /** Full-duplex mesh conference (no floor control). */
    CONFERENCE
}

fun SessionType.isMeshSession(): Boolean = this == SessionType.GROUP || this == SessionType.CONFERENCE

fun SessionType.usesFloorControl(): Boolean = this == SessionType.GROUP
