package com.talkback.core.session

import com.talkback.core.model.ModuleId

/**
 * #180 — unordered pair identity for mesh edges (not directional).
 * [first] is lexicographically less than [second].
 */
data class PairwiseMeshEdgeKey(
    val first: String,
    val second: String
) {
    init {
        require(first < second) { "PairwiseMeshEdgeKey must be normalized: first < second" }
    }

    fun storageKey(): String = "$first|$second"

    fun involves(moduleId: ModuleId): Boolean =
        moduleId.value == first || moduleId.value == second

    fun peerOf(local: ModuleId): ModuleId? = when (local.value) {
        first -> ModuleId(second)
        second -> ModuleId(first)
        else -> null
    }

    companion object {
        fun of(a: ModuleId, b: ModuleId): PairwiseMeshEdgeKey {
            val (lo, hi) = if (a.value < b.value) a.value to b.value else b.value to a.value
            return PairwiseMeshEdgeKey(lo, hi)
        }
    }
}
