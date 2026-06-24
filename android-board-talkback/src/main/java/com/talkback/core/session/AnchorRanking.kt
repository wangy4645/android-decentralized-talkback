package com.talkback.core.session

import com.talkback.core.model.ModuleId

/**
 * Stable anchor election: charging pool > online tenure > battery > moduleId tie-break.
 * Score = onlineMinutes * 50 + charging * 30 + batteryPercent * 20 / 100
 */
object AnchorRanking {
    const val INITIAL_ANCHOR_EPOCH = 100L

    data class Roles(
        val primary: ModuleId,
        val backup: ModuleId?,
        val scores: Map<String, Long> = emptyMap()
    )

    fun score(
        health: AnchorHealthSnapshot?,
        nowMs: Long = System.currentTimeMillis()
    ): Long {
        val h = health ?: AnchorHealthSnapshot.unknown(nowMs)
        val onlineMs = if (h.onlineSinceMs > 0L) (nowMs - h.onlineSinceMs).coerceAtLeast(0L) else 0L
        val onlineMinutes = onlineMs / 60_000L
        val chargingBonus = if (h.charging) 30L else 0L
        val batteryPts = (h.batteryPercent.coerceIn(0, 100) * 20L) / 100L
        return onlineMinutes * 50L + chargingBonus + batteryPts
    }

    fun elect(
        members: Collection<ModuleId>,
        healthByModule: Map<String, AnchorHealthSnapshot>,
        nowMs: Long = System.currentTimeMillis()
    ): Roles? {
        if (members.isEmpty()) return null
        val scores = members.associate { member ->
            member.value to score(healthByModule[member.value], nowMs)
        }
        val ranked = members.sortedWith(
            compareByDescending<ModuleId> { scores[it.value] ?: 0L }
                .thenBy { it.value }
        )
        val primary = ranked.first()
        val backup = ranked.getOrNull(1)
        return Roles(primary, backup, scores)
    }

    /**
     * Bootstrap election: require HELLO health for every remote member before ranking.
     * Until directory is complete, fall back to min moduleId so only one node creates the mesh.
     */
    fun electForBootstrap(
        members: Collection<ModuleId>,
        localModuleId: ModuleId,
        healthByModule: Map<String, AnchorHealthSnapshot>,
        nowMs: Long = System.currentTimeMillis()
    ): Roles? {
        if (members.isEmpty()) return null
        val remotes = members.filter { it != localModuleId }
        val directoryReady = remotes.isNotEmpty() &&
            remotes.all { healthByModule.containsKey(it.value) }
        if (!directoryReady) {
            val ranked = members.sortedBy { it.value }
            return Roles(
                primary = ranked.first(),
                backup = ranked.getOrNull(1)
            )
        }
        return elect(members, healthByModule, nowMs)
    }

    fun electFromStrings(
        members: Collection<String>,
        healthByModule: Map<String, AnchorHealthSnapshot>,
        nowMs: Long = System.currentTimeMillis()
    ): Roles? = elect(members.map { ModuleId(it) }, healthByModule, nowMs)

    /** Higher epoch wins; equal epoch falls back to ranking between the two module ids. */
    fun resolveSplitBrain(
        leftModuleId: String,
        leftEpoch: Long,
        rightModuleId: String,
        rightEpoch: Long,
        healthByModule: Map<String, AnchorHealthSnapshot>,
        nowMs: Long = System.currentTimeMillis()
    ): String {
        if (leftEpoch != rightEpoch) {
            return if (leftEpoch > rightEpoch) leftModuleId else rightModuleId
        }
        val leftScore = score(healthByModule[leftModuleId], nowMs)
        val rightScore = score(healthByModule[rightModuleId], nowMs)
        if (leftScore != rightScore) {
            return if (leftScore > rightScore) leftModuleId else rightModuleId
        }
        return minOf(leftModuleId, rightModuleId)
    }
}
