package com.talkback.core.conference.transport

import com.talkback.core.conference.runtime.MediaJitterConstants

/**
 * Observation-only metrics for 10-source scaling:
 * jitter depth percentiles, non-TopK backlog growth, Top-K promotion latency / live-edge.
 */
class ReceiverScaling10SourceWatchCollector(
    private val sourceIds: List<String>,
) {
    data class PromotionEvent(
        val sourceIdentity: String,
        val loudAtMs: Long,
        val topKAtMs: Long,
        val promotionLatencyMs: Long,
        val firstPullAtMs: Long?,
        val promotionFirstPulledAgeMs: Long?,
        val liveEdgeOk: Boolean?,
        val promotionToFirstValidPlayoutMs: Long?,
    )

    private val depths = sourceIds.associateWith { ArrayList<Int>(256) }.toMutableMap()
    private val firstDepth = sourceIds.associateWith { null as Int? }.toMutableMap()
    private val lastDepth = sourceIds.associateWith { 0 }.toMutableMap()
    private val nonTopKMonotonicGrowthTicks = sourceIds.associateWith { 0L }.toMutableMap()
    private val nonTopKSampleTicks = sourceIds.associateWith { 0L }.toMutableMap()
    private val prevNonTopKDepth = sourceIds.associateWith { null as Int? }.toMutableMap()

    private val pendingLoudAtMs = mutableMapOf<String, Long>()
    private val awaitingFirstPull = mutableMapOf<String, Long>() // source -> topKAtMs
    private val promotions = ArrayList<PromotionEvent>(64)
    private var deadlineDiscards = 0L
    private var lastSampleWallMs = 0L

    @Synchronized
    fun maybeSample(
        nowMs: Long,
        depthBySource: Map<String, Int>,
        topKIds: Set<String>,
    ) {
        if (lastSampleWallMs != 0L &&
            nowMs - lastSampleWallMs < ReceiverScaling10SourceConstants.JITTER_SAMPLE_MS
        ) {
            return
        }
        lastSampleWallMs = nowMs
        for (id in sourceIds) {
            val depth = depthBySource[id] ?: 0
            val bucket = depths.getValue(id)
            if (bucket.size < 512) bucket += depth
            if (firstDepth[id] == null) firstDepth[id] = depth
            lastDepth[id] = depth
            if (id !in topKIds) {
                nonTopKSampleTicks[id] = (nonTopKSampleTicks[id] ?: 0L) + 1L
                val prev = prevNonTopKDepth[id]
                if (prev != null && depth > prev) {
                    nonTopKMonotonicGrowthTicks[id] =
                        (nonTopKMonotonicGrowthTicks[id] ?: 0L) + 1L
                }
                prevNonTopKDepth[id] = depth
            } else {
                prevNonTopKDepth[id] = null
            }
        }
    }

    @Synchronized
    fun onSyntheticLoud(sourceIdentity: String, nowMs: Long) {
        if (sourceIdentity !in pendingLoudAtMs && sourceIdentity !in awaitingFirstPull) {
            pendingLoudAtMs[sourceIdentity] = nowMs
        }
    }

    @Synchronized
    fun onTopKMembership(nowMs: Long, topKIds: Set<String>) {
        val entered = pendingLoudAtMs.keys.filter { it in topKIds }
        for (id in entered) {
            val loudAt = pendingLoudAtMs.remove(id) ?: continue
            awaitingFirstPull[id] = nowMs
            // provisional event; complete on first pull
            promotions +=
                PromotionEvent(
                    sourceIdentity = id,
                    loudAtMs = loudAt,
                    topKAtMs = nowMs,
                    promotionLatencyMs = nowMs - loudAt,
                    firstPullAtMs = null,
                    promotionFirstPulledAgeMs = null,
                    liveEdgeOk = null,
                    promotionToFirstValidPlayoutMs = null,
                )
        }
        // Clear pending if somehow already in TopK without loud (shouldn't matter)
        pendingLoudAtMs.keys.retainAll { it !in topKIds }
    }

    @Synchronized
    fun onFirstPullAfterPromotion(
        sourceIdentity: String,
        nowMs: Long,
        frameMediaTimeMs: Long,
    ) {
        val topKAt = awaitingFirstPull[sourceIdentity] ?: return
        val slotAgeMs = nowMs - frameMediaTimeMs
        val liveEdgeOk = slotAgeMs <= MediaJitterConstants.MAX_PLAYOUT_DELAY_MS
        if (!liveEdgeOk) {
            return
        }
        awaitingFirstPull.remove(sourceIdentity)
        val promotionToFirstValid = nowMs - topKAt
        val idx = promotions.indexOfLast { it.sourceIdentity == sourceIdentity && it.firstPullAtMs == null }
        if (idx >= 0) {
            val prev = promotions[idx]
            promotions[idx] =
                prev.copy(
                    firstPullAtMs = nowMs,
                    promotionFirstPulledAgeMs = slotAgeMs,
                    liveEdgeOk = true,
                    promotionToFirstValidPlayoutMs = promotionToFirstValid,
                )
        } else {
            promotions +=
                PromotionEvent(
                    sourceIdentity = sourceIdentity,
                    loudAtMs = topKAt,
                    topKAtMs = topKAt,
                    promotionLatencyMs = 0L,
                    firstPullAtMs = nowMs,
                    promotionFirstPulledAgeMs = slotAgeMs,
                    liveEdgeOk = true,
                    promotionToFirstValidPlayoutMs = promotionToFirstValid,
                )
        }
    }

    @Synchronized
    fun recordDeadlineDiscard() {
        deadlineDiscards += 1
    }

    @Synchronized
    fun snapshot(): Map<String, Any?> {
        val perSource =
            sourceIds.associateWith { id ->
                val samples = depths.getValue(id)
                val growthTicks = nonTopKMonotonicGrowthTicks[id] ?: 0L
                val sampleTicks = nonTopKSampleTicks[id] ?: 0L
                mapOf(
                    "start" to (firstDepth[id] ?: 0),
                    "end" to (lastDepth[id] ?: 0),
                    "max" to (samples.maxOrNull() ?: 0),
                    "p50" to percentile(samples, 0.50),
                    "p99" to percentile(samples, 0.99),
                    "sampleCount" to samples.size,
                    "nonTopKGrowthTicks" to growthTicks,
                    "nonTopKSampleTicks" to sampleTicks,
                    "nonTopKMostlyGrowing" to
                        (sampleTicks > 5 && growthTicks.toDouble() / sampleTicks >= 0.7),
                )
            }
        val completedPromotions = promotions.filter { it.firstPullAtMs != null }
        val liveEdgeOkCount = completedPromotions.size
        val liveEdgeFailCount = promotions.count { it.firstPullAtMs == null }
        val firstPullAges = completedPromotions.mapNotNull { it.promotionFirstPulledAgeMs }
        val validPlayoutLatencies =
            completedPromotions.mapNotNull { it.promotionToFirstValidPlayoutMs }
        return mapOf(
            "perSourceJitterDepth" to perSource,
            "nonTopKBacklog" to
                mapOf(
                    "sourcesMostlyGrowing" to
                        perSource.count { (_, v) -> v["nonTopKMostlyGrowing"] == true },
                    "deadlineDiscardOrLatePullHints" to deadlineDiscards,
                    "note" to
                        "nonTopKMostlyGrowing = depth rose on ≥70% of non-TopK sample intervals",
                ),
            "topKPromotion" to
                mapOf(
                    "eventCount" to promotions.size,
                    "completedWithFirstPull" to completedPromotions.size,
                    "promotionLatencyMs" to
                        percentileBlock(promotions.map { it.promotionLatencyMs }),
                    "promotionFirstPulledAgeMs" to percentileBlock(firstPullAges),
                    "promotionToFirstValidPlayoutMs" to percentileBlock(validPlayoutLatencies),
                    "liveEdgeOkCount" to liveEdgeOkCount,
                    "liveEdgeFailCount" to liveEdgeFailCount,
                    "liveEdgeCriterionMs" to MediaJitterConstants.MAX_PLAYOUT_DELAY_MS,
                    "adjudicatorSchedulingToleranceMs" to ADJUDICATOR_SCHEDULING_TOLERANCE_MS,
                    "events" to
                        promotions.takeLast(16).map {
                            mapOf(
                                "source" to it.sourceIdentity,
                                "promotionLatencyMs" to it.promotionLatencyMs,
                                "promotionFirstPulledAgeMs" to it.promotionFirstPulledAgeMs,
                                "liveEdgeOk" to it.liveEdgeOk,
                                "promotionToFirstValidPlayoutMs" to it.promotionToFirstValidPlayoutMs,
                            )
                        },
                ),
        )
    }

    companion object {
        /** Harness adjudicator only — not a Profile 03 contract change. */
        const val ADJUDICATOR_SCHEDULING_TOLERANCE_MS: Long = 20L

        fun percentile(values: List<Int>, p: Double): Int {
            if (values.isEmpty()) return 0
            val sorted = values.sorted()
            val idx = ((sorted.size - 1) * p).toInt().coerceIn(0, sorted.lastIndex)
            return sorted[idx]
        }

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
