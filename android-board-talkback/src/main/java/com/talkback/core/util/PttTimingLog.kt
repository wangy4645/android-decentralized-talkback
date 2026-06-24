package com.talkback.core.util

import java.util.concurrent.ConcurrentHashMap

/**
 * Stage A soak: PTT / grant / capture correlation for Invariant-F1 enforcement.
 *
 * Events: PTT_DOWN, GRANT_APPLIED, captureON, captureOFF, PTT_UP
 * Metrics: PTT_METRIC grantDelay, captureDelay, holdTime
 */
object PttTimingLog {
    private val pttDownMs = ConcurrentHashMap<String, Long>()
    private val grantAppliedMs = ConcurrentHashMap<String, Long>()

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
        pttDownMs[sessionId]?.let { down ->
            val grant = grantAppliedMs[sessionId]
            val sinceGrant = grant?.let { ts - it }
            val suffix = buildString {
                append(" captureDelay=${ts - down}ms")
                if (sinceGrant != null) append(" sinceGrant=${sinceGrant}ms")
            }
            TalkbackLog.i("PTT_METRIC session=$sessionId$suffix")
        }
        log("captureON", sessionId, ts)
    }

    fun captureOff(sessionId: String) = log("captureOFF", sessionId, System.currentTimeMillis())

    private fun log(event: String, sessionId: String, ts: Long) {
        TalkbackLog.i("PTT_TIMING event=$event session=$sessionId ts=$ts")
    }
}
