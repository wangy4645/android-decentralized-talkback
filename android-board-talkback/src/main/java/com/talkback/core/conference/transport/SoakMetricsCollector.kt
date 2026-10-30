package com.talkback.core.conference.transport

/**
 * Observation-only percentile collector for Slice 5b soak.
 * Not a capacity gate.
 */
class SoakMetricsCollector {
    private val decodeUs = ArrayList<Long>(8192)
    private val mixUs = ArrayList<Long>(8192)
    private val playoutBudgetUs = ArrayList<Long>(8192)
    private val writeIntervalUs = ArrayList<Long>(8192)
    private val topKSelectedCount = ArrayList<Int>(8192)
    private val liveDecoderCount = ArrayList<Int>(8192)
    private val underrunSamples = ArrayList<Long>(256)
    private val lockWaitUs = ArrayList<Long>(1024)
    private val lockHoldUs = ArrayList<Long>(1024)
    private val audioTrackWriteUs = ArrayList<Long>(8192)
    private var lastWriteWallMs: Long? = null
    private var emptyPcmTicks = 0L

    @Synchronized
    fun recordCycle(
        decodeDurationUs: Long,
        mixDurationUs: Long,
        playoutBudgetUsedUs: Long,
        topKCount: Int,
        liveDecoders: Int,
        underrunCount: Long,
        writeWallMs: Long = System.currentTimeMillis(),
    ) {
        decodeUs += decodeDurationUs
        mixUs += mixDurationUs
        playoutBudgetUs += playoutBudgetUsedUs
        topKSelectedCount += topKCount
        liveDecoderCount += liveDecoders
        underrunSamples += underrunCount
        lastWriteWallMs?.let { prev -> writeIntervalUs += (writeWallMs - prev) * 1000L }
        lastWriteWallMs = writeWallMs
    }

    @Synchronized
    fun recordEmptyPcmTick() {
        emptyPcmTicks += 1
    }

    @Synchronized
    fun recordAudioTrackWrite(writeDurationUs: Long) {
        if (writeDurationUs > 0L) audioTrackWriteUs += writeDurationUs
    }

    @Synchronized
    fun recordLockSample(waitUs: Long, holdUs: Long) {
        // Cap sample volume — observe contention, not a gate
        if (lockWaitUs.size < 2048) {
            lockWaitUs += waitUs
            lockHoldUs += holdUs
        }
    }

    @Synchronized
    fun snapshot(): Map<String, Any?> =
        mapOf(
            "sampleCount" to decodeUs.size,
            "decodeDurationUs" to percentileBlock(decodeUs),
            "mixDurationUs" to percentileBlock(mixUs),
            "playoutBudgetUsedUs" to percentileBlock(playoutBudgetUs),
            "playoutBudgetNear20msCount" to playoutBudgetUs.count { it >= 18_000L },
            "playoutBudgetOver20msCount" to playoutBudgetUs.count { it > 20_000L },
            "writeIntervalUs" to percentileBlock(writeIntervalUs),
            "writeIntervalOver30msCount" to writeIntervalUs.count { it > 30_000L },
            "emptyPcmTicks" to emptyPcmTicks,
            "pipelineLockUs" to
                mapOf(
                    "wait" to percentileBlock(lockWaitUs),
                    "hold" to percentileBlock(lockHoldUs),
                    "sampleCount" to lockWaitUs.size,
                    "scope" to PipelineLockPlayoutRefinement.FIX_NAME,
                ),
            "audioTrackWriteUs" to percentileBlock(audioTrackWriteUs),
            "audioTrackWriteOutsideLock" to true,
            "topKSelectedCount" to
                mapOf(
                    "min" to (topKSelectedCount.minOrNull() ?: 0),
                    "max" to (topKSelectedCount.maxOrNull() ?: 0),
                    "last" to (topKSelectedCount.lastOrNull() ?: 0),
                ),
            "liveDecoderCount" to
                mapOf(
                    "min" to (liveDecoderCount.minOrNull() ?: 0),
                    "max" to (liveDecoderCount.maxOrNull() ?: 0),
                    "last" to (liveDecoderCount.lastOrNull() ?: 0),
                ),
            "underrunCountFirst" to (underrunSamples.firstOrNull() ?: 0L),
            "underrunCountLast" to (underrunSamples.lastOrNull() ?: 0L),
            "underrunCountDelta" to
                ((underrunSamples.lastOrNull() ?: 0L) - (underrunSamples.firstOrNull() ?: 0L)),
            "underrunObserveOnly" to true,
        )

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
