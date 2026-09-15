package com.talkback.core.conference.runtime

/**
 * Observable playout metrics for Phase 1 Slice 3 (diagnostic only — not a gate).
 */
interface PlayoutMetricsSeam {
    val successfulWrites: Long
    val failedWrites: Long
    val underrunCount: Long
    val lastWriteDurationUs: Long
}
