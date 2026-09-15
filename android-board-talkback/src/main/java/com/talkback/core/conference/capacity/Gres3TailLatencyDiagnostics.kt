package com.talkback.core.conference.capacity

/**
 * H1d tail-latency capture thresholds (diagnostics only; C3 gates unchanged).
 */
object Gres3TailLatencyDiagnostics {
    const val FANOUT_TAIL_CAPTURE_NS: Long = 10_000_000L
    const val SCHED_TAIL_CAPTURE_NS: Long = 5_000_000L
    const val SLOT_EXEC_TAIL_CAPTURE_NS: Long = 15_000_000L
    const val EXTREME_STALL_NS: Long = 100_000_000L
    const val MAX_TAIL_EVENTS: Int = 128

    const val C3_FANOUT_P99_NS: Long = 5_000_000L
    const val C3_FANOUT_MAX_NS: Long = 10_000_000L

    const val SCHED_DOMINANCE_FRACTION: Double = 0.50
    const val LEG_DOMINANCE_FRACTION: Double = 0.80
    const val RECURRENT_SCHED_DOMINANT_TAIL_MIN_COUNT: Int = 2
}
