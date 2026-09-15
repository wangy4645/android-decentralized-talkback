package com.talkback.core.conference.capacity

/**
 * C3/C4 per-run pure adjudicator (H1b). No Android / I/O dependencies.
 */
object Gres3CapacityRunAdjudicator {
    const val FANOUT_P99_MAX_US: Long = 5_000L
    const val FANOUT_MAX_MAX_US: Long = 10_000L
    const val SLOT_EXECUTION_MAX_US: Long = 20_000L
    const val QUALIFYING_MEASUREMENT_SEC: Int = 600
    const val QUALIFYING_WARMUP_SEC: Int = 60
    const val QUALIFYING_COOLDOWN_SEC: Int = 30
    const val LOGICAL_MEDIA_RATE_HZ: Int = 50
    const val BOUNDARY_SLOT_TOLERANCE: Int = 2

    // Android PowerManager.THERMAL_STATUS_SEVERE
    const val THERMAL_STATUS_SEVERE: Int = 3

    fun adjudicate(input: Gres3AdjudicatorInput): Gres3AdjudicatorOutput {
        val reasons = mutableListOf<Gres3AdjudicationReason>()

        if (!input.bundleComplete) {
            reasons += Gres3AdjudicationReason.BUNDLE_INCOMPLETE
            return invalidOutput(reasons)
        }

        if (input.manifest.runClass == Gres3RunClass.QUALIFICATION) {
            reasons += Gres3AdjudicationReason.QUALIFICATION_NOT_ELIGIBLE
        }

        if (input.config.targetCount != Gres3CapacityHarnessConstants.REQUIRED_LEG_COUNT ||
            input.manifest.topologyLegCount != Gres3CapacityHarnessConstants.REQUIRED_LEG_COUNT ||
            input.manifest.requiredLegCount != Gres3CapacityHarnessConstants.REQUIRED_LEG_COUNT
        ) {
            reasons += Gres3AdjudicationReason.TARGET_COUNT_NOT_9
        }

        if (input.manifest.benchmarkConfigHash != input.config.benchmarkConfigHash) {
            reasons += Gres3AdjudicationReason.CONFIG_MISMATCH
        }

        val structuralInvalid =
            reasons.any {
                it == Gres3AdjudicationReason.BUNDLE_INCOMPLETE ||
                    it == Gres3AdjudicationReason.TARGET_COUNT_NOT_9 ||
                    it == Gres3AdjudicationReason.CONFIG_MISMATCH
            }
        if (structuralInvalid) {
            return invalidOutput(reasons)
        }

        if (input.manifest.runClass == Gres3RunClass.QUALIFYING) {
            if (input.config.measurementSec != QUALIFYING_MEASUREMENT_SEC ||
                input.config.warmupSec != QUALIFYING_WARMUP_SEC ||
                input.config.cooldownSec != QUALIFYING_COOLDOWN_SEC
            ) {
                reasons += Gres3AdjudicationReason.MEASUREMENT_DURATION_NOT_FROZEN
            }
        }

        val expectedSlots = expectedLogicalSlots(input.config)
        val slotShortfall = expectedSlots - input.summary.emittedSlotCount - input.summary.missedSlotCount
        if (slotShortfall > BOUNDARY_SLOT_TOLERANCE) {
            reasons += Gres3AdjudicationReason.MEASUREMENT_INCOMPLETE
        }

        val tier1Reasons = evaluateTier1(input.summary, input.manifest.runClass)
        val tier1 =
            if (tier1Reasons.isEmpty()) {
                Gres3TierVerdict.PASS
            } else {
                reasons += tier1Reasons
                Gres3TierVerdict.FAIL
            }

        val tier2Reasons = evaluateTier2(input)
        val tier2 =
            when {
                tier2Reasons.contains(Gres3AdjudicationReason.THERMAL_UNKNOWN) -> {
                    reasons += Gres3AdjudicationReason.THERMAL_UNKNOWN
                    Gres3TierVerdict.INCOMPLETE
                }
                tier2Reasons.isNotEmpty() -> {
                    reasons += tier2Reasons
                    Gres3TierVerdict.FAIL
                }
                else -> Gres3TierVerdict.PASS
            }

        val eligible =
            input.manifest.runClass == Gres3RunClass.QUALIFYING &&
                !reasons.contains(Gres3AdjudicationReason.QUALIFICATION_NOT_ELIGIBLE) &&
                !reasons.contains(Gres3AdjudicationReason.MEASUREMENT_DURATION_NOT_FROZEN) &&
                !reasons.contains(Gres3AdjudicationReason.MEASUREMENT_INCOMPLETE)

        val runVerdict =
            when {
                reasons.contains(Gres3AdjudicationReason.QUALIFICATION_NOT_ELIGIBLE) -> Gres3RunVerdict.INVALID
                reasons.contains(Gres3AdjudicationReason.MEASUREMENT_INCOMPLETE) -> Gres3RunVerdict.INVALID
                reasons.contains(Gres3AdjudicationReason.MEASUREMENT_DURATION_NOT_FROZEN) -> Gres3RunVerdict.INVALID
                tier2 == Gres3TierVerdict.INCOMPLETE -> Gres3RunVerdict.INVALID
                tier1 == Gres3TierVerdict.FAIL || tier2 == Gres3TierVerdict.FAIL -> Gres3RunVerdict.FAIL
                tier1 == Gres3TierVerdict.PASS && tier2 == Gres3TierVerdict.PASS -> Gres3RunVerdict.PASS
                else -> Gres3RunVerdict.INVALID
            }

        return Gres3AdjudicatorOutput(
            validity = Gres3Validity.VALID,
            c3Tier1 = tier1,
            c3Tier2 = tier2,
            runVerdict = runVerdict,
            reasons = reasons.distinct(),
            eligibleForQualifyingSet = eligible && runVerdict == Gres3RunVerdict.PASS,
        )
    }

