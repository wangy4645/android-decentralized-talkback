package com.talkback.core.conference.capacity

/**
 * H1d.4 fan-out tail stall attribution (diagnostic only; C3 unchanged).
 */
enum class Gres3FanoutTailAttributionClass {
    /** A — wall >> CPU or large inter-leg gaps → deschedule / blocking wait */
    FANOUT_INTERNAL_DESCHEDULE,
    /** B — one leg send() wall-time dominates */
    SINGLE_SEND_SYSCALL_BLOCK,
    /** C — multiple legs each contribute elevated delay */
    MULTI_LEG_ACCUMULATION,
    /** D — no tail events or inconclusive */
    NOT_ATTRIBUTED,
}

data class Gres3FanoutTailAttributionEvent(
    val slotIndex: Long,
    val attributionClass: Gres3FanoutTailAttributionClass,
    val fanoutWallNs: Long,
    val fanoutThreadCpuNs: Long,
    val legWallSumNs: Long,
    val interLegGapNs: Long,
    val maxLegIndex: Int,
    val maxLegDurationNs: Long,
)

data class Gres3H1d4AdjudicatorOutput(
    val primaryAttribution: Gres3FanoutTailAttributionClass,
    val tailEventAttributions: List<Gres3FanoutTailAttributionEvent>,
    val worstFanoutWallNs: Long,
    val worstFanoutThreadCpuNs: Long,
    val worstNonCpuNs: Long,
    val reasons: List<String>,
)

object Gres3FanoutTailAttributor {
    const val DESCHEDULE_NON_CPU_FRACTION: Double = 0.50
    const val INTER_LEG_GAP_FRACTION: Double = 0.30
    const val SINGLE_LEG_BLOCK_FRACTION: Double = 0.80
    const val MULTI_LEG_WALL_COVERAGE_FRACTION: Double = 0.85
    const val ELEVATED_LEG_NS: Long = 1_000_000L
    const val MULTI_LEG_ELEVATED_MIN_COUNT: Int = 3

    fun classifyEvent(event: FanOutTailEvent): Gres3FanoutTailAttributionEvent {
        val wall = event.fanoutDurationNs
        val cpu = event.fanoutThreadCpuNs
        val legSum = event.legWallSumNs
        val interLegGap = (wall - legSum).coerceAtLeast(0L)
        val nonCpu = (wall - cpu).coerceAtLeast(0L)

        val attribution =
            when {
                wall > 0L &&
                    (
                        nonCpu.toDouble() / wall.toDouble() >= DESCHEDULE_NON_CPU_FRACTION ||
                            interLegGap.toDouble() / wall.toDouble() >= INTER_LEG_GAP_FRACTION
                    ) -> Gres3FanoutTailAttributionClass.FANOUT_INTERNAL_DESCHEDULE
                wall > 0L &&
                    event.maxLegDurationNs.toDouble() / wall.toDouble() >= SINGLE_LEG_BLOCK_FRACTION ->
                    Gres3FanoutTailAttributionClass.SINGLE_SEND_SYSCALL_BLOCK
                legSum > 0L &&
                    legSum.toDouble() / wall.toDouble() >= MULTI_LEG_WALL_COVERAGE_FRACTION &&
                    countElevatedLegs(event.legDurationsNs) >= MULTI_LEG_ELEVATED_MIN_COUNT ->
                    Gres3FanoutTailAttributionClass.MULTI_LEG_ACCUMULATION
                else -> Gres3FanoutTailAttributionClass.NOT_ATTRIBUTED
            }

        return Gres3FanoutTailAttributionEvent(
            slotIndex = event.slotIndex,
            attributionClass = attribution,
            fanoutWallNs = wall,
            fanoutThreadCpuNs = cpu,
            legWallSumNs = legSum,
            interLegGapNs = interLegGap,
            maxLegIndex = event.maxLegIndex,
            maxLegDurationNs = event.maxLegDurationNs,
        )
    }

    fun adjudicate(tailEvents: List<FanOutTailEvent>): Gres3H1d4AdjudicatorOutput {
        if (tailEvents.isEmpty()) {
            return Gres3H1d4AdjudicatorOutput(
                primaryAttribution = Gres3FanoutTailAttributionClass.NOT_ATTRIBUTED,
                tailEventAttributions = emptyList(),
                worstFanoutWallNs = 0L,
                worstFanoutThreadCpuNs = 0L,
                worstNonCpuNs = 0L,
                reasons = listOf("no_tail_events_captured"),
            )
        }

        val attributions = tailEvents.map { classifyEvent(it) }
        val worstWallEvent = attributions.maxByOrNull { it.fanoutWallNs }!!
        val worstNonCpu = (worstWallEvent.fanoutWallNs - worstWallEvent.fanoutThreadCpuNs).coerceAtLeast(0L)

        val classCounts =
            attributions.groupingBy { it.attributionClass }.eachCount()
        val primary =
            classCounts
                .filterKeys { it != Gres3FanoutTailAttributionClass.NOT_ATTRIBUTED }
                .maxByOrNull { it.value }
                ?.key
                ?: classifyEvent(tailEvents.maxByOrNull { it.fanoutDurationNs }!!).attributionClass

        val reasons = mutableListOf<String>()
        reasons += "tail_event_count=${attributions.size}"
        reasons += "primary_attribution=${primary.name.lowercase()}"
        if (worstNonCpu.toDouble() / worstWallEvent.fanoutWallNs.coerceAtLeast(1L).toDouble() >= DESCHEDULE_NON_CPU_FRACTION) {
            reasons += "worst_event_non_cpu_dominant"
        }
        if (worstWallEvent.interLegGapNs.toDouble() / worstWallEvent.fanoutWallNs.coerceAtLeast(1L).toDouble() >= INTER_LEG_GAP_FRACTION) {
            reasons += "worst_event_inter_leg_gap_elevated"
        }

        return Gres3H1d4AdjudicatorOutput(
            primaryAttribution = primary,
            tailEventAttributions = attributions,
            worstFanoutWallNs = worstWallEvent.fanoutWallNs,
            worstFanoutThreadCpuNs = worstWallEvent.fanoutThreadCpuNs,
            worstNonCpuNs = worstNonCpu,
            reasons = reasons,
        )
    }

    private fun countElevatedLegs(legDurationsNs: LongArray): Int {
        var count = 0
        var i = 0
        while (i < legDurationsNs.size) {
            if (legDurationsNs[i] >= ELEVATED_LEG_NS) {
                count++
            }
            i++
        }
        return count
    }

    fun sumLegWallNs(legDurationsNs: LongArray): Long {
        var sum = 0L
        var i = 0
        while (i < legDurationsNs.size) {
            val d = legDurationsNs[i]
            if (d > 0L) {
                sum += d
            }
            i++
        }
        return sum
    }
}
