package com.talkback.core.conference.capacity

enum class Gres3H1dRemediationReadiness {
    READY_FOR_H1C_RERUN,
    TAIL_STALL_UNEXPLAINED,
    STRUCTURAL_C3_PROJECTION_FAIL,
    SCHED_TAIL_RECURRENT,
}

data class Gres3H1dAdjudicatorInput(
    val counters: FanOutMetricsCounters,
    val tailEvents: List<FanOutTailEvent>,
    val harnessInstrumentationActive: Boolean = true,
)

data class Gres3H1dAdjudicatorOutput(
    val readiness: Gres3H1dRemediationReadiness,
    val unexplainedExtremeTail: Boolean,
    val structuralC3ProjectionFail: Boolean,
    val recurrentSchedulingDominantTail: Boolean,
    val attributionSummary: Gres3H1dAttributionSummary,
    val schedAttributionHints: List<Gres3SchedTailAttributionHint>,
    val reasons: List<String>,
)

data class Gres3H1dAttributionSummary(
    val tailEventCount: Int,
    val schedTailEventCount: Int,
    val worstFanoutNs: Long,
    val worstSchedLatenessNs: Long,
    val worstSlotExecutionNs: Long,
    val worstMaxLegIndex: Int,
    val worstDominantLegFraction: Double,
    val schedDominantTailCount: Int,
    val legSendDominantTailCount: Int,
    val extremeStallCount: Int,
)

/**
 * H1d post-run analysis — diagnoses tail stalls; does NOT change C3 gates.
 */
object Gres3H1dAdjudicator {
    fun adjudicate(input: Gres3H1dAdjudicatorInput): Gres3H1dAdjudicatorOutput {
        val events = input.tailEvents
        val counters = input.counters

        var worstFanout = 0L
        var worstSched = 0L
        var worstSlotExec = 0L
        var worstLegIndex = -1
        var worstLegFraction = 0.0
        var schedDominant = 0
        var legDominant = 0
        var schedTailEvents = 0
        val schedHints = LinkedHashSet<Gres3SchedTailAttributionHint>()

        for (event in events) {
            if (event.fanoutDurationNs > worstFanout) {
                worstFanout = event.fanoutDurationNs
                worstLegIndex = event.maxLegIndex
                worstLegFraction = event.dominantLegFraction
            }
            if (event.schedLatenessNs > worstSched) {
                worstSched = event.schedLatenessNs
            }
            val slotExec = event.slotExecutionNs
            if (slotExec > worstSlotExec) {
                worstSlotExec = slotExec
            }
            if (event.schedDominant) {
                schedDominant++
            }
            if (event.schedLatenessNs >= Gres3TailLatencyDiagnostics.SCHED_TAIL_CAPTURE_NS) {
                schedTailEvents++
                schedHints.addAll(
                    Gres3SchedTailAttributor.hintsFor(
                        event = event,
                        harnessInstrumentationActive = input.harnessInstrumentationActive,
                    ),
                )
            }
            if (event.dominantLegFraction >= Gres3TailLatencyDiagnostics.LEG_DOMINANCE_FRACTION) {
                legDominant++
            }
        }

        val structuralC3 =
            counters.fanoutP99Ns > Gres3TailLatencyDiagnostics.C3_FANOUT_P99_NS ||
                counters.fanoutMaxNs > Gres3TailLatencyDiagnostics.C3_FANOUT_MAX_NS ||
                counters.slotDeadlineMissCount > 0

        val unexplainedExtreme =
            counters.extremeTailStallCount > 0 &&
                legDominant == 0 &&
                schedDominant == 0

        val recurrentSchedulingDominantTail =
            schedDominant >= Gres3TailLatencyDiagnostics.RECURRENT_SCHED_DOMINANT_TAIL_MIN_COUNT

        val readiness =
            when {
                unexplainedExtreme -> Gres3H1dRemediationReadiness.TAIL_STALL_UNEXPLAINED
                recurrentSchedulingDominantTail -> Gres3H1dRemediationReadiness.SCHED_TAIL_RECURRENT
                structuralC3 -> Gres3H1dRemediationReadiness.STRUCTURAL_C3_PROJECTION_FAIL
                else -> Gres3H1dRemediationReadiness.READY_FOR_H1C_RERUN
            }

        val reasons = mutableListOf<String>()
        if (structuralC3) {
            reasons += "structural_c3_projection_fail"
        }
        if (unexplainedExtreme) {
            reasons += "unexplained_extreme_tail_stall"
        }
        if (recurrentSchedulingDominantTail) {
            reasons += "recurrent_scheduling_dominant_tail"
        }
        if (schedDominant > legDominant && schedDominant > 0) {
            reasons += "scheduling_lateness_dominant_in_tails"
        }
        if (legDominant > 0) {
            reasons += "leg_send_dominant_in_tails"
        }
        if (readiness == Gres3H1dRemediationReadiness.READY_FOR_H1C_RERUN) {
            reasons += "remediation_projection_improved_or_explained"
        }

        return Gres3H1dAdjudicatorOutput(
            readiness = readiness,
            unexplainedExtremeTail = unexplainedExtreme,
            structuralC3ProjectionFail = structuralC3,
            recurrentSchedulingDominantTail = recurrentSchedulingDominantTail,
            attributionSummary =
                Gres3H1dAttributionSummary(
                    tailEventCount = events.size,
                    schedTailEventCount = schedTailEvents,
                    worstFanoutNs = worstFanout,
                    worstSchedLatenessNs = worstSched,
                    worstSlotExecutionNs = worstSlotExec,
                    worstMaxLegIndex = worstLegIndex,
                    worstDominantLegFraction = worstLegFraction,
                    schedDominantTailCount = schedDominant,
                    legSendDominantTailCount = legDominant,
                    extremeStallCount = counters.extremeTailStallCount,
                ),
            schedAttributionHints = schedHints.toList(),
            reasons = reasons,
        )
    }
}
