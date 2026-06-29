package com.talkback.core.session

import com.talkback.core.model.ModuleId

/**
 * Read-only reachability snapshot for a session. PR-SPLIT-1 seam: MembershipManager
 * replaces the implementation in PR-SPLIT-2 without changing conference callers.
 */
interface ReachabilityView {
    fun snapshot(sessionId: String): ReachabilitySnapshot
}

data class ReachabilitySnapshot(
    val online: Set<ModuleId>,
    val suspect: Set<ModuleId>,
    val evicted: Set<ModuleId>
) {
    companion object {
        val EMPTY = ReachabilitySnapshot(emptySet(), emptySet(), emptySet())
    }
}
