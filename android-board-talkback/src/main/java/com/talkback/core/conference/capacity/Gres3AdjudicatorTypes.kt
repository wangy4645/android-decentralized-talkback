package com.talkback.core.conference.capacity

enum class Gres3Validity {
    VALID,
    INVALID,
}

enum class Gres3TierVerdict {
    PASS,
    FAIL,
    INCOMPLETE,
}

enum class Gres3RunVerdict {
    PASS,
    FAIL,
    INVALID,
}

enum class Gres3AdjudicationReason {
    BUNDLE_INCOMPLETE,
    CONFIG_MISMATCH,
    QUALIFICATION_NOT_ELIGIBLE,
    TARGET_COUNT_NOT_9,
    MEASUREMENT_DURATION_NOT_FROZEN,
    MEASUREMENT_INCOMPLETE,
    THERMAL_UNKNOWN,
    SLOT_DEADLINE_MISS,
    CATCH_UP_EMISSION,
    SEND_FAIL,
    FANOUT_P99_EXCEEDED,
    FANOUT_MAX_EXCEEDED,
    SLOT_EXECUTION_EXCEEDED,
    THERMAL_SEVERE,
    MEMORY_MONOTONIC_GROWTH,
    CPU_SATURATION,
    NOT_ATTEMPTED_COUNT_NONZERO,
    PARTIAL_BATCH_COUNT_NONZERO,
    FANOUT_INCOMPLETE_SLOT,
    PER_LEG_SEND_ATTEMPT_COUNT_MISMATCH,
    MIN_RETURNED_MESSAGES_BELOW_9,
}

data class Gres3AdjudicatorManifest(
    val runClass: Gres3RunClass,
    val appBuildSha: String,
    val benchmarkConfigHash: String,
    val requiredLegCount: Int,
    val topologyLegCount: Int,
    val thermalPreflightObservable: Boolean,
    val thermalPreflightPasses: Boolean,
)

data class Gres3AdjudicatorConfig(
    val warmupSec: Int,
    val measurementSec: Int,
    val cooldownSec: Int,
    val logicalMediaRateHz: Int,
    val targetCount: Int,
    val benchmarkConfigHash: String,
)

data class Gres3AdjudicatorSummary(
    val emittedSlotCount: Int,
    val missedSlotCount: Int,
    val slotDeadlineMissCount: Int,
    val catchUpEmissionCount: Int,
    val sendFailCount: Int,
    val fanoutP99Ns: Long,
    val fanoutMaxNs: Long,
    val schedLatenessMaxNs: Long? = null,
    val slotExecutionCompletionMaxNs: Long? = null,
    val thermalObservable: Boolean,
    val thermalUnknown: Boolean,
    val thermalMaxStatusLevel: Int? = null,
    val memoryMonotonicGrowth: Boolean? = null,
    val cpuSaturated: Boolean? = null,
    val expectedLogicalSlots: Int? = null,
    val expectedLegSendAttempts: Int? = null,
    val perLegSendAttemptCount: Int? = null,
    val notAttemptedCount: Int? = null,
    val partialBatchCount: Int? = null,
    val fanoutIncompleteSlotCount: Int? = null,
    val fanoutCompleteSlotCount: Int? = null,
    val minReturnedMessages: Int? = null,
    val batchSyscallDurationUs: Long? = null,
)

data class Gres3AdjudicatorInput(
    val manifest: Gres3AdjudicatorManifest,
    val config: Gres3AdjudicatorConfig,
    val summary: Gres3AdjudicatorSummary,
    val bundleComplete: Boolean = true,
)

data class Gres3AdjudicatorOutput(
    val validity: Gres3Validity,
    val c3Tier1: Gres3TierVerdict?,
    val c3Tier2: Gres3TierVerdict?,
    val runVerdict: Gres3RunVerdict,
    val reasons: List<Gres3AdjudicationReason>,
    val eligibleForQualifyingSet: Boolean,
)
