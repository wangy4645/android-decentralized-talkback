package com.talkback.governance.transition

enum class TransitionTrigger {
    MEETING_END,
    MEETING_START,
    GROUP_BOOTSTRAP,
    IDENTITY_REBOUND,
    HOST_FAILOVER,
    REJOIN,
    NETWORK_CHANGE,
    UNICAST_SUSPEND_GROUP,
    UNICAST_RESUME_GROUP
}
