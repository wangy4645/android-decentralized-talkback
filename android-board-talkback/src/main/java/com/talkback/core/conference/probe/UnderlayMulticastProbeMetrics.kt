package com.talkback.core.conference.probe

import kotlin.math.abs

/**
 * Receiver-side underlay statistics for dual-node probe adjudication.
 */
class UnderlayMulticastProbeMetrics(
    private val runIdHash: Int,
) {
    var receivedCount: Int = 0
        private set
    var duplicateCount: Int = 0
        private set
    var foreignRunCount: Int = 0
        private set
    var outOfOrderCount: Int = 0
        private set

    var firstSeq: Long? = null
        private set
    var lastSeq: Long? = null
        private set

    var maxConsecutiveLoss: Int = 0
        private set

    private var maxInterArrivalGapMs: Long? = null
    private var interArrivalGapOver120MsCount: Int = 0

    private val interArrivalMs = mutableListOf<Long>()
    private val interArrivalJitterMs = mutableListOf<Long>()
    private val oneWayDelayMs = mutableListOf<Long>()
    private val seenSeq = mutableSetOf<Long>()
    private var lastArrivalMs: Long? = null
    private var lastSeenSeq: Long? = null

    fun onPacket(
        packet: UnderlayMulticastProbePacket,
        arrivalWallMs: Long,
    ) {
        if (packet.runIdHash != runIdHash) {
            foreignRunCount += 1
            return
        }
        if (!seenSeq.add(packet.seq)) {
            duplicateCount += 1
            return
        }

        if (firstSeq == null) {
            firstSeq = packet.seq
        }
        lastSeq = packet.seq
        receivedCount += 1

        val prevSeq = lastSeenSeq
        if (prevSeq != null) {
            if (packet.seq < prevSeq) {
                outOfOrderCount += 1
            } else if (packet.seq > prevSeq + 1) {
                val gap = (packet.seq - prevSeq - 1).toInt()
                if (gap > maxConsecutiveLoss) {
                    maxConsecutiveLoss = gap
                }
            }
        }
        lastSeenSeq = packet.seq

        val prevArrival = lastArrivalMs
        if (prevArrival != null) {
            val delta = arrivalWallMs - prevArrival
            interArrivalMs += delta
            interArrivalJitterMs += abs(delta - UnderlayMulticastProbeConstants.NOMINAL_INTERVAL_MS)
            maxInterArrivalGapMs =
                maxOf(maxInterArrivalGapMs ?: delta, delta)
            if (delta > UnderlayMulticastProbeConstants.FROZEN_MAX_PLAYOUT_DELAY_MS) {
                interArrivalGapOver120MsCount += 1
            }
        }
        lastArrivalMs = arrivalWallMs

        val delay = arrivalWallMs - packet.sendWallMs
        if (delay >= 0) {
            oneWayDelayMs += delay
        }
    }

    fun percentile(values: List<Long>, p: Double): Long? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val idx = ((sorted.size - 1) * p).toInt().coerceIn(0, sorted.lastIndex)
        return sorted[idx]
    }

    fun snapshot(): Map<String, Any?> =
        mapOf(
            "receivedCount" to receivedCount,
            "duplicateCount" to duplicateCount,
            "foreignRunCount" to foreignRunCount,
            "outOfOrderCount" to outOfOrderCount,
            "firstSeq" to firstSeq,
            "lastSeq" to lastSeq,
            "maxConsecutiveLoss" to maxConsecutiveLoss,
            "interArrivalMsP50" to percentile(interArrivalMs, 0.50),
            "interArrivalMsP95" to percentile(interArrivalMs, 0.95),
            "interArrivalMsP99" to percentile(interArrivalMs, 0.99),
            "maxInterArrivalGapMs" to maxInterArrivalGapMs,
            "interArrivalGapOver120MsCount" to interArrivalGapOver120MsCount,
            "interArrivalJitterMsP50" to percentile(interArrivalJitterMs, 0.50),
            "interArrivalJitterMsP95" to percentile(interArrivalJitterMs, 0.95),
            "interArrivalJitterMsP99" to percentile(interArrivalJitterMs, 0.99),
            "oneWayDelayMsP50" to percentile(oneWayDelayMs, 0.50),
            "oneWayDelayMsP95" to percentile(oneWayDelayMs, 0.95),
            "oneWayDelayMsP99" to percentile(oneWayDelayMs, 0.99),
            "oneWayDelayNote" to
                "wall-clock estimate; not NTP-synced — use inter-arrival jitter as primary gate",
        )
}
