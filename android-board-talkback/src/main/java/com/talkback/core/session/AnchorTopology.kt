package com.talkback.core.session

import com.talkback.core.model.ModuleId

/**
 * Star topology: each member maintains a single media link to the elected anchor.
 * Anchor forwards the floor holder's audio to all other members (see [ProgramAudioBus]).
 */
object AnchorTopology : MediaTopology {
    override val kind: GroupMediaTopology = GroupMediaTopology.ANCHOR

    override fun joinTargets(
        localModuleId: ModuleId,
        initiatorModuleId: ModuleId,
        anchorModuleId: ModuleId,
        allMemberModuleIds: Set<ModuleId>
    ): List<ModuleId> {
        if (localModuleId != anchorModuleId) return emptyList()
        // Bootstrap anchor already reaches members via GROUP_INVITE; avoid duplicate GROUP_JOIN glare.
        if (localModuleId == initiatorModuleId) return emptyList()
        return allMemberModuleIds
            .filter { it != localModuleId && it != initiatorModuleId }
            .sortedBy { it.value }
            .toList()
    }

    override fun transmitPeerIds(
        localModuleId: ModuleId,
        anchorModuleId: ModuleId,
        allMemberModuleIds: Set<ModuleId>
    ): Set<String> {
        if (localModuleId == anchorModuleId) {
            return allMemberModuleIds
                .filter { it != localModuleId }
                .map { it.value }
                .toSet()
        }
        return setOf(anchorModuleId.value)
    }
}
