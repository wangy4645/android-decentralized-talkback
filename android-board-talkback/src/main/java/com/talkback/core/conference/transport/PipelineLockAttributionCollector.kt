package com.talkback.core.conference.transport

/**
 * Observation-only attribution for [pipelineLock] hold time at 4-source scaling.
 * Not a capacity gate.
 */
class PipelineLockAttributionCollector {
    enum class Category {
        PLAYOUT_TOPK,
        PLAYOUT_JITTER_PULL,
        PLAYOUT_DECODE,
        PLAYOUT_MIX,
        PLAYOUT_AUDIOTRACK,
        NETWORK_ADMIT,
        NETWORK_RESYNC,
        SYNTHETIC_ADMIT,
        SYNTHETIC_RESYNC,
        LOCK_UNATTRIBUTED,
    }

    enum class EntryPoint {
        PLAYOUT_TICK,
        NETWORK_RECEIVE,
        SYNTHETIC_INJECT,
    }

    private val byCategory = Category.entries.associateWith { ArrayList<Long>(2048) }.toMutableMap()
    private val byEntryTotal = EntryPoint.entries.associateWith { ArrayList<Long>(2048) }.toMutableMap()
    private val byEntryAttributed = EntryPoint.entries.associateWith { ArrayList<Long>(2048) }.toMutableMap()

    @Synchronized
    fun recordCategory(category: Category, holdUs: Long) {
        if (holdUs <= 0L) return
        val bucket = byCategory.getValue(category)
        if (bucket.size < 8192) bucket += holdUs
    }

    @Synchronized
    fun recordEntry(
        entryPoint: EntryPoint,
        totalHoldUs: Long,
        attributedUs: Long,
    ) {
        if (totalHoldUs <= 0L) return
        val totalBucket = byEntryTotal.getValue(entryPoint)
        val attrBucket = byEntryAttributed.getValue(entryPoint)
        if (totalBucket.size < 4096) {
            totalBucket += totalHoldUs
            attrBucket += attributedUs.coerceAtMost(totalHoldUs)
            val unattributed = (totalHoldUs - attributedUs).coerceAtLeast(0L)
            if (unattributed > 0L) {
                recordCategory(Category.LOCK_UNATTRIBUTED, unattributed)
            }
        }
    }

    @Synchronized
    fun snapshot(): Map<String, Any?> {
        val categoryBlocks =
            Category.entries.associate { category ->
                category.name to percentileBlock(byCategory.getValue(category))
            }
        val entryBlocks =
            EntryPoint.entries.associate { entry ->
                entry.name to
                    mapOf(
                        "totalHoldUs" to percentileBlock(byEntryTotal.getValue(entry)),
                        "attributedUs" to percentileBlock(byEntryAttributed.getValue(entry)),
                        "sampleCount" to byEntryTotal.getValue(entry).size.toLong(),
                    )
            }
        val dominance = computeDominance(categoryBlocks)
        return mapOf(
            "observationOnly" to true,
            "capacityGates" to "NONE",
            "byCategoryHoldUs" to categoryBlocks,
            "byEntryPoint" to entryBlocks,
            "dominance" to dominance,
            "rollup" to computeRollup(categoryBlocks),
        )
    }

    private fun computeRollup(categoryBlocks: Map<String, Map<String, Long>>): Map<String, Any?> {
        fun sumP99(vararg names: Category): Long =
            names.sumOf { categoryBlocks[it.name]?.get("p99") ?: 0L }
        val playoutMediaInsideLockP99 =
            sumP99(
                Category.PLAYOUT_TOPK,
                Category.PLAYOUT_JITTER_PULL,
                Category.PLAYOUT_DECODE,
                Category.PLAYOUT_MIX,
            )
        val audioTrackOutsideLockP99 = categoryBlocks[Category.PLAYOUT_AUDIOTRACK.name]?.get("p99") ?: 0L
        val ingressP99 =
            sumP99(
                Category.NETWORK_ADMIT,
                Category.NETWORK_RESYNC,
                Category.SYNTHETIC_ADMIT,
                Category.SYNTHETIC_RESYNC,
            )
        val overheadP99 = categoryBlocks[Category.LOCK_UNATTRIBUTED.name]?.get("p99") ?: 0L
        return mapOf(
            "playoutMediaInsideLockP99Us" to playoutMediaInsideLockP99,
            "audioTrackOutsideLockP99Us" to audioTrackOutsideLockP99,
            "playoutMediaHoldP99Us" to (playoutMediaInsideLockP99 + audioTrackOutsideLockP99),
            "ingressHoldP99Us" to ingressP99,
            "unattributedHoldP99Us" to overheadP99,
            "playoutTickTotalHoldP99Us" to
                (byEntryTotal[EntryPoint.PLAYOUT_TICK]?.let { percentileBlock(it)["p99"] } ?: 0L),
            "networkReceiveTotalHoldP99Us" to
                (byEntryTotal[EntryPoint.NETWORK_RECEIVE]?.let { percentileBlock(it)["p99"] } ?: 0L),
            "syntheticInjectTotalHoldP99Us" to
                (byEntryTotal[EntryPoint.SYNTHETIC_INJECT]?.let { percentileBlock(it)["p99"] } ?: 0L),
        )
    }

