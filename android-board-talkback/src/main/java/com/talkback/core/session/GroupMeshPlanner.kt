package com.talkback.core.session

import com.talkback.core.model.ModuleId

/**
 * Plans module-level mesh links for group calls.
 * Lexicographically smaller moduleId is the SDP offerer for each pair.
 * Links to the initiator are established via GROUP_INVITE and skipped here.
 */
object GroupMeshPlanner {

    /**
     * Peers this node should GROUP_INVITE when bootstrapping or adding links.
     * Lexicographically smaller moduleId is the offerer for each pair (Perfect Negotiation).
     */
    fun inviteTargets(
        localModuleId: ModuleId,
        allMemberModuleIds: Set<ModuleId>
    ): List<ModuleId> =
        allMemberModuleIds
            .asSequence()
            .filter { it != localModuleId && localModuleId.value < it.value }
            .sortedBy { it.value }
            .toList()

    fun joinTargets(
        localModuleId: ModuleId,
        initiatorModuleId: ModuleId,
        allMemberModuleIds: Set<ModuleId>
    ): List<ModuleId> {
        val local = localModuleId.value
        val initiator = initiatorModuleId.value
        if (local == initiator) return emptyList()

        return allMemberModuleIds
            .asSequence()
            .filter { it.value != local }
            .filter { target ->
                val targetId = target.value
                targetId != initiator && local < targetId
            }
            .sortedBy { it.value }
            .toList()
    }
}
