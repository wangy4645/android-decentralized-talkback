package com.talkback.core.session

/**
 * Module-level reachability within a GROUP talkgroup roster.
 * Orthogonal to [InviteState] / [MediaState] and discovery dialability.
 */
enum class GroupMemberReachability {
    ONLINE,
    SUSPECT,
    EVICTED
}
