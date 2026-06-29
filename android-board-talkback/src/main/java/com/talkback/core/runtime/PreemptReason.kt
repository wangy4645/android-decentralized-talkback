package com.talkback.core.runtime

/** Module-local Activity stack reason; orthogonal to [com.talkback.core.model.EndpointPriority]. */
enum class PreemptReason {
    NONE,
    USER_INITIATED,
    UNICAST_PREEMPT,
    SYSTEM
}
