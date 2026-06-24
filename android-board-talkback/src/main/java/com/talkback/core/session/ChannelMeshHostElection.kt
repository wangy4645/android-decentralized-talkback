package com.talkback.core.session

import com.talkback.core.model.ModuleId

/**
 * Deterministic channel mesh host via [AnchorRanking] (stable sort, not raw min moduleId).
 * Used for warmup / group-call initiation to avoid multi-host glare.
 */
object ChannelMeshHostElection {

    fun electHost(
        localModuleId: ModuleId,
        reachableModuleIds: Collection<String>,
        healthByModule: Map<String, AnchorHealthSnapshot> = emptyMap()
    ): ModuleId {
        val candidates = LinkedHashSet<String>()
        candidates.add(localModuleId.value)
        reachableModuleIds.forEach { candidates.add(it) }
        return AnchorRanking.electForBootstrap(
            members = candidates.map { ModuleId(it) },
            localModuleId = localModuleId,
            healthByModule = healthByModule
        )?.primary ?: ModuleId(candidates.min())
    }

    fun isLocalHost(
        localModuleId: ModuleId,
        reachableModuleIds: Collection<String>,
        healthByModule: Map<String, AnchorHealthSnapshot> = emptyMap()
    ): Boolean = electHost(localModuleId, reachableModuleIds, healthByModule) == localModuleId

    fun nextHost(members: Collection<String>, currentHost: String): ModuleId? {
        val sorted = members.sorted()
        val index = sorted.indexOf(currentHost)
        if (index < 0) return sorted.firstOrNull()?.let { ModuleId(it) }
        return sorted.getOrNull(index + 1)?.let { ModuleId(it) }
    }
}
