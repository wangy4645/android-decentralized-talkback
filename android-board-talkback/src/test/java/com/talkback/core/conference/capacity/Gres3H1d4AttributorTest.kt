package com.talkback.core.conference.capacity

import org.junit.Assert.assertEquals
import org.junit.Test

class Gres3H1d4AttributorTest {
    private fun tailEvent(
        slotIndex: Long,
        fanoutWallNs: Long,
        fanoutCpuNs: Long,
        legDurations: LongArray,
        maxLegIndex: Int,
        maxLegNs: Long,
    ): FanOutTailEvent {
        val legSum = Gres3FanoutTailAttributor.sumLegWallNs(legDurations)
        return FanOutTailEvent(
            slotIndex = slotIndex,
            schedLatenessNs = 100_000L,
            fanoutDurationNs = fanoutWallNs,
            fanoutThreadCpuNs = fanoutCpuNs,
            legWallSumNs = legSum,
            maxLegIndex = maxLegIndex,
            maxLegDurationNs = maxLegNs,
            legDurationsNs = legDurations,
            slotDeadlineMiss = false,
            stallContext =
                FanOutStallContext(
                    uptimeMs = 1L,
                    threadId = 1,
                    threadName = "gres3-capacity-pacing",
                    processThreadPriority = -8,
                    javaThreadPriority = 5,
                    javaHeapUsedBytes = 0L,
                    nativeHeapBytes = 0L,
                    gcCount = -1L,
                    thermalStatus = null,
                    processImportance = null,
                    screenInteractive = true,
                ),
        )
    }

    @Test
    fun descheduleDominant_wallMuchGreaterThanCpu() {
        val event =
            tailEvent(
                slotIndex = 44L,
                fanoutWallNs = 12_400_000L,
                fanoutCpuNs = 300_000L,
                legDurations = LongArray(9) { 400_000L },
                maxLegIndex = 2,
                maxLegNs = 500_000L,
            )
        val classified = Gres3FanoutTailAttributor.classifyEvent(event)
        assertEquals(Gres3FanoutTailAttributionClass.FANOUT_INTERNAL_DESCHEDULE, classified.attributionClass)
    }

    @Test
    fun singleLegBlock_dominantSend() {
        val legs = LongArray(9) { 200_000L }
        legs[3] = 9_500_000L
        val event =
            tailEvent(
                slotIndex = 10L,
                fanoutWallNs = 10_000_000L,
                fanoutCpuNs = 9_800_000L,
                legDurations = legs,
                maxLegIndex = 3,
                maxLegNs = 9_500_000L,
            )
        val classified = Gres3FanoutTailAttributor.classifyEvent(event)
        assertEquals(Gres3FanoutTailAttributionClass.SINGLE_SEND_SYSCALL_BLOCK, classified.attributionClass)
    }

    @Test
    fun multiLegAccumulation_severalElevatedLegs() {
        val legs = LongArray(9) { 1_200_000L }
        val event =
            tailEvent(
                slotIndex = 20L,
                fanoutWallNs = 11_000_000L,
                fanoutCpuNs = 10_500_000L,
                legDurations = legs,
                maxLegIndex = 0,
                maxLegNs = 1_200_000L,
            )
        val classified = Gres3FanoutTailAttributor.classifyEvent(event)
        assertEquals(Gres3FanoutTailAttributionClass.MULTI_LEG_ACCUMULATION, classified.attributionClass)
    }
}
