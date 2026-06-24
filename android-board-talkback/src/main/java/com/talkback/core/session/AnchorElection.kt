package com.talkback.core.session

import com.talkback.core.model.ModuleId

/**
 * Deterministic anchor election for anchor-relay group media.
 */
object AnchorElection {

    fun anchor(members: Collection<ModuleId>): ModuleId? =
        members.minByOrNull { it.value }

    fun anchorModuleId(members: Collection<String>): String? =
        members.minOrNull()

    fun nextAnchor(members: Collection<ModuleId>, current: ModuleId): ModuleId? {
        val sorted = members.sortedBy { it.value }
        val index = sorted.indexOf(current)
        if (index < 0) return sorted.firstOrNull()
        return sorted.getOrNull(index + 1)
    }

    fun isAnchor(local: ModuleId, members: Collection<ModuleId>): Boolean =
        anchor(members) == local
}
