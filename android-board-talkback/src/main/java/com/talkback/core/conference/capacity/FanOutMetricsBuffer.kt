package com.talkback.core.conference.capacity

import android.content.Context

/**
 * In-memory per-slot metrics + H1d tail events (measurement hot path). Flush during cooldown.
 */
class FanOutMetricsBuffer(
    private val maxSlots: Int,
    private val legCount: Int,
    private val tailDiagnosticsEnabled: Boolean = false,
    private val recordLegDetail: Boolean = false,
    private val stallContextFactory: (() -> FanOutStallContext)? = null,
) {
    private var emittedSlotCount: Int = 0
    private var missedSlotCount: Int = 0
    private var slotDeadlineMissCount: Int = 0
    private var catchUpEmissionCount: Int = 0
    private var sendFailCount: Int = 0
    private var extremeTailStallCount: Int = 0
    private var sendmmsgPartialBatchCount: Int = 0
    private var sendmmsgMinReturnedMessages: Int = Int.MAX_VALUE
    private var sendmmsgWorstSyscallWallNs: Long = 0L
    private var sendmmsgEagainCount: Int = 0
    private var notAttemptedCount: Int = 0
    private var perLegSendAttemptCount: Int = 0
    private var fanoutIncompleteSlotCount: Int = 0
    private var fanoutCompleteSlotCount: Int = 0
    private var slotExecutionMaxNs: Long = 0L

    private val schedLatenessNs = LongArray(maxSlots)
    private val fanoutDurationNs = LongArray(maxSlots)
    private val slotIndexByEmitted = LongArray(maxSlots)
    private val legsFailedByEmitted = IntArray(maxSlots)
    private val maxLegIndexByEmitted = IntArray(maxSlots) { -1 }
    private val maxLegDurationNsByEmitted = LongArray(maxSlots)

    private var fanoutMaxNs: Long = 0L
    private var schedLatenessMaxNs: Long = 0L
    private val fanoutHistogram = LongArray(FANOUT_HISTOGRAM_BUCKETS)

    private val tailEvents = ArrayList<FanOutTailEvent>(Gres3TailLatencyDiagnostics.MAX_TAIL_EVENTS)
    private val legDurationCopyScratch = LongArray(legCount)

    fun recordMissedSlot(@Suppress("UNUSED_PARAMETER") slotIndex: Long) {
        missedSlotCount++
    }

    fun recordEmittedSlot(
        slotIndex: Long,
        schedLatenessNsValue: Long,
        fanoutDurationNsValue: Long,
        sendOutcome: FanOutSendOutcomeScratch,
        slotDeadlineMiss: Boolean,
        legDurationsNs: LongArray? = null,
    ) {
        if (emittedSlotCount >= maxSlots) {
            return
        }
        val idx = emittedSlotCount
        schedLatenessNs[idx] = schedLatenessNsValue
        fanoutDurationNs[idx] = fanoutDurationNsValue
        slotIndexByEmitted[idx] = slotIndex
        legsFailedByEmitted[idx] = sendOutcome.legsFailed
        if (recordLegDetail) {
            maxLegIndexByEmitted[idx] = sendOutcome.maxLegIndex
            maxLegDurationNsByEmitted[idx] = sendOutcome.maxLegDurationNs
        }
        emittedSlotCount++

        if (slotDeadlineMiss) {
            slotDeadlineMissCount++
        }
        if (sendOutcome.legsFailed > 0) {
            sendFailCount++
        }
        val notAttemptedThisSlot = notAttemptedLegs(sendOutcome)
        notAttemptedCount += notAttemptedThisSlot
        perLegSendAttemptCount += legCount - notAttemptedThisSlot

        if (sendOutcome.sendmmsgRequestedMessages > 0) {
            if (sendOutcome.sendmmsgReturnedMessages < sendOutcome.sendmmsgRequestedMessages) {
                sendmmsgPartialBatchCount++
            }
            if (sendOutcome.sendmmsgReturnedMessages < sendmmsgMinReturnedMessages) {
                sendmmsgMinReturnedMessages = sendOutcome.sendmmsgReturnedMessages
            }
            if (sendOutcome.sendmmsgSyscallWallNs > sendmmsgWorstSyscallWallNs) {
                sendmmsgWorstSyscallWallNs = sendOutcome.sendmmsgSyscallWallNs
            }
            if (sendOutcome.sendmmsgEagain) {
                sendmmsgEagainCount++
            }
        }

        val fanoutComplete = isFanoutComplete(sendOutcome)
        if (fanoutComplete) {
            fanoutCompleteSlotCount++
            if (fanoutDurationNsValue > fanoutMaxNs) {
                fanoutMaxNs = fanoutDurationNsValue
            }
            val bucket = fanoutDurationToBucket(fanoutDurationNsValue)
            if (bucket in fanoutHistogram.indices) {
                fanoutHistogram[bucket]++
            }
            val slotExecutionNs = schedLatenessNsValue + fanoutDurationNsValue
            if (slotExecutionNs > slotExecutionMaxNs) {
                slotExecutionMaxNs = slotExecutionNs
            }
        } else {
            fanoutIncompleteSlotCount++
        }

        if (schedLatenessNsValue > schedLatenessMaxNs) {
            schedLatenessMaxNs = schedLatenessNsValue
        }
        if (fanoutDurationNsValue >= Gres3TailLatencyDiagnostics.EXTREME_STALL_NS) {
            extremeTailStallCount++
        }

        if (tailDiagnosticsEnabled && shouldCaptureTail(schedLatenessNsValue, fanoutDurationNsValue, slotDeadlineMiss)) {
            captureTailEvent(
                slotIndex = slotIndex,
                schedLatenessNsValue = schedLatenessNsValue,
                fanoutDurationNsValue = fanoutDurationNsValue,
                sendOutcome = sendOutcome,
                slotDeadlineMiss = slotDeadlineMiss,
                legDurationsNs = legDurationsNs,
            )
        }
    }

    fun snapshotCounters(): FanOutMetricsCounters =
        FanOutMetricsCounters(
            emittedSlotCount = emittedSlotCount,
            missedSlotCount = missedSlotCount,
            slotDeadlineMissCount = slotDeadlineMissCount,
            catchUpEmissionCount = catchUpEmissionCount,
            sendFailCount = sendFailCount,
            fanoutP99Ns = computeFanoutP99Ns(),
            fanoutMaxNs = fanoutMaxNs,
            schedLatenessMaxNs = schedLatenessMaxNs,
            extremeTailStallCount = extremeTailStallCount,
            sendmmsgPartialBatchCount = sendmmsgPartialBatchCount,
            sendmmsgMinReturnedMessages =
                if (sendmmsgMinReturnedMessages == Int.MAX_VALUE) {
                    0
                } else {
                    sendmmsgMinReturnedMessages
                },
            sendmmsgWorstSyscallWallNs = sendmmsgWorstSyscallWallNs,
            sendmmsgEagainCount = sendmmsgEagainCount,
            notAttemptedCount = notAttemptedCount,
            perLegSendAttemptCount = perLegSendAttemptCount,
            fanoutIncompleteSlotCount = fanoutIncompleteSlotCount,
            fanoutCompleteSlotCount = fanoutCompleteSlotCount,
            slotExecutionCompletionMaxNs = slotExecutionMaxNs,
        )

    fun emittedSlotRecords(): List<FanOutSlotRecord> {
        val out = ArrayList<FanOutSlotRecord>(emittedSlotCount)
        for (i in 0 until emittedSlotCount) {
            out.add(
                FanOutSlotRecord(
                    slotIndex = slotIndexByEmitted[i],
                    schedLatenessNs = schedLatenessNs[i],
                    fanoutDurationNs = fanoutDurationNs[i],
                    legsFailed = legsFailedByEmitted[i],
                    maxLegIndex = if (recordLegDetail) maxLegIndexByEmitted[i] else -1,
                    maxLegDurationNs = if (recordLegDetail) maxLegDurationNsByEmitted[i] else 0L,
                ),
            )
        }
        return out
    }

    fun tailEvents(): List<FanOutTailEvent> = tailEvents.toList()

    fun schedTailEvents(): List<FanOutTailEvent> =
        tailEvents.filter { it.schedLatenessNs >= Gres3TailLatencyDiagnostics.SCHED_TAIL_CAPTURE_NS }

    private fun shouldCaptureTail(
        schedLatenessNsValue: Long,
        fanoutDurationNsValue: Long,
        slotDeadlineMiss: Boolean,
    ): Boolean {
        if (tailEvents.size >= Gres3TailLatencyDiagnostics.MAX_TAIL_EVENTS) {
            return false
        }
        val slotExec = schedLatenessNsValue + fanoutDurationNsValue
        return slotDeadlineMiss ||
            fanoutDurationNsValue >= Gres3TailLatencyDiagnostics.FANOUT_TAIL_CAPTURE_NS ||
            schedLatenessNsValue >= Gres3TailLatencyDiagnostics.SCHED_TAIL_CAPTURE_NS ||
            slotExec >= Gres3TailLatencyDiagnostics.SLOT_EXEC_TAIL_CAPTURE_NS
    }

    private fun captureTailEvent(
        slotIndex: Long,
        schedLatenessNsValue: Long,
        fanoutDurationNsValue: Long,
        sendOutcome: FanOutSendOutcomeScratch,
        slotDeadlineMiss: Boolean,
        legDurationsNs: LongArray?,
    ) {
        if (legDurationsNs != null && legDurationsNs.size == legCount) {
            System.arraycopy(legDurationsNs, 0, legDurationCopyScratch, 0, legCount)
        } else {
            legDurationCopyScratch.fill(-1L)
        }
        val stallContext =
            stallContextFactory?.invoke()
                ?: FanOutStallContext(
                    uptimeMs = 0L,
                    threadId = -1,
                    threadName = "",
                    processThreadPriority = 0,
                    javaThreadPriority = 0,
                    javaHeapUsedBytes = 0L,
                    nativeHeapBytes = 0L,
                    gcCount = -1L,
                    thermalStatus = null,
                    processImportance = null,
                    screenInteractive = null,
                )
        tailEvents.add(
            FanOutTailEvent(
                slotIndex = slotIndex,
                schedLatenessNs = schedLatenessNsValue,
                fanoutDurationNs = fanoutDurationNsValue,
                fanoutThreadCpuNs = sendOutcome.fanoutThreadCpuNs,
                legWallSumNs = Gres3FanoutTailAttributor.sumLegWallNs(legDurationCopyScratch),
                maxLegIndex = sendOutcome.maxLegIndex,
                maxLegDurationNs = sendOutcome.maxLegDurationNs,
                legDurationsNs = legDurationCopyScratch.copyOf(),
                slotDeadlineMiss = slotDeadlineMiss,
                stallContext = stallContext,
            ),
        )
    }

    private fun computeFanoutP99Ns(): Long {
        val total = fanoutHistogram.sum()
        if (total == 0L) {
            return 0L
        }
        val target = (total * 99 + 99) / 100
        var cumulative = 0L
        for (bucket in fanoutHistogram.indices) {
            cumulative += fanoutHistogram[bucket]
            if (cumulative >= target) {
                return bucketUpperBoundNs(bucket)
            }
        }
        return fanoutMaxNs
    }

    private fun fanoutDurationToBucket(durationNs: Long): Int {
        val micros = durationNs / 1_000L
        return when {
            micros <= 0L -> 0
            micros >= FANOUT_HISTOGRAM_BUCKETS - 1 -> FANOUT_HISTOGRAM_BUCKETS - 1
            else -> micros.toInt()
        }
    }

    private fun bucketUpperBoundNs(bucket: Int): Long = bucket * 1_000L

    private fun isFanoutComplete(sendOutcome: FanOutSendOutcomeScratch): Boolean {
        if (sendOutcome.sendmmsgRequestedMessages > 0) {
            return sendOutcome.sendmmsgReturnedMessages == sendOutcome.sendmmsgRequestedMessages &&
                sendOutcome.sendmmsgErrno == 0
        }
        return sendOutcome.legsFailed == 0 && sendOutcome.legsAttempted == legCount
    }

    private fun notAttemptedLegs(sendOutcome: FanOutSendOutcomeScratch): Int {
        if (sendOutcome.sendmmsgRequestedMessages > 0) {
            if (sendOutcome.sendmmsgReturnedMessages == sendOutcome.sendmmsgRequestedMessages &&
                sendOutcome.sendmmsgErrno == 0
            ) {
                return 0
            }
            return (legCount - sendOutcome.sendmmsgReturnedMessages - 1).coerceAtLeast(0)
        }
        return 0
    }

    companion object {
        private const val FANOUT_HISTOGRAM_BUCKETS: Int = 12_000

        fun forConfig(
            context: Context,
            config: Gres3CapacityHarnessConfig,
            maxSlots: Int,
        ): FanOutMetricsBuffer =
            FanOutMetricsBuffer(
                maxSlots = maxSlots,
                legCount = config.endpoints.size,
                tailDiagnosticsEnabled = config.tailLatencyDiagnosticsEnabled,
                recordLegDetail = config.perLegSendTimingEnabled,
                stallContextFactory =
                    if (config.tailLatencyDiagnosticsEnabled) {
                        { FanOutStallContextCapture.capture(context) }
                    } else {
                        null
                    },
            )
    }
}

data class FanOutMetricsCounters(
    val emittedSlotCount: Int,
    val missedSlotCount: Int,
    val slotDeadlineMissCount: Int,
    val catchUpEmissionCount: Int,
    val sendFailCount: Int,
    val fanoutP99Ns: Long,
    val fanoutMaxNs: Long,
    val schedLatenessMaxNs: Long = 0L,
    val extremeTailStallCount: Int = 0,
    val sendmmsgPartialBatchCount: Int = 0,
    val sendmmsgMinReturnedMessages: Int = 0,
    val sendmmsgWorstSyscallWallNs: Long = 0L,
    val sendmmsgEagainCount: Int = 0,
    val notAttemptedCount: Int = 0,
    val perLegSendAttemptCount: Int = 0,
    val fanoutIncompleteSlotCount: Int = 0,
    val fanoutCompleteSlotCount: Int = 0,
    val slotExecutionCompletionMaxNs: Long = 0L,
)

data class FanOutSlotRecord(
    val slotIndex: Long,
    val schedLatenessNs: Long,
    val fanoutDurationNs: Long,
    val legsFailed: Int,
    val maxLegIndex: Int = -1,
    val maxLegDurationNs: Long = 0L,
)
