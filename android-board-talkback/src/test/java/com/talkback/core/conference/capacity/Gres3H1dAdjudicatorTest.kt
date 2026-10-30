package com.talkback.core.conference.capacity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Gres3H1dAdjudicatorTest {
    private fun stallContext() =
        FanOutStallContext(
            uptimeMs = 1000L,
            threadId = 42,
            threadName = "gres3-capacity-pacing",
            processThreadPriority = -8,
            javaThreadPriority = 5,
            javaHeapUsedBytes = 1_000_000L,
            nativeHeapBytes = 500_000L,
            gcCount = -1L,
            thermalStatus = null,
            processImportance = null,
            screenInteractive = true,
        )

    @Test
    fun legDominantTail_structuralC3Fail() {
        val event =
            FanOutTailEvent(
                slotIndex = 42L,
                schedLatenessNs = 1_000_000L,
                fanoutDurationNs = 900_000_000L,
                fanoutThreadCpuNs = 880_000_000L,
                legWallSumNs = 890_000_000L,
                maxLegIndex = 3,
                maxLegDurationNs = 880_000_000L,
                legDurationsNs = LongArray(9) { if (it == 3) 880_000_000L else 1_000_000L },
                slotDeadlineMiss = true,
                stallContext = stallContext(),
            )
        val output =
            Gres3H1dAdjudicator.adjudicate(
                Gres3H1dAdjudicatorInput(
                    counters =
                        FanOutMetricsCounters(
                            emittedSlotCount = 100,
                            missedSlotCount = 0,
                            slotDeadlineMissCount = 1,
                            catchUpEmissionCount = 0,
                            sendFailCount = 0,
                            fanoutP99Ns = 8_000_000L,
                            fanoutMaxNs = 900_000_000L,
                            extremeTailStallCount = 1,
                        ),
                    tailEvents = listOf(event),
                ),
            )
        assertEquals(Gres3H1dRemediationReadiness.STRUCTURAL_C3_PROJECTION_FAIL, output.readiness)
        assertTrue(output.reasons.contains("leg_send_dominant_in_tails"))
    }

    @Test
    fun schedDominantTail_recurrentSchedReadiness() {
        val event =
            FanOutTailEvent(
                slotIndex = 1L,
                schedLatenessNs = 12_000_000L,
                fanoutDurationNs = 3_000_000L,
                fanoutThreadCpuNs = 2_800_000L,
                legWallSumNs = 2_900_000L,
                maxLegIndex = 0,
                maxLegDurationNs = 500_000L,
                legDurationsNs = LongArray(9) { 500_000L },
                slotDeadlineMiss = true,
                stallContext = stallContext(),
            )
        val output =
            Gres3H1dAdjudicator.adjudicate(
                Gres3H1dAdjudicatorInput(
                    counters =
                        FanOutMetricsCounters(
                            emittedSlotCount = 100,
                            missedSlotCount = 0,
                            slotDeadlineMissCount = 1,
                            catchUpEmissionCount = 0,
                            sendFailCount = 0,
                            fanoutP99Ns = 4_000_000L,
                            fanoutMaxNs = 12_000_000L,
                        ),
                    tailEvents = listOf(event, event.copy(slotIndex = 2L)),
                ),
            )
        assertEquals(Gres3H1dRemediationReadiness.SCHED_TAIL_RECURRENT, output.readiness)
        assertTrue(output.recurrentSchedulingDominantTail)
        assertTrue(output.reasons.contains("scheduling_lateness_dominant_in_tails"))
        assertTrue(output.schedAttributionHints.isNotEmpty())
    }

    @Test
    fun defaultPacingPriority_isUrgentDisplayNotJavaMax() {
        assertEquals(
            android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY,
            Gres3CapacityHarnessConstants.DEFAULT_PACING_THREAD_PRIORITY,
        )
    }
}
