package com.talkback.core.conference.session.integration

import android.os.SystemClock
import com.talkback.core.conference.runtime.MediaMixConstants
import com.talkback.core.conference.runtime.MixCycleResult
import com.talkback.core.conference.runtime.MixedBlock
import com.talkback.core.conference.runtime.SlotPullDisposition
import kotlin.math.abs
import com.talkback.core.conference.session.integration.cutover.AudibleOwnershipState
import com.talkback.core.conference.session.integration.cutover.ReplacementCutoverRc1
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * A3-M1 — mixer output observability (behavior-neutral telemetry only).
 *
 * Answers: during MULTICAST_PRODUCTION audible cycles, does the mixer produce PCM and which
 * remote sources actually contribute? Aggregated before AndroidAudioTrackPlayoutSeam write.
 */
object Profile01A3MixOutputTelemetry {
    data class TopKDeferralTickTelemetry(
        val rankedTopKCount: Int,
        val effectiveTopKCount: Int,
        val deferredSourceCount: Int,
        val pullSucceededSourceCount: Int,
        val mixProducedThisTick: Boolean,
        val allTopKUnpullableTick: Boolean,
    )

    const val PHASE = "A3_MIX_OUTPUT"
    const val PHASE_CYCLE = "A3_MIX_CYCLE"
    const val OWNER_MULTICAST_PRODUCTION = "MULTICAST_PRODUCTION"
    private const val AGGREGATION_INTERVAL = 25L
    private const val SILENCE_ABS_THRESHOLD = 33 // ~ -60 dBFS @ s16
    private const val CLIP_ABS_THRESHOLD = 29_000 // near 0.89 FS guard

    @Volatile
    var testLogSink: ((String) -> Unit)? = null

    private val aggregators =
        java.util.concurrent.ConcurrentHashMap<String, SessionAggregator>()

    fun isTestEnabled(): Boolean = testLogSink != null

    fun audioTrackOwnerLabel(): String =
        when (ReplacementCutoverRc1.currentState()) {
            AudibleOwnershipState.MULTICAST_ACTIVE -> OWNER_MULTICAST_PRODUCTION
            AudibleOwnershipState.CUTOVER_ARMED -> "CUTOVER_ARMED"
            AudibleOwnershipState.ANCHOR_ACTIVE, null -> "SHADOW_METRICS_ONLY"
        }

    fun resetForTest() {
        testLogSink = null
        aggregators.clear()
    }

    fun clearSession(sessionId: String) {
        aggregators.remove(sessionId)
    }

    fun recordCycle(
        sessionId: String,
        owner: String,
        mixCycle: MixCycleResult,
        playoutObserved: Boolean,
        pipelinePlcCount: Int? = null,
        deferralTick: TopKDeferralTickTelemetry? = null,
    ) {
        if (owner != OWNER_MULTICAST_PRODUCTION && !isTestEnabled()) return
        emitMixCycleObservation(sessionId, mixCycle, SystemClock.elapsedRealtime())
        val agg = aggregators.computeIfAbsent(sessionId) { SessionAggregator() }
        agg.record(
            owner = owner,
            mixCycle = mixCycle,
            playoutObserved = playoutObserved,
            pipelinePlcCount = pipelinePlcCount,
            deferralTick = deferralTick,
        )
        if (agg.shouldEmitNow()) {
            emitSummary(sessionId, agg)
        }
    }

    fun flushForTest(sessionId: String) {
        val agg = aggregators[sessionId] ?: return
        if (agg.pendingCycles > 0L) {
            emitSummary(sessionId, agg)
        }
    }

