package com.talkback.core.conference.capacity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AbsoluteSlotSchedulerTest {
    @Test
    fun lateSlotIsSkippedWithoutCatchUp() {
        val startNs = 1_000_000L
        val scheduler =
            AbsoluteSlotScheduler(
                measurementStartNs = startNs,
                measurementDurationNs = 100_000_000L,
                slotPeriodNs = 20_000_000L,
            )
        val missed = mutableListOf<Long>()
        val nowNs = startNs + 45_000_000L
        val next = scheduler.resolveNextEmittableSlot(nowNs, slotIndex = 0L, onMissedSlot = missed::add)
        assertEquals(listOf(0L, 1L), missed)
        assertEquals(2L, next)
    }

    @Test
    fun measurementCompleteReturnsNull() {
        val startNs = 0L
        val scheduler =
            AbsoluteSlotScheduler(
                measurementStartNs = startNs,
                measurementDurationNs = 40_000_000L,
                slotPeriodNs = 20_000_000L,
            )
        assertTrue(scheduler.isMeasurementComplete(40_000_000L))
        val missed = mutableListOf<Long>()
        val next = scheduler.resolveNextEmittableSlot(40_000_000L, 0L, missed::add)
        assertNull(next)
    }
}
