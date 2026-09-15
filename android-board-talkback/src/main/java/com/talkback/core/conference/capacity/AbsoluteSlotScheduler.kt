package com.talkback.core.conference.capacity

import java.util.concurrent.locks.LockSupport

/**
 * Absolute nanoTime slot timeline (C3): late slots are skipped, never catch-up.
 */
class AbsoluteSlotScheduler(
    private val measurementStartNs: Long,
    private val measurementDurationNs: Long,
    private val slotPeriodNs: Long = SLOT_PERIOD_NS,
) {
    val maxSlotCount: Long =
        measurementDurationNs / slotPeriodNs

    fun nominalSlotStartNs(slotIndex: Long): Long =
        measurementStartNs + slotIndex * slotPeriodNs

    fun nominalSlotDeadlineNs(slotIndex: Long): Long =
        measurementStartNs + (slotIndex + 1) * slotPeriodNs

    fun isMeasurementComplete(nowNs: Long): Boolean =
        nowNs >= measurementStartNs + measurementDurationNs

    /**
     * Returns the next actionable slot index, or null when measurement window ended.
     * Advances [slotIndex] across all missed slots (record via [onMissedSlot]) without emission.
     */
    fun resolveNextEmittableSlot(
        nowNs: Long,
        slotIndex: Long,
        onMissedSlot: (Long) -> Unit,
    ): Long? {
        var cursor = slotIndex
        while (!isMeasurementComplete(nowNs)) {
            if (cursor >= maxSlotCount) {
                return null
            }
            val deadlineNs = nominalSlotDeadlineNs(cursor)
            if (nowNs >= deadlineNs) {
                onMissedSlot(cursor)
                cursor++
                continue
            }
            return cursor
        }
        return null
    }

    fun waitUntilSlotStart(
        slotIndex: Long,
        parkNs: (Long) -> Unit = { remaining ->
            if (remaining > 2_000_000L) {
                val parkDurationNs = remaining - 500_000L
                if (parkDurationNs > 0L) {
                    LockSupport.parkNanos(parkDurationNs)
                }
            }
        },
    ) {
        val targetNs = nominalSlotStartNs(slotIndex)
        while (true) {
            val nowNs = System.nanoTime()
            val remaining = targetNs - nowNs
            if (remaining <= 0L) {
                return
            }
            parkNs(remaining)
        }
    }

    companion object {
        const val SLOT_PERIOD_NS: Long = 20_000_000L
        const val LOGICAL_MEDIA_RATE_HZ: Int = 50
    }
}