    private fun emitMixCycleObservation(
        sessionId: String,
        mixCycle: MixCycleResult,
        cycleTimeMs: Long,
    ) {
        val block = mixCycle.mixedBlock
        if (block.mixParticipantCount <= 0 && block.samples.isEmpty()) return
        val stats = analyzeMixedPcm(block.samples)
        val fields = linkedMapOf<String, String>(
            "mixCycleTimeMs" to cycleTimeMs.toString(),
            "mixParticipantCount" to block.mixParticipantCount.toString(),
        )
        block.blockDiagnostics?.let { diag ->
            fields["mixCombineMode"] = diag.combineMode
            fields["mixBlendDivisor"] = diag.mixBlendDivisor.toString()
            fields["mixActiveSpeechCount"] = diag.mixActiveSpeechCount.toString()
            fields["mixLimiterActive"] = diag.limiterActive.toString()
            fields["mixLimiterGain"] = String.format("%.4f", diag.limiterGain)
            if (diag.mixOutputMakeupGainDb > 0.0) {
                fields["mixOutputMakeupGainDb"] = String.format("%.1f", diag.mixOutputMakeupGainDb)
            }
        }
        stats.rmsDbfs?.let { fields["mixOutputRmsDbfs"] = String.format("%.2f", it) }
        Profile01ShadowRuntimeObservability.logPhase(
            phase = PHASE_CYCLE,
            sessionId = sessionId,
            fields = fields,
        )
        testLogSink?.invoke(
            buildString {
                append(PHASE_CYCLE)
                fields.forEach { (k, v) -> append(' ').append(k).append('=').append(v) }
            },
        )
    }

    private fun emitSummary(
        sessionId: String,
        agg: SessionAggregator,
    ) {
        if (agg.pendingCycles == 0L) return
        val (ingressLate, ingressReorder) =
            Profile01ShadowRuntimeObservability.snapshotIngressAnomalyCounts(sessionId)
        val summary = agg.buildSummary(ingressLate, ingressReorder)
        agg.resetWindow()
        Profile01ShadowRuntimeObservability.logPhase(
            phase = PHASE,
            sessionId = sessionId,
            fields = summary,
        )
        val line =
            buildString {
                append(PHASE)
                summary.forEach { (k, v) -> append(' ').append(k).append('=').append(v) }
            }
        testLogSink?.invoke(line)
    }

    internal data class MixedPcmStats(
        val outputFrames: Int,
        val rmsDbfs: Double?,
        val peakDbfs: Double?,
        val silenceFrames: Int,
        val clipFrames: Int,
    )

    internal enum class SourceContributionKind {
        REAL,
        PLC,
        EMPTY,
    }

    internal fun analyzeMixedPcm(samples: ShortArray): MixedPcmStats {
        if (samples.isEmpty()) {
            return MixedPcmStats(
                outputFrames = 0,
                rmsDbfs = null,
                peakDbfs = null,
                silenceFrames = 0,
                clipFrames = 0,
            )
        }
        var sumSq = 0.0
        var peakAbs = 0
        var silence = 0
        var clip = 0
        for (sample in samples) {
            val v = sample.toInt()
            sumSq += v.toDouble() * v.toDouble()
            val abs = kotlin.math.abs(v)
            if (abs > peakAbs) peakAbs = abs
            if (abs <= SILENCE_ABS_THRESHOLD) silence++
            if (abs >= CLIP_ABS_THRESHOLD) clip++
        }
        val rms = sqrt(sumSq / samples.size)
        return MixedPcmStats(
            outputFrames = samples.size,
            rmsDbfs = linearToDbfs(rms),
            peakDbfs = linearToDbfs(peakAbs.toDouble()),
            silenceFrames = silence,
            clipFrames = clip,
        )
    }

    internal fun classifySourceContribution(
        sourceIdentity: String,
        pullDisposition: SlotPullDisposition?,
        mixParticipantIdentities: Set<String>,
    ): SourceContributionKind {
        val inMix = sourceIdentity in mixParticipantIdentities
        val pull = pullDisposition ?: SlotPullDisposition.EMPTY
        return when {
            inMix && pull == SlotPullDisposition.DECODE_FRAME -> SourceContributionKind.REAL
            pull == SlotPullDisposition.PLC_SYNTHESIS ||
                pull == SlotPullDisposition.SILENCE_GAP -> SourceContributionKind.PLC
            else -> SourceContributionKind.EMPTY
        }
    }

