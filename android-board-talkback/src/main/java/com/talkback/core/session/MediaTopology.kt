package com.talkback.core.session

import com.talkback.core.media.MediaTopologyPolicy
import com.talkback.core.model.ModuleId

/**
 * Pluggable group media topology (mesh vs anchor relay).
 */
interface MediaTopology {
    val kind: GroupMediaTopology

    /** Module-level link targets for mesh completion (GROUP_JOIN) after invite accept. */
    fun joinTargets(
        localModuleId: ModuleId,
        initiatorModuleId: ModuleId,
        anchorModuleId: ModuleId,
        allMemberModuleIds: Set<ModuleId>
    ): List<ModuleId>

    /** Remote module IDs that must be ICE-connected for local transmit readiness. */
    fun transmitPeerIds(
        localModuleId: ModuleId,
        anchorModuleId: ModuleId,
        allMemberModuleIds: Set<ModuleId>
    ): Set<String>

    companion object {
        fun forMemberCount(memberCount: Int): MediaTopology =
            if (memberCount >= MediaTopologyPolicy.SFU_LITE_THRESHOLD_MODULES) {
                AnchorTopology
            } else {
                MeshTopology
            }

        fun forSession(
            topology: GroupMediaTopology,
            memberCount: Int
        ): MediaTopology = when (topology) {
            GroupMediaTopology.ANCHOR -> AnchorTopology
            GroupMediaTopology.MESH -> MeshTopology
        }
    }
}
