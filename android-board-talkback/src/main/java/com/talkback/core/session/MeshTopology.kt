package com.talkback.core.session

import com.talkback.core.model.ModuleId

/** Full mesh via [GroupMeshPlanner] (module-level pairwise links). */
object MeshTopology : MediaTopology {
    override val kind: GroupMediaTopology = GroupMediaTopology.MESH

    override fun joinTargets(
        localModuleId: ModuleId,
        initiatorModuleId: ModuleId,
        anchorModuleId: ModuleId,
        allMemberModuleIds: Set<ModuleId>
    ): List<ModuleId> = GroupMeshPlanner.joinTargets(localModuleId, initiatorModuleId, allMemberModuleIds)

    override fun transmitPeerIds(
        localModuleId: ModuleId,
        anchorModuleId: ModuleId,
        allMemberModuleIds: Set<ModuleId>
    ): Set<String> = allMemberModuleIds
        .filter { it != localModuleId }
        .map { it.value }
        .toSet()
}