    private fun computeDominance(categoryBlocks: Map<String, Map<String, Long>>): Map<String, Any?> {
        val ranked =
            categoryBlocks.entries
                .map { (name, block) -> name to (block["p99"] ?: 0L) }
                .sortedByDescending { it.second }
        val primary = ranked.firstOrNull()
        val secondary = ranked.getOrNull(1)
        val playoutMediaInsideLock =
            listOf(
                Category.PLAYOUT_TOPK.name,
                Category.PLAYOUT_JITTER_PULL.name,
                Category.PLAYOUT_DECODE.name,
                Category.PLAYOUT_MIX.name,
            )
        val audioTrackOutsideP99 = categoryBlocks[Category.PLAYOUT_AUDIOTRACK.name]?.get("p99") ?: 0L
        val playoutInsideP99 = playoutMediaInsideLock.sumOf { categoryBlocks[it]?.get("p99") ?: 0L }
        val ingress =
            listOf(
                Category.NETWORK_ADMIT.name,
                Category.NETWORK_RESYNC.name,
                Category.SYNTHETIC_ADMIT.name,
                Category.SYNTHETIC_RESYNC.name,
            )
        val ingressP99 = ingress.sumOf { categoryBlocks[it]?.get("p99") ?: 0L }
        val overheadP99 = categoryBlocks[Category.LOCK_UNATTRIBUTED.name]?.get("p99") ?: 0L
        val interpretation =
            when {
                playoutInsideP99 >= ingressP99 && playoutInsideP99 >= overheadP99 ->
                    "PRIMARY_PLAYOUT_MEDIA_WORK_INSIDE_LOCK"
                audioTrackOutsideP99 >= playoutInsideP99 && audioTrackOutsideP99 >= ingressP99 ->
                    "PRIMARY_AUDIOTRACK_OUTSIDE_LOCK"
                ingressP99 >= playoutInsideP99 && ingressP99 >= overheadP99 ->
                    "PRIMARY_INGRESS_ADMIT_CONTENTION"
                overheadP99 > playoutInsideP99 && overheadP99 > ingressP99 ->
                    "PRIMARY_LOCK_SCOPE_OVERHEAD"
                else -> "MIXED"
            }
        return mapOf(
            "primaryCategory" to (primary?.first ?: "NONE"),
            "primaryCategoryHoldP99Us" to (primary?.second ?: 0L),
            "secondaryCategory" to (secondary?.first ?: "NONE"),
            "secondaryCategoryHoldP99Us" to (secondary?.second ?: 0L),
            "interpretation" to interpretation,
            "rankedByCategoryP99" to ranked.map { mapOf("category" to it.first, "holdP99Us" to it.second) },
        )
    }

    companion object {
        fun percentile(values: List<Long>, p: Double): Long {
            if (values.isEmpty()) return 0L
            val sorted = values.sorted()
            val idx = ((sorted.size - 1) * p).toInt().coerceIn(0, sorted.lastIndex)
            return sorted[idx]
        }

        fun percentileBlock(values: List<Long>): Map<String, Long> =
            mapOf(
                "p50" to percentile(values, 0.50),
                "p95" to percentile(values, 0.95),
                "p99" to percentile(values, 0.99),
                "max" to (values.maxOrNull() ?: 0L),
                "count" to values.size.toLong(),
            )
    }
}
