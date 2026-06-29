package com.talkback.core.util

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Stage A soak: PTT / grant / capture correlation for Invariant-F1 enforcement.
 *
 * Events: PTT_DOWN, GRANT_APPLIED, captureON, captureOFF, PTT_UP
 * Metrics: PTT_METRIC grantDelay, captureDelay, sinceGrant, holdTime
 *
 * Phase 1 (ADR-0004): sinceGrant = captureON - GRANT_APPLIED (R12). Samples feed P95 calibration
 * (timeout_violation_rate, false_yield_rate per ADR-0004).
 * via [emitP95Report] or `scripts/soak-summarize.ps1` log parsing.
 */
object PttTimingLog {
    private val pttDownMs = ConcurrentHashMap<String, Long>()
    private val grantAppliedMs = ConcurrentHashMap<String, Long>()
    private val captureCountBySession = ConcurrentHashMap<String, Int>()

    private data class SinceGrantSample(val ms: Long, val warmStart: Boolean)

    private val sinceGrantSamples = CopyOnWriteArrayList<SinceGrantSample>()

    fun pttDown(sessionId: String) {
        val ts = System.currentTimeMillis()
        pttDownMs[sessionId] = ts
        grantAppliedMs.remove(sessionId)
        log("PTT_DOWN", sessionId, ts)
    }

    fun pttUp(sessionId: String) {
        val ts = System.currentTimeMillis()
        val down = pttDownMs.remove(sessionId)
        val grant = grantAppliedMs.remove(sessionId)
        if (down != null) {
            val hold = ts - down
            val grantDelay = grant?.let { it - down }
            val suffix = buildString {
                append(" holdTime=${hold}ms")
                if (grantDelay != null) append(" grantDelay=${grantDelay}ms")
            }
            TalkbackLog.i("PTT_METRIC session=$sessionId$suffix")
        }
        log("PTT_UP", sessionId, ts)
    }

    fun grantApplied(sessionId: String) {
        val ts = System.currentTimeMillis()
        grantAppliedMs[sessionId] = ts
        pttDownMs[sessionId]?.let { down ->
            TalkbackLog.i("PTT_METRIC session=$sessionId grantDelay=${ts - down}ms")
        }
        log("GRANT_APPLIED", sessionId, ts)
    }

    fun captureOn(sessionId: String) {
        val ts = System.currentTimeMillis()
        val grant = grantAppliedMs[sessionId]
        val sinceGrant = grant?.let { ts - it }
        val priorCaptures = captureCountBySession.merge(sessionId, 1) { old, _ -> old + 1 } ?: 1
        val warmStart = priorCaptures > 1
        if (sinceGrant != null) {
            recordSinceGrant(sinceGrant, warmStart)
        }
        val suffix = buildString {
            pttDownMs[sessionId]?.let { down ->
                append(" captureDelay=${ts - down}ms")
            }
            if (sinceGrant != null) {
                append(" sinceGrant=${sinceGrant}ms warmStart=$warmStart")
            }
        }
        if (suffix.isNotEmpty()) {
            TalkbackLog.i("PTT_METRIC session=$sessionId$suffix")
        }
        log("captureON", sessionId, ts)
    }

    fun captureOff(sessionId: String) = log("captureOFF", sessionId, System.currentTimeMillis())

    /** Emit cold/warm P95 summary for on-device calibration (ADR-0004, ≥20 samples each). */
    fun emitP95Report() {
        val cold = sinceGrantSamples.filter { !it.warmStart }.map { it.ms }.sorted()
        val warm = sinceGrantSamples.filter { it.warmStart }.map { it.ms }.sorted()
        TalkbackLog.i(
            "PTT_P95_REPORT " +
                "cold_n=${cold.size} cold_P50=${percentile(cold, 50)} cold_P95=${percentile(cold, 95)} " +
                "warm_n=${warm.size} warm_P50=${percentile(warm, 50)} warm_P95=${percentile(warm, 95)}"
        )
    }

    fun sinceGrantSampleCount(): Int = sinceGrantSamples.size

    internal fun recordSinceGrant(ms: Long, warmStart: Boolean) {
        sinceGrantSamples.add(SinceGrantSample(ms, warmStart))
        if (sinceGrantSamples.size % 20 == 0) {
            emitP95Report()
        }
    }

    internal fun percentile(sorted: List<Long>, p: Int): String {
        if (sorted.isEmpty()) return "n/a"
        val idx = kotlin.math.ceil(p / 100.0 * sorted.size).toInt() - 1
        return sorted[idx.coerceIn(0, sorted.lastIndex)].toString()
    }

    internal fun resetForTest() {
        pttDownMs.clear()
        grantAppliedMs.clear()
        captureCountBySession.clear()
        sinceGrantSamples.clear()
    }

    internal fun lastSampleForTest(): Pair<Long, Boolean>? =
        sinceGrantSamples.lastOrNull()?.let { it.ms to it.warmStart }

    private fun log(event: String, sessionId: String, ts: Long) {
        TalkbackLog.i("PTT_TIMING event=$event session=$sessionId ts=$ts")
    }
}
