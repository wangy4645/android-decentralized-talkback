package com.talkback.core.conference.runtime

import com.talkback.core.conference.session.integration.Profile01ShadowRuntimeObservability

/**
 * Behavior-neutral production playout tick observability.
 * One-second fired/skipped aggregates; a full stage split only when tick interval >= 40 ms.
 */
internal object ProductionPlayoutTickTelemetry {
    const val PHASE_STATS = "PRODUCTION_PLAYOUT_TICK_STATS"
    const val PHASE_ANOMALY = "PRODUCTION_PLAYOUT_TICK_ANOMALY"
    const val PLAYOUT_OWNER = "PRODUCTION"
    const val WINDOW_MS = 1_000L
    const val ANOMALY_INTERVAL_US = 40_000L

    @Volatile
    var testLogSink: ((String) -> Unit)? = null

    private var windowStartElapsedMs = Long.MIN_VALUE
    private var lastFired = 0L
    private var lastSkipped = 0L
    private var windowBaseFired = 0L
    private var windowBaseSkipped = 0L
    private var windowTicks = 0
    private var windowAnomalies = 0
    private var armed = false

    fun resetForTest() {
        testLogSink = null
        windowStartElapsedMs = Long.MIN_VALUE
        lastFired = 0L
        lastSkipped = 0L
        windowBaseFired = 0L
        windowBaseSkipped = 0L
        windowTicks = 0
        windowAnomalies = 0
        armed = false
    }

    fun record(
        elapsedMs: Long,
        tickIntervalUs: Long,
        intervalValid: Boolean,
        lockWaitUs: Long,
        resolveUs: Long,
        decodeUs: Long,
        mixUs: Long,
        writeUs: Long,
        tickTotalUs: Long,
        skipReason: String,
        writtenSamples: Int,
        ticksFired: Long,
        ticksSkipped: Long,
    ) {
        if (!armed) {
            armed = true
            windowStartElapsedMs = elapsedMs
            windowBaseFired = ticksFired
            windowBaseSkipped = ticksSkipped
            lastFired = ticksFired
            lastSkipped = ticksSkipped
        } else if (windowStartElapsedMs != Long.MIN_VALUE &&
            elapsedMs - windowStartElapsedMs >= WINDOW_MS &&
            windowTicks > 0
        ) {
            emitStats()
            windowStartElapsedMs = elapsedMs
            windowBaseFired = lastFired
            windowBaseSkipped = lastSkipped
            windowTicks = 0
            windowAnomalies = 0
        }
        lastFired = ticksFired
        lastSkipped = ticksSkipped
        windowTicks++
        val longInterval = intervalValid && tickIntervalUs >= ANOMALY_INTERVAL_US
        if (longInterval) {
            windowAnomalies++
            emitAnomaly(
                tickIntervalUs = tickIntervalUs,
                lockWaitUs = lockWaitUs,
                resolveUs = resolveUs,
                decodeUs = decodeUs,
                mixUs = mixUs,
                writeUs = writeUs,
                tickTotalUs = tickTotalUs,
                skipReason = skipReason,
                writtenSamples = writtenSamples,
                ticksFired = ticksFired,
                ticksSkipped = ticksSkipped,
            )
        }
    }

    fun flush() {
        if (windowTicks > 0) {
            emitStats()
        }
        windowStartElapsedMs = Long.MIN_VALUE
        windowTicks = 0
        windowAnomalies = 0
        armed = false
    }

    private fun emitStats() {
        emit(
            PHASE_STATS,
            mapOf(
                "playoutOwner" to PLAYOUT_OWNER,
                "ticksFired" to lastFired.toString(),
                "ticksSkipped" to lastSkipped.toString(),
                "ticksFiredDelta" to (lastFired - windowBaseFired).toString(),
                "ticksSkippedDelta" to (lastSkipped - windowBaseSkipped).toString(),
                "windowTicks" to windowTicks.toString(),
                "anomalyCount" to windowAnomalies.toString(),
            ),
        )
    }

    private fun emitAnomaly(
        tickIntervalUs: Long,
        lockWaitUs: Long,
        resolveUs: Long,
        decodeUs: Long,
        mixUs: Long,
        writeUs: Long,
        tickTotalUs: Long,
        skipReason: String,
        writtenSamples: Int,
        ticksFired: Long,
        ticksSkipped: Long,
    ) {
        emit(
            PHASE_ANOMALY,
            mapOf(
                "playoutOwner" to PLAYOUT_OWNER,
                "tickIntervalUs" to tickIntervalUs.toString(),
                "tickTotalUs" to tickTotalUs.toString(),
                "lockWaitUs" to lockWaitUs.toString(),
                "resolveUs" to resolveUs.toString(),
                "decodeUs" to decodeUs.toString(),
                "mixUs" to mixUs.toString(),
                "writeUs" to writeUs.toString(),
                "skipReason" to skipReason,
                "writtenSamples" to writtenSamples.toString(),
                "ticksFired" to ticksFired.toString(),
                "ticksSkipped" to ticksSkipped.toString(),
            ),
        )
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
