package com.talkback.core.conference.capacity

/**
 * Builds C4 per-run adjudicator input from on-device harness artifacts.
 */
object Gres3C4EvidenceAssembler {
    fun buildInput(
        config: Gres3CapacityHarnessConfig,
        counters: FanOutMetricsCounters,
        thermalPreflight: ThermalPreflight.Result,
        bundleComplete: Boolean,
    ): Gres3AdjudicatorInput {
        val adjudicatorConfig = toAdjudicatorConfig(config)
        val expectedSlots = Gres3CapacityRunAdjudicator.expectedLogicalSlots(adjudicatorConfig)
        val expectedLegAttempts = expectedSlots * Gres3CapacityHarnessConstants.REQUIRED_LEG_COUNT
        return Gres3AdjudicatorInput(
            manifest =
                Gres3AdjudicatorManifest(
                    runClass = config.runClass,
                    appBuildSha = config.appBuildSha,
                    benchmarkConfigHash = config.benchmarkConfigHash,
                    requiredLegCount = Gres3CapacityHarnessConstants.REQUIRED_LEG_COUNT,
                    topologyLegCount = config.endpoints.size,
                    thermalPreflightObservable = thermalPreflight.observable,
                    thermalPreflightPasses = thermalPreflight.passesPreflight,
                ),
            config = adjudicatorConfig,
            summary = toAdjudicatorSummary(config, counters, thermalPreflight, expectedSlots, expectedLegAttempts),
            bundleComplete = bundleComplete,
        )
    }

    fun adjudicate(
        config: Gres3CapacityHarnessConfig,
        counters: FanOutMetricsCounters,
        thermalPreflight: ThermalPreflight.Result,
        bundleComplete: Boolean,
    ): Gres3AdjudicatorOutput =
        Gres3CapacityRunAdjudicator.adjudicate(
            buildInput(
                config = config,
                counters = counters,
                thermalPreflight = thermalPreflight,
                bundleComplete = bundleComplete,
            ),
        )

    private fun toAdjudicatorConfig(config: Gres3CapacityHarnessConfig): Gres3AdjudicatorConfig =
        Gres3AdjudicatorConfig(
            warmupSec = config.warmupSec,
            measurementSec = config.measurementSec,
            cooldownSec = config.cooldownSec,
            logicalMediaRateHz = AbsoluteSlotScheduler.LOGICAL_MEDIA_RATE_HZ,
            targetCount = config.endpoints.size,
            benchmarkConfigHash = config.benchmarkConfigHash,
        )

    private fun toAdjudicatorSummary(
        config: Gres3CapacityHarnessConfig,
        counters: FanOutMetricsCounters,
        thermalPreflight: ThermalPreflight.Result,
        expectedSlots: Int,
        expectedLegAttempts: Int,
    ): Gres3AdjudicatorSummary {
        val qualifyingFields =
            if (config.runClass == Gres3RunClass.QUALIFYING) {
                QualifyingSummaryFields(
                    expectedLogicalSlots = expectedSlots,
                    expectedLegSendAttempts = expectedLegAttempts,
                    perLegSendAttemptCount = counters.perLegSendAttemptCount,
                    notAttemptedCount = counters.notAttemptedCount,
                    partialBatchCount = counters.sendmmsgPartialBatchCount,
                    fanoutIncompleteSlotCount = counters.fanoutIncompleteSlotCount,
                    fanoutCompleteSlotCount = counters.fanoutCompleteSlotCount,
                    minReturnedMessages =
                        if (config.executionModel.usesSendmmsgBatch) {
                            counters.sendmmsgMinReturnedMessages
                        } else {
                            null
                        },
                    batchSyscallDurationUs =
                        if (config.executionModel.usesSendmmsgBatch &&
                            counters.sendmmsgWorstSyscallWallNs > 0L
                        ) {
                            counters.sendmmsgWorstSyscallWallNs / 1_000L
                        } else {
                            null
                        },
                )
            } else {
                null
            }
        return Gres3AdjudicatorSummary(
            emittedSlotCount = counters.emittedSlotCount,
            missedSlotCount = counters.missedSlotCount,
            slotDeadlineMissCount = counters.slotDeadlineMissCount,
            catchUpEmissionCount = counters.catchUpEmissionCount,
            sendFailCount = counters.sendFailCount,
            fanoutP99Ns = counters.fanoutP99Ns,
            fanoutMaxNs = counters.fanoutMaxNs,
            schedLatenessMaxNs = counters.schedLatenessMaxNs,
            slotExecutionCompletionMaxNs = counters.slotExecutionCompletionMaxNs,
            thermalObservable = thermalPreflight.observable,
            thermalUnknown = !thermalPreflight.observable,
            expectedLogicalSlots = qualifyingFields?.expectedLogicalSlots,
            expectedLegSendAttempts = qualifyingFields?.expectedLegSendAttempts,
            perLegSendAttemptCount = qualifyingFields?.perLegSendAttemptCount,
            notAttemptedCount = qualifyingFields?.notAttemptedCount,
            partialBatchCount = qualifyingFields?.partialBatchCount,
            fanoutIncompleteSlotCount = qualifyingFields?.fanoutIncompleteSlotCount,
            fanoutCompleteSlotCount = qualifyingFields?.fanoutCompleteSlotCount,
            minReturnedMessages = qualifyingFields?.minReturnedMessages,
            batchSyscallDurationUs = qualifyingFields?.batchSyscallDurationUs,
        )
    }

    private data class QualifyingSummaryFields(
        val expectedLogicalSlots: Int,
        val expectedLegSendAttempts: Int,
        val perLegSendAttemptCount: Int,
        val notAttemptedCount: Int,
        val partialBatchCount: Int,
        val fanoutIncompleteSlotCount: Int,
        val fanoutCompleteSlotCount: Int,
        val minReturnedMessages: Int?,
        val batchSyscallDurationUs: Long?,
    )
}
