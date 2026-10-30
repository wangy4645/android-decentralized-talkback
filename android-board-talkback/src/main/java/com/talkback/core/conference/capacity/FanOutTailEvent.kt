package com.talkback.core.conference.capacity

data class FanOutTailEvent(
    val slotIndex: Long,
    val schedLatenessNs: Long,
    val fanoutDurationNs: Long,
    val fanoutThreadCpuNs: Long,
    val legWallSumNs: Long,
    val maxLegIndex: Int,
    val maxLegDurationNs: Long,
    val legDurationsNs: LongArray,
    val slotDeadlineMiss: Boolean,
    val stallContext: FanOutStallContext,
) {
    val slotExecutionNs: Long
        get() = schedLatenessNs + fanoutDurationNs

    val fanoutNonCpuNs: Long
        get() = (fanoutDurationNs - fanoutThreadCpuNs).coerceAtLeast(0L)

    val interLegGapNs: Long
        get() = (fanoutDurationNs - legWallSumNs).coerceAtLeast(0L)

    val dominantLegFraction: Double
        get() =
            if (fanoutDurationNs <= 0L) {
                0.0
            } else {
                maxLegDurationNs.toDouble() / fanoutDurationNs.toDouble()
            }

    val schedDominant: Boolean
        get() =
            slotExecutionNs > 0L &&
                schedLatenessNs.toDouble() / slotExecutionNs.toDouble() >=
                    Gres3TailLatencyDiagnostics.SCHED_DOMINANCE_FRACTION
}