    internal fun formatPercent(numerator: Int, denominator: Int): String {
        if (denominator <= 0) return "0%"
        val pct = (numerator * 100 + denominator / 2) / denominator
        return "${pct.coerceIn(0, 100)}%"
    }

    private fun linearToDbfs(linear: Double): Double {
        if (linear <= 0.0) return -120.0
        val normalized = linear / MediaMixConstants.S16_FULL_SCALE
        return 20.0 * ln(normalized) / ln(10.0)
    }

    private class SessionAggregator {
        var owner: String = OWNER_MULTICAST_PRODUCTION
        private var cycleOrdinal: Long = 0
        private var windowCycles: Long = 0
        private var sumSampleSquares: Double = 0.0
        private var totalOutputFrames: Long = 0
        private var totalSilenceFrames: Long = 0
        private var totalClipFrames: Long = 0
        private var peakLinear: Double = 0.0
        private val contributors = linkedSetOf<String>()
        private val sourceReal = linkedMapOf<String, Int>()
        private val sourcePlc = linkedMapOf<String, Int>()
        private val sourceEmpty = linkedMapOf<String, Int>()
        private var writeCalls: Long = 0
        private var writtenFrames: Long = 0
        private var windowPlcCountBaseline: Int? = null
        private var windowPlcCountEnd: Int? = null
        private var pullRealTotal: Int = 0
        private var pullPlcTotal: Int = 0
        /** Top-K window empty: includes NOT_SELECTED (no pullSlot) — not network loss rate. */
        private var pullEmptyTotal: Int = 0
        private var topKNotSelectedForPullTotal: Int = 0
        private var pullAttemptTotal: Int = 0
        private var pullAttemptRealTotal: Int = 0
        private var pullAttemptPlcTotal: Int = 0
        private var pullAttemptEmptyTotal: Int = 0
        private var mixCombineMode: String = BrightScreenMeetingMixPolicy.productionMixCombineMode.name
        private var mixNMin: Int = Int.MAX_VALUE
        private var mixNMax: Int = 0
        private var mixNSum: Long = 0
        private var limiterActiveCycles: Int = 0
        private var preLimitPeakAbsFsMax: Double = 0.0
        private var lastCycleRmsDbfs: Double? = null
        private var rmsAbsDeltaSum: Double = 0.0
        private var rmsAbsDeltaCount: Int = 0
        private var lastDeferralTick: TopKDeferralTickTelemetry? = null

        val pendingCycles: Long
            get() = windowCycles

