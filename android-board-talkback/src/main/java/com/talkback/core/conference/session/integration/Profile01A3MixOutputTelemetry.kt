package com.talkback.core.conference.session.integration

import com.talkback.core.conference.runtime.MediaMixConstants
import com.talkback.core.conference.runtime.MixCycleResult
import com.talkback.core.conference.runtime.MixedBlock
import com.talkback.core.conference.runtime.SlotPullDisposition
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
    const val PHASE = "A3_MIX_OUTPUT"
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
    ) {
        if (owner != OWNER_MULTICAST_PRODUCTION && !isTestEnabled()) return
        val agg = aggregators.computeIfAbsent(sessionId) { SessionAggregator() }
        agg.record(
            owner = owner,
            mixCycle = mixCycle,
            playoutObserved = playoutObserved,
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

    private fun emitSummary(
        sessionId: String,
        agg: SessionAggregator,
    ) {
        if (agg.pendingCycles == 0L) return
        val summary = agg.buildSummary()
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

        val pendingCycles: Long
            get() = windowCycles

        fun record(
            owner: String,
            mixCycle: MixCycleResult,
            playoutObserved: Boolean,
        ) {
            this.owner = owner
            cycleOrdinal++
            windowCycles++
            val stats = analyzeMixedPcm(mixCycle.mixedBlock.samples)
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
            for ((source, pull) in mixCycle.sourcePullDispositions) {
                when (
                    classifySourceContribution(
                        source,
                        pull,
                        mixCycle.mixParticipantIdentities,
                    )
                ) {
                    SourceContributionKind.REAL -> sourceReal[source] = (sourceReal[source] ?: 0) + 1
                    SourceContributionKind.PLC -> sourcePlc[source] = (sourcePlc[source] ?: 0) + 1
                    SourceContributionKind.EMPTY -> sourceEmpty[source] = (sourceEmpty[source] ?: 0) + 1
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
        }

        fun buildSummary(): Map<String, String> {
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
                    "$source:real=${formatPercent(real, cycles)},plc=${formatPercent(plc, cycles)},empty=${formatPercent(empty, cycles)}"
                }
            return linkedMapOf(
                "owner" to owner,
                "cycles" to cycles.toString(),
                "outputFrames" to totalOutputFrames.toString(),
                "rmsDbfs" to (aggregateRmsDbfs?.let { formatDbfs(it) } ?: "na"),
                "peakDbfs" to (aggregatePeakDbfs?.let { formatDbfs(it) } ?: "na"),
                "silenceFrames" to totalSilenceFrames.toString(),
                "clipFrames" to totalClipFrames.toString(),
                "contributors" to contributors.sorted().joinToString(","),
                "sourceContribution" to sourceContribution,
                "writeCalls" to writeCalls.toString(),
                "writtenFrames" to writtenFrames.toString(),
            )
        }

        private fun formatDbfs(dbfs: Double): String = String.format("%.1f", dbfs)
    }
}
