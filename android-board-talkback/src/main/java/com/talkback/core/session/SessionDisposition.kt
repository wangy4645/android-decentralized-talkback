package com.talkback.core.session

/** Control-plane lifecycle for mesh sessions (distinct from media teardown). */
enum class SessionDisposition {
    ACTIVE,
    YIELDED_TO_AUTHORITY,
    ABANDONED,
    TERMINATED
}