    fun expectedLogicalSlots(config: Gres3AdjudicatorConfig): Int =
        config.measurementSec * config.logicalMediaRateHz

    private fun evaluateTier1(
        summary: Gres3AdjudicatorSummary,
        runClass: Gres3RunClass,
    ): List<Gres3AdjudicationReason> {
        val reasons = mutableListOf<Gres3AdjudicationReason>()
        if (summary.slotDeadlineMissCount != 0) {
            reasons += Gres3AdjudicationReason.SLOT_DEADLINE_MISS
        }
        if (summary.catchUpEmissionCount != 0) {
            reasons += Gres3AdjudicationReason.CATCH_UP_EMISSION
        }
        if (summary.sendFailCount != 0) {
            reasons += Gres3AdjudicationReason.SEND_FAIL
        }
        if (runClass == Gres3RunClass.QUALIFYING) {
            reasons += evaluateQualifyingSubmissionEvidence(summary)
        }
        val fanoutP99Us = summary.fanoutP99Ns / 1_000L
        val fanoutMaxUs = summary.fanoutMaxNs / 1_000L
        if (fanoutP99Us > FANOUT_P99_MAX_US) {
            reasons += Gres3AdjudicationReason.FANOUT_P99_EXCEEDED
        }
        if (fanoutMaxUs > FANOUT_MAX_MAX_US) {
            reasons += Gres3AdjudicationReason.FANOUT_MAX_EXCEEDED
        }
        val slotExecutionMaxUs =
            summary.slotExecutionCompletionMaxNs?.let { it / 1_000L }
                ?: run {
                    val sched = summary.schedLatenessMaxNs ?: 0L
                    (sched + summary.fanoutMaxNs) / 1_000L
                }
        if (slotExecutionMaxUs >= SLOT_EXECUTION_MAX_US) {
            reasons += Gres3AdjudicationReason.SLOT_EXECUTION_EXCEEDED
        }
        return reasons
    }

    private fun evaluateTier2(input: Gres3AdjudicatorInput): List<Gres3AdjudicationReason> {
        val summary = input.summary
        val reasons = mutableListOf<Gres3AdjudicationReason>()

        val thermalObservable =
            summary.thermalObservable &&
                input.manifest.thermalPreflightObservable &&
                input.manifest.thermalPreflightPasses
        if (!thermalObservable || summary.thermalUnknown) {
            return listOf(Gres3AdjudicationReason.THERMAL_UNKNOWN)
        }

        val maxLevel = summary.thermalMaxStatusLevel
        if (maxLevel != null && maxLevel >= THERMAL_STATUS_SEVERE) {
            reasons += Gres3AdjudicationReason.THERMAL_SEVERE
        }

        if (summary.memoryMonotonicGrowth == true) {
            reasons += Gres3AdjudicationReason.MEMORY_MONOTONIC_GROWTH
        }
        if (summary.cpuSaturated == true) {
            reasons += Gres3AdjudicationReason.CPU_SATURATION
        }
        return reasons
    }

    private fun evaluateQualifyingSubmissionEvidence(
        summary: Gres3AdjudicatorSummary,
    ): List<Gres3AdjudicationReason> {
        val reasons = mutableListOf<Gres3AdjudicationReason>()
        summary.notAttemptedCount?.let { count ->
            if (count != 0) {
                reasons += Gres3AdjudicationReason.NOT_ATTEMPTED_COUNT_NONZERO
            }
        }
        summary.partialBatchCount?.let { count ->
            if (count != 0) {
                reasons += Gres3AdjudicationReason.PARTIAL_BATCH_COUNT_NONZERO
            }
        }
        summary.fanoutIncompleteSlotCount?.let { count ->
            if (count != 0) {
                reasons += Gres3AdjudicationReason.FANOUT_INCOMPLETE_SLOT
            }
        }
        val expectedAttempts = summary.expectedLegSendAttempts
        val actualAttempts = summary.perLegSendAttemptCount
        if (expectedAttempts != null && actualAttempts != null && actualAttempts != expectedAttempts) {
            reasons += Gres3AdjudicationReason.PER_LEG_SEND_ATTEMPT_COUNT_MISMATCH
        }
        summary.minReturnedMessages?.let { minReturned ->
            if (minReturned < Gres3CapacityHarnessConstants.REQUIRED_LEG_COUNT) {
                reasons += Gres3AdjudicationReason.MIN_RETURNED_MESSAGES_BELOW_9
            }
        }
        return reasons
    }

    private fun invalidOutput(reasons: List<Gres3AdjudicationReason>): Gres3AdjudicatorOutput =
        Gres3AdjudicatorOutput(
            validity = Gres3Validity.INVALID,
            c3Tier1 = null,
            c3Tier2 = null,
            runVerdict = Gres3RunVerdict.INVALID,
            reasons = reasons.distinct(),
            eligibleForQualifyingSet = false,
        )
}
