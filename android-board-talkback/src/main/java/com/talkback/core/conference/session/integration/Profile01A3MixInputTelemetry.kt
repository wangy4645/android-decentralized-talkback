package com.talkback.core.conference.session.integration

import com.talkback.core.conference.runtime.MediaMixConstants
import com.talkback.core.conference.runtime.MixCycleResult
import com.talkback.core.conference.runtime.SourceMixInputKind
import com.talkback.core.conference.session.integration.cutover.AudibleOwnershipState
import com.talkback.core.conference.session.integration.cutover.ReplacementCutoverRc1
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * A3-Q1 — per-source mix-input PCM amplitude (behavior-neutral telemetry only).
 *
 * Aggregates REAL vs PLC/concealment PCM separately for each remote source entering the mixer,
 * plus mixed output stats on the same 25-cycle window as [Profile01A3MixOutputTelemetry].
 */
object Profile01A3MixInputTelemetry {
    const val PHASE = "A3_MIX_INPUT"
    const val OWNER_MULTICAST_PRODUCTION = Profile01A3MixOutputTelemetry.OWNER_MULTICAST_PRODUCTION
    private const val AGGREGATION_INTERVAL = 25L
    private const val SILENCE_ABS_THRESHOLD = 33
    private const val CLIP_ABS_THRESHOLD = 29_000

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
    ) {
        if (owner != OWNER_MULTICAST_PRODUCTION && !isTestEnabled()) return
        val agg = aggregators.computeIfAbsent(sessionId) { SessionAggregator() }
        agg.record(owner = owner, mixCycle = mixCycle)
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

    internal data class PcmAmplitudeStats(
        val frames: Int,
        val rmsDbfs: Double?,
        val peakDbfs: Double?,
        val clipSamples: Int,
        val silenceFrames: Int,
    )

    internal fun analyzePcm(samples: ShortArray): PcmAmplitudeStats {
        if (samples.isEmpty()) {
            return PcmAmplitudeStats(
                frames = 0,
                rmsDbfs = null,
                peakDbfs = null,
                clipSamples = 0,
                silenceFrames = 0,
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
        return PcmAmplitudeStats(
            frames = samples.size,
            rmsDbfs = linearToDbfs(rms),
            peakDbfs = linearToDbfs(peakAbs.toDouble()),
            clipSamples = clip,
            silenceFrames = silence,
        )
    }

    internal fun mergeBucket(
        bucket: AmplitudeBucket,
        stats: PcmAmplitudeStats,
        samples: ShortArray,
    ) {
        if (stats.frames <= 0) return
        bucket.frames += stats.frames
        bucket.clipSamples += stats.clipSamples
        bucket.silenceFrames += stats.silenceFrames
        for (sample in samples) {
            val v = sample.toDouble()
            bucket.sumSampleSquares += v * v
        }
        val peakAbs = samples.maxOfOrNull { kotlin.math.abs(it.toInt()) } ?: 0
        if (peakAbs.toDouble() > bucket.peakLinear) {
            bucket.peakLinear = peakAbs.toDouble()
        }
    }

    internal fun bucketSummary(bucket: AmplitudeBucket): PcmAmplitudeStats {
        if (bucket.frames <= 0) {
            return PcmAmplitudeStats(
                frames = 0,
                rmsDbfs = null,
                peakDbfs = null,
                clipSamples = 0,
                silenceFrames = 0,
            )
        }
        return PcmAmplitudeStats(
            frames = bucket.frames.toInt(),
            rmsDbfs = linearToDbfs(sqrt(bucket.sumSampleSquares / bucket.frames)),
            peakDbfs = if (bucket.peakLinear > 0.0) linearToDbfs(bucket.peakLinear) else null,
            clipSamples = bucket.clipSamples.toInt(),
            silenceFrames = bucket.silenceFrames.toInt(),
        )
    }

    internal fun formatSourceInput(
        source: String,
        real: PcmAmplitudeStats,
        plc: PcmAmplitudeStats,
    ): String =
        buildString {
            append(source)
            append(":realFrames=").append(real.frames)
            append(",realRmsDbfs=").append(real.rmsDbfs?.let { formatDbfs(it) } ?: "na")
            append(",realPeakDbfs=").append(real.peakDbfs?.let { formatDbfs(it) } ?: "na")
            append(",realClipSamples=").append(real.clipSamples)
            append(",realSilenceFrames=").append(real.silenceFrames)
            append(",plcFrames=").append(plc.frames)
            append(",plcRmsDbfs=").append(plc.rmsDbfs?.let { formatDbfs(it) } ?: "na")
            append(",plcPeakDbfs=").append(plc.peakDbfs?.let { formatDbfs(it) } ?: "na")
            append(",plcClipSamples=").append(plc.clipSamples)
            append(",plcSilenceFrames=").append(plc.silenceFrames)
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

    internal fun linearToDbfs(linear: Double): Double {
        if (linear <= 0.0) return -120.0
        val normalized = linear / MediaMixConstants.S16_FULL_SCALE
        return 20.0 * ln(normalized) / ln(10.0)
    }

    internal fun formatDbfs(dbfs: Double): String = String.format("%.1f", dbfs)

    internal class AmplitudeBucket {
        var frames: Long = 0
        var sumSampleSquares: Double = 0.0
        var peakLinear: Double = 0.0
        var clipSamples: Long = 0
        var silenceFrames: Long = 0
    }

    private class SessionAggregator {
        var owner: String = OWNER_MULTICAST_PRODUCTION
        private var cycleOrdinal: Long = 0
        private var windowCycles: Long = 0
        private val sourceReal = linkedMapOf<String, AmplitudeBucket>()
        private val sourcePlc = linkedMapOf<String, AmplitudeBucket>()
        private val observedSources = linkedSetOf<String>()
        private val mixedBucket = AmplitudeBucket()

        val pendingCycles: Long
            get() = windowCycles

        fun record(
            owner: String,
            mixCycle: MixCycleResult,
        ) {
            this.owner = owner
            cycleOrdinal++
            windowCycles++
            observedSources += mixCycle.topKIdentities
            observedSources += mixCycle.sourcePullDispositions.keys

            for ((source, snapshot) in mixCycle.sourceMixInputs) {
                val stats = analyzePcm(snapshot.samples)
                when (snapshot.kind) {
                    SourceMixInputKind.REAL ->
                        mergeBucket(
                            sourceReal.getOrPut(source) { AmplitudeBucket() },
                            stats,
                            snapshot.samples,
                        )
                    SourceMixInputKind.PLC ->
                        mergeBucket(
                            sourcePlc.getOrPut(source) { AmplitudeBucket() },
                            stats,
                            snapshot.samples,
                        )
                }
            }

            val mixedStats = Profile01A3MixOutputTelemetry.analyzeMixedPcm(mixCycle.mixedBlock.samples)
            if (mixedStats.outputFrames > 0) {
                mergeBucket(
                    mixedBucket,
                    PcmAmplitudeStats(
                        frames = mixedStats.outputFrames,
                        rmsDbfs = mixedStats.rmsDbfs,
                        peakDbfs = mixedStats.peakDbfs,
                        clipSamples = mixedStats.clipFrames,
                        silenceFrames = mixedStats.silenceFrames,
                    ),
                    mixCycle.mixedBlock.samples,
                )
            }
        }

        fun shouldEmitNow(): Boolean =
            cycleOrdinal > 0L && cycleOrdinal % AGGREGATION_INTERVAL == 0L

        fun resetWindow() {
            windowCycles = 0
            sourceReal.clear()
            sourcePlc.clear()
            observedSources.clear()
            mixedBucket.frames = 0
            mixedBucket.sumSampleSquares = 0.0
            mixedBucket.peakLinear = 0.0
            mixedBucket.clipSamples = 0
            mixedBucket.silenceFrames = 0
        }

        fun buildSummary(): Map<String, String> {
            val sourceKeys = observedSources.sorted()
            val sourceInput =
                sourceKeys.joinToString(separator = "|") { source ->
                    formatSourceInput(
                        source = source,
                        real = bucketSummary(sourceReal[source] ?: AmplitudeBucket()),
                        plc = bucketSummary(sourcePlc[source] ?: AmplitudeBucket()),
                    )
                }
            val mixed = bucketSummary(mixedBucket)
            return linkedMapOf(
                "owner" to owner,
                "cycles" to windowCycles.toString(),
                "sourceInput" to sourceInput,
                "mixedFrames" to mixed.frames.toString(),
                "mixedRmsDbfs" to (mixed.rmsDbfs?.let { formatDbfs(it) } ?: "na"),
                "mixedPeakDbfs" to (mixed.peakDbfs?.let { formatDbfs(it) } ?: "na"),
                "mixedClipSamples" to mixed.clipSamples.toString(),
                "mixedSilenceFrames" to mixed.silenceFrames.toString(),
            )
        }
    }
}