        fun record(
            owner: String,
            mixCycle: MixCycleResult,
            playoutObserved: Boolean,
            pipelinePlcCount: Int?,
            deferralTick: TopKDeferralTickTelemetry?,
        ) {
            this.owner = owner
            if (deferralTick != null) {
                lastDeferralTick = deferralTick
            }
            cycleOrdinal++
            windowCycles++
            if (pipelinePlcCount != null) {
                if (windowPlcCountBaseline == null) {
                    windowPlcCountBaseline = pipelinePlcCount
                }
                windowPlcCountEnd = pipelinePlcCount
            }
            val block = mixCycle.mixedBlock
            val n = block.mixParticipantCount
            if (n > 0) {
                mixNSum += n
                if (n < mixNMin) mixNMin = n
                if (n > mixNMax) mixNMax = n
            }
            block.blockDiagnostics?.let { diag ->
                mixCombineMode = diag.combineMode
                if (diag.limiterActive) limiterActiveCycles++
                if (diag.preLimitPeakAbsFs > preLimitPeakAbsFsMax) {
                    preLimitPeakAbsFsMax = diag.preLimitPeakAbsFs
                }
            }
            val stats = analyzeMixedPcm(block.samples)
            stats.rmsDbfs?.let { rms ->
                lastCycleRmsDbfs?.let { prev ->
                    rmsAbsDeltaSum += abs(rms - prev)
                    rmsAbsDeltaCount++
                }
                lastCycleRmsDbfs = rms
            }
            if (stats.outputFrames > 0) {
                for (sample in mixCycle.mixedBlock.samples) {
                    val v = sample.toDouble()
                    sumSampleSquares += v * v
                }
                totalOutputFrames += stats.outputFrames
                totalSilenceFrames += stats.silenceFrames
                totalClipFrames += stats.clipFrames
                val peakAbs =
                    mixCycle.mixedBlock.samples.maxOfOrNull { kotlin.math.abs(it.toInt()) } ?: 0
                if (peakAbs.toDouble() > peakLinear) peakLinear = peakAbs.toDouble()
            }
            contributors += mixCycle.mixParticipantIdentities
            for (source in mixCycle.topKIdentities) {
                val pull = mixCycle.sourcePullDispositions[source]
                if (pull == null) {
                    topKNotSelectedForPullTotal++
                } else {
                    pullAttemptTotal++
                }
                when (
                    classifySourceContribution(
                        source,
                        pull,
                        mixCycle.mixParticipantIdentities,
                    )
                ) {
                    SourceContributionKind.REAL -> {
                        sourceReal[source] = (sourceReal[source] ?: 0) + 1
                        pullRealTotal++
                        if (pull != null) pullAttemptRealTotal++
                    }
                    SourceContributionKind.PLC -> {
                        sourcePlc[source] = (sourcePlc[source] ?: 0) + 1
                        pullPlcTotal++
                        if (pull != null) pullAttemptPlcTotal++
                    }
                    SourceContributionKind.EMPTY -> {
                        sourceEmpty[source] = (sourceEmpty[source] ?: 0) + 1
                        pullEmptyTotal++
                        if (pull != null) pullAttemptEmptyTotal++
                    }
                }
            }
            if (
                (owner == OWNER_MULTICAST_PRODUCTION || isTestEnabled()) &&
                    playoutObserved &&
                    stats.outputFrames > 0
            ) {
                writeCalls++
                writtenFrames += stats.outputFrames
            }
        }

        fun shouldEmitNow(): Boolean =
            cycleOrdinal > 0L && cycleOrdinal % AGGREGATION_INTERVAL == 0L

        fun resetWindow() {
            windowCycles = 0
            sumSampleSquares = 0.0
            totalOutputFrames = 0
            totalSilenceFrames = 0
            totalClipFrames = 0
            peakLinear = 0.0
            contributors.clear()
            sourceReal.clear()
            sourcePlc.clear()
            sourceEmpty.clear()
            writeCalls = 0
            writtenFrames = 0
            windowPlcCountBaseline = null
            windowPlcCountEnd = null
            pullRealTotal = 0
            pullPlcTotal = 0
            pullEmptyTotal = 0
            topKNotSelectedForPullTotal = 0
            pullAttemptTotal = 0
            pullAttemptRealTotal = 0
            pullAttemptPlcTotal = 0
            pullAttemptEmptyTotal = 0
            mixCombineMode = BrightScreenMeetingMixPolicy.productionMixCombineMode.name
            mixNMin = Int.MAX_VALUE
            mixNMax = 0
            mixNSum = 0
            limiterActiveCycles = 0
            preLimitPeakAbsFsMax = 0.0
            lastCycleRmsDbfs = null
            rmsAbsDeltaSum = 0.0
            rmsAbsDeltaCount = 0
            lastDeferralTick = null
        }

