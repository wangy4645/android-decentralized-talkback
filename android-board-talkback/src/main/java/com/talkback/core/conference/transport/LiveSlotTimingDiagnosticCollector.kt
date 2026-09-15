package com.talkback.core.conference.transport

/**
 * Observation-only timing attribution for LIVE_SLOT / soak playout path.
 * Does not change Profile 03, jitter, deadline, or AudioTrack parameters.
 */
class LiveSlotTimingDiagnosticCollector(
    private val trailCap: Int = LiveSlotTimingDiagnosticConstants.TRAIL_CAP,
) {
    private val lateBySource = mutableMapOf<String, Long>()
    private var lateAtAdmit = 0L
    private var lateAtPull = 0L
    private var mediaTimeEqualsArrival = 0L
    private var admitSamples = 0L
    private val admitDeadlineSlackMs = ArrayList<Long>(8192)
    private val pullAgePastDeadlineMs = ArrayList<Long>(8192)
    private val arrivalMinusMediaMs = ArrayList<Long>(8192)

    private val writeIntervalsUs = ArrayList<Long>(8192)
    private var lastWriteWallMs: Long? = null
    private var cyclesWithLate = 0L
    private var cyclesWithUnderrun = 0L
    private var cyclesWithBoth = 0L
    private var cyclesWithNeither = 0L
    private var cyclesWithWriteGapOver30ms = 0L
    private var cyclesWithGapAndUnderrun = 0L
    private var emptyPcmTicks = 0L
    private var writeCycles = 0L
    private val lateBeforeUnderrunLagMs = ArrayList<Long>(1024)
    private var lastLateWallMs: Long? = null

    private val trail = ArrayList<Map<String, Any?>>(trailCap)
    private var trailDropped = 0L

    @Synchronized
    fun recordAdmit(
        source: String,
        slot: Long,
        rxWallMs: Long,
        mediaTimeMs: Long,
        usefulDeadlineMs: Long,
        disposition: String,
    ) {
        admitSamples += 1
        val slack = usefulDeadlineMs - rxWallMs
        admitDeadlineSlackMs += slack
        arrivalMinusMediaMs += (rxWallMs - mediaTimeMs)
        if (mediaTimeMs == rxWallMs) mediaTimeEqualsArrival += 1
        if (disposition == "LATE_FOR_PLAYOUT") {
            lateAtAdmit += 1
            lateBySource[source] = (lateBySource[source] ?: 0L) + 1L
            lastLateWallMs = rxWallMs
            appendTrail(
                mapOf(
                    "kind" to "ADMIT_LATE",
                    "source" to source,
                    "slot" to slot,
                    "rxWallMs" to rxWallMs,
                    "mediaTimeMs" to mediaTimeMs,
                    "usefulDeadlineMs" to usefulDeadlineMs,
                    "deadlineSlackMs" to slack,
                    "disposition" to disposition,
                ),
            )
        } else if (trail.size < trailCap / 5) {
            // sparse non-late admits for mapping baseline
            appendTrail(
                mapOf(
                    "kind" to "ADMIT",
                    "source" to source,
                    "slot" to slot,
                    "rxWallMs" to rxWallMs,
                    "mediaTimeMs" to mediaTimeMs,
                    "usefulDeadlineMs" to usefulDeadlineMs,
                    "deadlineSlackMs" to slack,
                    "disposition" to disposition,
                ),
            )
        }
    }

    @Synchronized
    fun recordPullLate(
        source: String,
        slot: Long,
        nowMs: Long,
        mediaTimeMs: Long,
        usefulDeadlineMs: Long,
    ) {
        lateAtPull += 1
        lateBySource[source] = (lateBySource[source] ?: 0L) + 1L
        val age = nowMs - usefulDeadlineMs
        pullAgePastDeadlineMs += age
        lastLateWallMs = nowMs
        appendTrail(
            mapOf(
                "kind" to "PULL_LATE",
                "source" to source,
                "slot" to slot,
                "rxWallMs" to null,
                "nowMs" to nowMs,
                "mediaTimeMs" to mediaTimeMs,
                "usefulDeadlineMs" to usefulDeadlineMs,
                "agePastDeadlineMs" to age,
            ),
        )
    }

    @Synchronized
    fun recordEmptyPcmTick(nowMs: Long) {
        emptyPcmTicks += 1
        if (trail.size < trailCap) {
            appendTrail(mapOf("kind" to "EMPTY_PCM_TICK", "nowMs" to nowMs))
        }
    }

    @Synchronized
    fun recordWriteCycle(
        writeWallMs: Long,
        underrunDelta: Long,
        lateDeltaThisCycle: Long,
        pcmSources: Int,
        decodeUs: Long,
        mixUs: Long,
        playoutBudgetUs: Long,
    ) {
        writeCycles += 1
        val intervalUs =
            lastWriteWallMs?.let { prev -> (writeWallMs - prev) * 1000L }
        lastWriteWallMs = writeWallMs
        if (intervalUs != null) {
            writeIntervalsUs += intervalUs
            if (intervalUs > 30_000L) cyclesWithWriteGapOver30ms += 1
        }
        val hasLate = lateDeltaThisCycle > 0
        val hasUnderrun = underrunDelta > 0
        when {
            hasLate && hasUnderrun -> {
                cyclesWithBoth += 1
                lastLateWallMs?.let { lateMs ->
                    lateBeforeUnderrunLagMs += (writeWallMs - lateMs)
                }
            }
            hasLate -> cyclesWithLate += 1
            hasUnderrun -> cyclesWithUnderrun += 1
            else -> cyclesWithNeither += 1
        }
        if (intervalUs != null && intervalUs > 30_000L && hasUnderrun) {
            cyclesWithGapAndUnderrun += 1
        }
        appendTrail(
            mapOf(
                "kind" to "WRITE",
                "writeWallMs" to writeWallMs,
                "writeIntervalUs" to intervalUs,
                "underrunDelta" to underrunDelta,
                "lateDelta" to lateDeltaThisCycle,
                "pcmSources" to pcmSources,
                "decodeUs" to decodeUs,
                "mixUs" to mixUs,
                "playoutBudgetUs" to playoutBudgetUs,
            ),
        )
    }

    @Synchronized
    fun snapshot(): Map<String, Any?> {
        val totalLate = lateAtAdmit + lateAtPull
        val mediaEqRate =
            if (admitSamples == 0L) {
                0.0
            } else {
                mediaTimeEqualsArrival.toDouble() / admitSamples.toDouble()
            }
        val q1Bucket =
            when {
                admitSamples == 0L -> "UNKNOWN"
                totalLate == 0L -> "NONE"
                lateAtAdmit > lateAtPull * 2 &&
                    SoakMetricsCollector.percentile(arrivalMinusMediaMs, 0.50) > 20L ->
                    "NETWORK_ARRIVAL"
                lateAtAdmit > lateAtPull * 2 -> "SLOT_DEADLINE_MAPPING"
                mediaEqRate > 0.95 && lateAtPull >= lateAtAdmit -> "PIPELINE_SCHEDULING"
                lateAtPull > 0 &&
                    SoakMetricsCollector.percentile(pullAgePastDeadlineMs, 0.50) > 0L ->
                    "PIPELINE_SCHEDULING"
                else -> "MIXED"
            }

        val writeP50 = SoakMetricsCollector.percentile(writeIntervalsUs, 0.50)
        val writeP95 = SoakMetricsCollector.percentile(writeIntervalsUs, 0.95)
        val gapRate =
            if (writeCycles == 0L) {
                0.0
            } else {
                cyclesWithWriteGapOver30ms.toDouble() / writeCycles.toDouble()
            }
        val underrunCycles = cyclesWithUnderrun + cyclesWithBoth
        val q2Bucket =
            when {
                underrunCycles == 0L -> "NONE"
                gapRate > 0.15 && cyclesWithGapAndUnderrun > underrunCycles / 3 ->
                    "PCM_SUPPLY_GAP"
                writeP50 in 15_000L..25_000L && gapRate < 0.05 && underrunCycles > 0 ->
                    "AUDIO_TRACK_BUFFER_CADENCE"
                gapRate > 0.10 || emptyPcmTicks > writeCycles / 5 -> "PCM_SUPPLY_GAP"
                underrunCycles > 0 -> "UNKNOWN"
                else -> "NONE"
            }

        val lateCycles = cyclesWithLate + cyclesWithBoth
        val underrunOnly = cyclesWithUnderrun + cyclesWithBoth
        val denom = (lateCycles + underrunOnly - cyclesWithBoth).coerceAtLeast(1L)
        val bothRate = cyclesWithBoth.toDouble() / denom.toDouble()
        val q3 =
            when {
                lateCycles == 0L && underrunOnly == 0L -> "NONE"
                bothRate >= 0.4 -> "STRONG_COOCCURRENCE"
                bothRate >= 0.15 -> "WEAK_COOCCURRENCE"
                else -> "WEAK_OR_INDEPENDENT"
            }

        return mapOf(
            "observationOnly" to true,
            "fixApplied" to true,
            "lateSamples" to
                mapOf(
                    "total" to totalLate,
                    "atAdmit" to lateAtAdmit,
                    "atPull" to lateAtPull,
                    "bySource" to lateBySource.toMap(),
                ),
            "admitMapping" to
                mapOf(
                    "admitSamples" to admitSamples,
                    "mediaTimeEqualsArrivalCount" to mediaTimeEqualsArrival,
                    "mediaTimeEqualsArrivalRate" to mediaEqRate,
                    "admitDeadlineSlackMs" to SoakMetricsCollector.percentileBlock(admitDeadlineSlackMs),
                    "arrivalMinusMediaMs" to SoakMetricsCollector.percentileBlock(arrivalMinusMediaMs),
                ),
            "pullLate" to
                mapOf(
                    "agePastDeadlineMs" to SoakMetricsCollector.percentileBlock(pullAgePastDeadlineMs),
                ),
            "writeCadence" to
                mapOf(
                    "writeCycles" to writeCycles,
                    "writeIntervalUs" to SoakMetricsCollector.percentileBlock(writeIntervalsUs),
                    "writeIntervalOver30msCount" to cyclesWithWriteGapOver30ms,
                    "emptyPcmTicks" to emptyPcmTicks,
                    "cyclesWithGapAndUnderrun" to cyclesWithGapAndUnderrun,
                ),
            "lateUnderrunCooccurrence" to
                mapOf(
                    "cyclesWithLateOnly" to cyclesWithLate,
                    "cyclesWithUnderrunOnly" to cyclesWithUnderrun,
                    "cyclesWithBoth" to cyclesWithBoth,
                    "cyclesWithNeither" to cyclesWithNeither,
                    "bothRateVsUnion" to bothRate,
                    "lateBeforeUnderrunLagMs" to
                        SoakMetricsCollector.percentileBlock(lateBeforeUnderrunLagMs),
                ),
            "attribution" to
                mapOf(
                    "q1Late" to q1Bucket,
                    "q2Underrun" to q2Bucket,
                    "q3Correlation" to q3,
                ),
            "trail" to
                mapOf(
                    "cap" to trailCap,
                    "kept" to trail.size,
                    "dropped" to trailDropped,
                    "rows" to trail.toList(),
                ),
        )
    }

    private fun appendTrail(row: Map<String, Any?>) {
        if (trail.size >= trailCap) {
            trailDropped += 1
            return
        }
        trail += row
    }
}
