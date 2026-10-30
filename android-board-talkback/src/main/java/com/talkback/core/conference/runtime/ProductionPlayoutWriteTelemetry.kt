package com.talkback.core.conference.runtime

import com.talkback.core.conference.session.integration.Profile01ShadowRuntimeObservability

/**
 * Behavior-neutral production AudioTrack write observability.
 * Distinguishes complete / short / fail writes. Aggregates ~1s; anomalies are rate-limited.
 */
internal object ProductionPlayoutWriteTelemetry {
    const val PHASE_STATS = "PRODUCTION_PLAYOUT_WRITE_STATS"
    const val PHASE_ANOMALY = "PRODUCTION_PLAYOUT_WRITE_ANOMALY"
    const val PHASE_TRACK = "PRODUCTION_PLAYOUT_TRACK"
    const val PLAYOUT_OWNER = "PRODUCTION"
    const val WINDOW_MS = 1_000L
    const val SAMPLE_CAP = 64

    enum class WriteKind {
        COMPLETE,
        SHORT,
        FAIL,
    }

    @Volatile
    var testLogSink: ((String) -> Unit)? = null

    private const val TRACK_ID_UNAVAILABLE = "na"

    private val durationUs = LongArray(SAMPLE_CAP)
    private val intervalMs = LongArray(SAMPLE_CAP)
    private val sortScratch = LongArray(SAMPLE_CAP)

    private var durationCount = 0
    private var intervalCount = 0
    private var writeCalls = 0
    private var completeWrites = 0
    private var shortWrites = 0
    private var failWrites = 0
    private var requestedSamplesSum = 0L
    private var writtenSamplesSum = 0L
    private var windowStartElapsedMs = Long.MIN_VALUE
    private var lastAnomalyElapsedMs = Long.MIN_VALUE / 4
    private var audioSessionId = 0
    private var bufferFrames = 0
    private var appUnderrunCount = 0L
    private var failedWritesTotal = 0L

    fun classify(written: Int, requested: Int): WriteKind =
        when {
            written < 0 -> WriteKind.FAIL
            written < requested -> WriteKind.SHORT
            else -> WriteKind.COMPLETE
        }

        fun resetForTest() {
        testLogSink = null
        resetWindow(startElapsedMs = Long.MIN_VALUE)
        lastAnomalyElapsedMs = Long.MIN_VALUE / 4
        audioSessionId = 0
        bufferFrames = 0
        appUnderrunCount = 0L
        failedWritesTotal = 0L
    }

    fun bindTrackIdentity(audioSessionId: Int, bufferFrames: Int) {
        this.audioSessionId = audioSessionId
        this.bufferFrames = bufferFrames
    }

    fun logTrackIdentity() {
        emit(
            PHASE_TRACK,
            identityFields() +
                mapOf(
                    "event" to "ARMED",
                    "trackId" to TRACK_ID_UNAVAILABLE,
                ),
        )
    }

    fun record(
        elapsedMs: Long,
        requestedSamples: Int,
        writtenSamples: Int,
        writeDurationUs: Long,
        mixCycleIntervalMs: Long,
        failedWrites: Long,
        appUnderrunCount: Long,
        intervalValid: Boolean,
    ) {
        if (windowStartElapsedMs == Long.MIN_VALUE) {
            windowStartElapsedMs = elapsedMs
        } else if (elapsedMs - windowStartElapsedMs >= WINDOW_MS && writeCalls > 0) {
            emitStats()
            resetWindow(elapsedMs)
        }
        this.failedWritesTotal = failedWrites
        this.appUnderrunCount = appUnderrunCount
        writeCalls++
        requestedSamplesSum += requestedSamples.toLong()
        val kind = classify(writtenSamples, requestedSamples)
        when (kind) {
            WriteKind.FAIL -> failWrites++
            WriteKind.SHORT -> {
                shortWrites++
                writtenSamplesSum += writtenSamples.coerceAtLeast(0).toLong()
            }
            WriteKind.COMPLETE -> {
                completeWrites++
                writtenSamplesSum += writtenSamples.toLong()
            }
        }
        pushCapped(durationUs, durationCount, writeDurationUs).also { durationCount = it }
        if (intervalValid) {
            pushCapped(intervalMs, intervalCount, mixCycleIntervalMs).also { intervalCount = it }
        }
        if (kind != WriteKind.COMPLETE) {
            maybeLogAnomaly(
                elapsedMs = elapsedMs,
                kind = kind,
                requestedSamples = requestedSamples,
                writtenSamples = writtenSamples,
                writeDurationUs = writeDurationUs,
                mixCycleIntervalMs = mixCycleIntervalMs,
                intervalValid = intervalValid,
            )
        }
    }