        fun buildSummary(
            ingressLateForPlayout: Int,
            ingressReorderExceeded: Int,
        ): Map<String, String> {
            val cycles = windowCycles.toInt()
            val aggregateRmsDbfs =
                if (totalOutputFrames > 0) {
                    linearToDbfs(sqrt(sumSampleSquares / totalOutputFrames))
                } else {
                    null
                }
            val aggregatePeakDbfs =
                if (peakLinear > 0.0) linearToDbfs(peakLinear) else null
            val sourceKeys =
                (sourceReal.keys + sourcePlc.keys + sourceEmpty.keys).sorted()
            val sourceContribution =
                sourceKeys.joinToString(separator = "; ") { source ->
                    val real = sourceReal[source] ?: 0
                    val plc = sourcePlc[source] ?: 0
                    val empty = sourceEmpty[source] ?: 0
                    "$source:real=${formatPercent(real, cycles)}(${real}),plc=${formatPercent(plc, cycles)}(${plc}),empty=${formatPercent(empty, cycles)}(${empty})"
                }
            val plcCountDelta =
                if (windowPlcCountBaseline != null && windowPlcCountEnd != null) {
                    windowPlcCountEnd!! - windowPlcCountBaseline!!
                } else {
                    null
                }
            val mixNAvg =
                if (cycles > 0 && mixNSum > 0L) {
                    mixNSum.toDouble() / cycles.toDouble()
                } else {
                    null
                }
            val rmsAbsDeltaAvg =
                if (rmsAbsDeltaCount > 0) {
                    rmsAbsDeltaSum / rmsAbsDeltaCount.toDouble()
                } else {
                    null
                }
            val preLimitPeakDbfsMax =
                if (preLimitPeakAbsFsMax > 0.0) {
                    linearToDbfs(preLimitPeakAbsFsMax * MediaMixConstants.S16_FULL_SCALE)
                } else {
                    null
                }
            val deferralFields =
                lastDeferralTick?.let { d ->
                    linkedMapOf(
                        "rankedTopKCount" to d.rankedTopKCount.toString(),
                        "effectiveTopKCount" to d.effectiveTopKCount.toString(),
                        "deferredSourceCount" to d.deferredSourceCount.toString(),
                        "pullSucceededSourceCount" to d.pullSucceededSourceCount.toString(),
                        "mixProducedThisTick" to d.mixProducedThisTick.toString(),
                        "allTopKUnpullableTick" to d.allTopKUnpullableTick.toString(),
                    )
                } ?: emptyMap()
            return linkedMapOf(
                "owner" to owner,
                "mixCombineMode" to mixCombineMode,
                "mixNMin" to (if (mixNMin == Int.MAX_VALUE) "na" else mixNMin.toString()),
                "mixNMax" to mixNMax.toString(),
                "mixNAvg" to (mixNAvg?.let { String.format("%.2f", it) } ?: "na"),
                "limiterActiveCycles" to limiterActiveCycles.toString(),
                "preLimitPeakDbfsMax" to (preLimitPeakDbfsMax?.let { formatDbfs(it) } ?: "na"),
                "rmsAbsDeltaAvgDb" to (rmsAbsDeltaAvg?.let { String.format("%.2f", it) } ?: "na"),
                "cycles" to cycles.toString(),
                "pullRealTotal" to pullRealTotal.toString(),
                "pullPlcTotal" to pullPlcTotal.toString(),
                "pullEmptyTotal" to pullEmptyTotal.toString(),
                "topKNotSelectedForPullTotal" to topKNotSelectedForPullTotal.toString(),
                "pullAttemptTotal" to pullAttemptTotal.toString(),
                "pullAttemptRealTotal" to pullAttemptRealTotal.toString(),
                "pullAttemptPlcTotal" to pullAttemptPlcTotal.toString(),
                "pullAttemptEmptyTotal" to pullAttemptEmptyTotal.toString(),
                "plcCountDelta" to (plcCountDelta?.toString() ?: "na"),
                "ingressLateForPlayout" to ingressLateForPlayout.toString(),
                "ingressReorderExceeded" to ingressReorderExceeded.toString(),
                "outputFrames" to totalOutputFrames.toString(),
                "rmsDbfs" to (aggregateRmsDbfs?.let { formatDbfs(it) } ?: "na"),
                "peakDbfs" to (aggregatePeakDbfs?.let { formatDbfs(it) } ?: "na"),
                "silenceFrames" to totalSilenceFrames.toString(),
                "clipFrames" to totalClipFrames.toString(),
                "contributors" to contributors.sorted().joinToString(","),
                "sourceContribution" to sourceContribution,
                "writeCalls" to writeCalls.toString(),
                "writtenFrames" to writtenFrames.toString(),
            ).apply { putAll(deferralFields) }
        }

        private fun formatDbfs(dbfs: Double): String = String.format("%.1f", dbfs)
    }
}
