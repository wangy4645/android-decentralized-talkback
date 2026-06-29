package com.talkback.core.session

/** Control-plane lifecycle for mesh sessions (ADR-0001). Distinct from media teardown. */
enum class SessionDisposition {
    ACTIVE,
    SUSPENDED,
    RESUMING,
    TERMINATING,
    TERMINATED,
    /** Floor authority yield; orthogonal to foreground suspend (ADR-0001). */
    YIELDED_TO_AUTHORITY,
    ABANDONED
}
