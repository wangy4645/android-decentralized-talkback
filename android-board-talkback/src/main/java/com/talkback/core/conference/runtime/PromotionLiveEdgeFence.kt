package com.talkback.core.conference.runtime

/**
 * Profile 03 — promotion transition live-edge fence.
 *
 * When a source enters Top-K after being non-selected, discard jitter frames already
 * past [MediaJitterConstants.MAX_PLAYOUT_DELAY_MS] relative to current playout time.
 * Does not clear the reorder window or jump to newest packet.
 */
object PromotionLiveEdgeFence {
    const val FIX_NAME = "PROMOTION_LIVE_EDGE_FENCE"
}

data class PromotionFenceBufferResult(
    val staleDiscarded: Int,
    /** Age of oldest retained frame at fence time, or null if buffer empty after fence. */
    val oldestRetainedAgeMs: Long?,
)

data class PromotionFenceEvent(
    val sourceIdentity: String,
    val staleFramesDiscarded: Int,
    val oldestRetainedAgeMs: Long?,
    val fencedAtMs: Long,
)

class PromotionLiveEdgeFenceMetrics {
    var promotionLiveEdgeFenceCount: Int = 0
        private set
    var promotionStaleFramesDiscarded: Long = 0
        private set
    private val oldestRetainedAgeSamples = ArrayList<Long>(64)

    @Synchronized
    fun record(event: PromotionFenceEvent) {
        promotionLiveEdgeFenceCount += 1
        promotionStaleFramesDiscarded += event.staleFramesDiscarded.toLong()
        event.oldestRetainedAgeMs?.let { oldestRetainedAgeSamples += it }
    }

    @Synchronized
    fun snapshot(): Map<String, Any?> {
        val ages = oldestRetainedAgeSamples.toList()
        return mapOf(
            "promotionLiveEdgeFenceCount" to promotionLiveEdgeFenceCount,
            "promotionStaleFramesDiscarded" to promotionStaleFramesDiscarded,
            "promotionOldestRetainedAgeMs" to percentileBlock(ages),
        )
    }

    private fun percentileBlock(values: List<Long>): Map<String, Long> =
        mapOf(
            "p50" to percentile(values, 0.50),
            "p95" to percentile(values, 0.95),
            "p99" to percentile(values, 0.99),
            "max" to (values.maxOrNull() ?: 0L),
            "count" to values.size.toLong(),
        )

    private fun percentile(values: List<Long>, p: Double): Long {
        if (values.isEmpty()) return 0L
        val sorted = values.sorted()
        val idx = ((sorted.size - 1) * p).toInt().coerceIn(0, sorted.lastIndex)
        return sorted[idx]
    }
}