    fun flush() {
        if (writeCalls > 0) {
            emitStats()
        }
        resetWindow(Long.MIN_VALUE)
    }

    private fun maybeLogAnomaly(
        elapsedMs: Long,
        kind: WriteKind,
        requestedSamples: Int,
        writtenSamples: Int,
        writeDurationUs: Long,
        mixCycleIntervalMs: Long,
        intervalValid: Boolean,
    ) {
        if (elapsedMs - lastAnomalyElapsedMs < WINDOW_MS) return
        lastAnomalyElapsedMs = elapsedMs
        emit(
            PHASE_ANOMALY,
            identityFields() +
                mapOf(
                    "kind" to kind.name,
                    "requestedSamples" to requestedSamples.toString(),
                    "writtenSamples" to writtenSamples.toString(),
                    "writeDurationUs" to writeDurationUs.toString(),
                    "mixCycleIntervalMs" to if (intervalValid) mixCycleIntervalMs.toString() else "na",
                    "failedWrites" to failedWritesTotal.toString(),
                    "appUnderrunCount" to appUnderrunCount.toString(),
                    "trackId" to TRACK_ID_UNAVAILABLE,
                ),
        )
    }

    private fun emitStats() {
        emit(
            PHASE_STATS,
            identityFields() +
                mapOf(
                    "writeCalls" to writeCalls.toString(),
                    "completeWrites" to completeWrites.toString(),
                    "shortWrites" to shortWrites.toString(),
                    "failWrites" to failWrites.toString(),
                    "requestedSamples" to requestedSamplesSum.toString(),
                    "writtenSamples" to writtenSamplesSum.toString(),
                    "failedWrites" to failedWritesTotal.toString(),
                    "appUnderrunCount" to appUnderrunCount.toString(),
                    "writeDurationUsP50" to percentile(durationUs, durationCount, 50).toString(),
                    "writeDurationUsP95" to percentile(durationUs, durationCount, 95).toString(),
                    "writeDurationUsMax" to maxOf(durationUs, durationCount).toString(),
                    "mixCycleIntervalMsP50" to percentile(intervalMs, intervalCount, 50).toString(),
                    "mixCycleIntervalMsP95" to percentile(intervalMs, intervalCount, 95).toString(),
                    "mixCycleIntervalMsMax" to maxOf(intervalMs, intervalCount).toString(),
                    "trackId" to TRACK_ID_UNAVAILABLE,
                ),
        )
    }

    private fun identityFields(): Map<String, String> =
        mapOf(
            "playoutOwner" to PLAYOUT_OWNER,
            "audioSessionId" to audioSessionId.toString(),
            "bufferFrames" to bufferFrames.toString(),
        )

    private fun resetWindow(startElapsedMs: Long) {
        durationCount = 0
        intervalCount = 0
        writeCalls = 0
        completeWrites = 0
        shortWrites = 0
        failWrites = 0
        requestedSamplesSum = 0L
        writtenSamplesSum = 0L
        windowStartElapsedMs = startElapsedMs
    }

    private fun pushCapped(store: LongArray, count: Int, value: Long): Int {
        if (count >= store.size) return count
        store[count] = value
        return count + 1
    }

    private fun percentile(store: LongArray, count: Int, p: Int): Long {
        if (count <= 0) return -1L
        val n = count.coerceAtMost(store.size)
        System.arraycopy(store, 0, sortScratch, 0, n)
        java.util.Arrays.sort(sortScratch, 0, n)
        val rank = ((p / 100.0) * (n - 1)).toInt().coerceIn(0, n - 1)
        return sortScratch[rank]
    }

    private fun maxOf(store: LongArray, count: Int): Long {
        if (count <= 0) return -1L
        val n = count.coerceAtMost(store.size)
        var max = store[0]
        for (i in 1 until n) {
            if (store[i] > max) max = store[i]
        }
        return max
    }

    private fun emit(phase: String, fields: Map<String, String>) {
        val sessionId = Profile01ShadowRuntimeObservability.activeSessionId ?: "-"
        Profile01ShadowRuntimeObservability.logPhase(
            phase = phase,
            sessionId = sessionId,
            conferenceId = Profile01ShadowRuntimeObservability.activeConferenceId,
            mediaKeyEpoch = Profile01ShadowRuntimeObservability.activeMediaKeyEpoch,
            fields = fields,
        )
        testLogSink?.invoke(
            buildString {
                append(phase)
                fields.forEach { (k, v) -> append(' ').append(k).append('=').append(v) }
            },
        )
    }
}
