package com.talkback.core.conference.capacity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Gres3CapacityRunAdjudicatorFixtureTest {
    @Test
    fun fixture_pass() {
        val output = Gres3CapacityRunAdjudicator.adjudicate(passQualifyingInput())
        assertEquals(Gres3Validity.VALID, output.validity)
        assertEquals(Gres3TierVerdict.PASS, output.c3Tier1)
        assertEquals(Gres3TierVerdict.PASS, output.c3Tier2)
        assertEquals(Gres3RunVerdict.PASS, output.runVerdict)
        assertTrue(output.eligibleForQualifyingSet)
        assertTrue(output.reasons.isEmpty())
    }

    @Test
    fun fixture_deadlineFail() {
        val output =
            Gres3CapacityRunAdjudicator.adjudicate(
                passQualifyingInput().copy(
                    summary = passSummary().copy(slotDeadlineMissCount = 1),
                ),
            )
        assertEquals(Gres3RunVerdict.FAIL, output.runVerdict)
        assertTrue(output.reasons.contains(Gres3AdjudicationReason.SLOT_DEADLINE_MISS))
        assertFalse(output.eligibleForQualifyingSet)
    }

    @Test
    fun fixture_sendFail() {
        val output =
            Gres3CapacityRunAdjudicator.adjudicate(
                passQualifyingInput().copy(
                    summary = passSummary().copy(sendFailCount = 2),
                ),
            )
        assertEquals(Gres3RunVerdict.FAIL, output.runVerdict)
        assertTrue(output.reasons.contains(Gres3AdjudicationReason.SEND_FAIL))
    }

    @Test
    fun fixture_catchUpFail() {
        val output =
            Gres3CapacityRunAdjudicator.adjudicate(
                passQualifyingInput().copy(
                    summary = passSummary().copy(catchUpEmissionCount = 1),
                ),
            )
        assertEquals(Gres3RunVerdict.FAIL, output.runVerdict)
        assertTrue(output.reasons.contains(Gres3AdjudicationReason.CATCH_UP_EMISSION))
    }

    @Test
    fun fixture_thermalUnknown() {
        val output =
            Gres3CapacityRunAdjudicator.adjudicate(
                passQualifyingInput().copy(
                    summary = passSummary().copy(thermalUnknown = true),
                ),
            )
        assertEquals(Gres3TierVerdict.INCOMPLETE, output.c3Tier2)
        assertEquals(Gres3RunVerdict.INVALID, output.runVerdict)
        assertTrue(output.reasons.contains(Gres3AdjudicationReason.THERMAL_UNKNOWN))
        assertFalse(output.eligibleForQualifyingSet)
    }

    @Test
    fun fixture_measurementIncomplete() {
        val output =
            Gres3CapacityRunAdjudicator.adjudicate(
                passQualifyingInput().copy(
                    summary = passSummary().copy(emittedSlotCount = 1_000),
                ),
            )
        assertEquals(Gres3RunVerdict.INVALID, output.runVerdict)
        assertTrue(output.reasons.contains(Gres3AdjudicationReason.MEASUREMENT_INCOMPLETE))
    }

    @Test
    fun fixture_configMismatch() {
        val output =
            Gres3CapacityRunAdjudicator.adjudicate(
                passQualifyingInput().copy(
                    config = passConfig().copy(benchmarkConfigHash = "deadbeef"),
                ),
            )
        assertEquals(Gres3Validity.INVALID, output.validity)
        assertEquals(Gres3RunVerdict.INVALID, output.runVerdict)
        assertTrue(output.reasons.contains(Gres3AdjudicationReason.CONFIG_MISMATCH))
    }

    @Test
    fun fixture_qualificationNotEligible() {
        val output =
            Gres3CapacityRunAdjudicator.adjudicate(
                passQualifyingInput().copy(
                    manifest = passManifest().copy(runClass = Gres3RunClass.QUALIFICATION),
                ),
            )
        assertEquals(Gres3Validity.VALID, output.validity)
        assertEquals(Gres3RunVerdict.INVALID, output.runVerdict)
        assertTrue(output.reasons.contains(Gres3AdjudicationReason.QUALIFICATION_NOT_ELIGIBLE))
        assertFalse(output.eligibleForQualifyingSet)
    }

    @Test
    fun fixture_fanoutP99Exceeded_mapsToTier1Fail() {
        val output =
            Gres3CapacityRunAdjudicator.adjudicate(
                passQualifyingInput().copy(
                    summary = passSummary().copy(fanoutP99Ns = 8_600_000L),
                ),
            )
        assertEquals(Gres3TierVerdict.FAIL, output.c3Tier1)
        assertEquals(Gres3RunVerdict.FAIL, output.runVerdict)
        assertTrue(output.reasons.contains(Gres3AdjudicationReason.FANOUT_P99_EXCEEDED))
    }

    @Test
    fun fixture_notAttemptedCountFail() {
        val output =
            Gres3CapacityRunAdjudicator.adjudicate(
                passQualifyingInput().copy(
                    summary = passSummary().copy(notAttemptedCount = 1),
                ),
            )
        assertEquals(Gres3RunVerdict.FAIL, output.runVerdict)
        assertTrue(output.reasons.contains(Gres3AdjudicationReason.NOT_ATTEMPTED_COUNT_NONZERO))
    }

    @Test
    fun fixture_partialBatchFail() {
        val output =
            Gres3CapacityRunAdjudicator.adjudicate(
                passQualifyingInput().copy(
                    summary = passSummary().copy(partialBatchCount = 1),
                ),
            )
        assertEquals(Gres3RunVerdict.FAIL, output.runVerdict)
        assertTrue(output.reasons.contains(Gres3AdjudicationReason.PARTIAL_BATCH_COUNT_NONZERO))
    }

    @Test
    fun fixture_fanoutIncompleteFail() {
        val output =
            Gres3CapacityRunAdjudicator.adjudicate(
                passQualifyingInput().copy(
                    summary =
                        passSummary().copy(
                            fanoutIncompleteSlotCount = 1,
                            fanoutCompleteSlotCount = 29_999,
                        ),
                ),
            )
        assertEquals(Gres3RunVerdict.FAIL, output.runVerdict)
        assertTrue(output.reasons.contains(Gres3AdjudicationReason.FANOUT_INCOMPLETE_SLOT))
    }

    @Test
    fun m03LoopbackFanoutMetrics_failQualifyingTier1() {
        val output =
            Gres3CapacityRunAdjudicator.adjudicate(
                passQualifyingInput().copy(
                    summary =
                        passSummary().copy(
                            fanoutP99Ns = 8_634_000L,
                            fanoutMaxNs = 16_111_458L,
                        ),
                ),
            )
        assertEquals(Gres3RunVerdict.FAIL, output.runVerdict)
        assertTrue(output.reasons.contains(Gres3AdjudicationReason.FANOUT_P99_EXCEEDED))
        assertTrue(output.reasons.contains(Gres3AdjudicationReason.FANOUT_MAX_EXCEEDED))
    }

    companion object {
        private const val HASH = "b4ba4ce796cf7a7e64d4eb12a416e43bdc0db98db46f8ebdd5e8af513274e10d"

        fun passManifest(): Gres3AdjudicatorManifest =
            Gres3AdjudicatorManifest(
                runClass = Gres3RunClass.QUALIFYING,
                appBuildSha = "cac628d",
                benchmarkConfigHash = HASH,
                requiredLegCount = 9,
                topologyLegCount = 9,
                thermalPreflightObservable = true,
                thermalPreflightPasses = true,
            )

        fun passConfig(): Gres3AdjudicatorConfig =
            Gres3AdjudicatorConfig(
                warmupSec = Gres3CapacityRunAdjudicator.QUALIFYING_WARMUP_SEC,
                measurementSec = Gres3CapacityRunAdjudicator.QUALIFYING_MEASUREMENT_SEC,
                cooldownSec = Gres3CapacityRunAdjudicator.QUALIFYING_COOLDOWN_SEC,
                logicalMediaRateHz = Gres3CapacityRunAdjudicator.LOGICAL_MEDIA_RATE_HZ,
                targetCount = 9,
                benchmarkConfigHash = HASH,
            )

        fun passSummary(): Gres3AdjudicatorSummary =
            Gres3AdjudicatorSummary(
                emittedSlotCount = 30_000,
                missedSlotCount = 0,
                slotDeadlineMissCount = 0,
                catchUpEmissionCount = 0,
                sendFailCount = 0,
                fanoutP99Ns = 4_000_000L,
                fanoutMaxNs = 9_000_000L,
                schedLatenessMaxNs = 500_000L,
                slotExecutionCompletionMaxNs = 8_000_000L,
                thermalObservable = true,
                thermalUnknown = false,
                thermalMaxStatusLevel = 2,
                memoryMonotonicGrowth = false,
                cpuSaturated = false,
                expectedLogicalSlots = 30_000,
                expectedLegSendAttempts = 270_000,
                perLegSendAttemptCount = 270_000,
                notAttemptedCount = 0,
                partialBatchCount = 0,
                fanoutIncompleteSlotCount = 0,
                fanoutCompleteSlotCount = 30_000,
                minReturnedMessages = 9,
                batchSyscallDurationUs = 120L,
            )

        fun passQualifyingInput(): Gres3AdjudicatorInput =
            Gres3AdjudicatorInput(
                manifest = passManifest(),
                config = passConfig(),
                summary = passSummary(),
            )
    }
}
