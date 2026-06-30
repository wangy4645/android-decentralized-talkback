package com.talkback.core.grouphealth

/**
 * Pure helpers for ADR-0008 convergenceAgeMs and PERIODIC_BUILDING stall detection.
 */
object GroupConvergenceTracker {

    fun convergenceAgeMs(anchorMs: Long?, nowMs: Long = System.currentTimeMillis()): Long {
        val anchor = anchorMs ?: nowMs
        return (nowMs - anchor).coerceAtLeast(0L)
    }

    fun shouldEmitPeriodicBuilding(
        readiness: GroupTopologyReadiness,
        convergenceAgeMs: Long,
        lastPeriodicMs: Long,
        nowMs: Long,
        stallThresholdMs: Long,
        periodicWindowMs: Long
    ): Boolean {
        if (readiness != GroupTopologyReadiness.BUILDING) return false
        if (convergenceAgeMs <= stallThresholdMs) return false
        if (lastPeriodicMs > 0L && nowMs - lastPeriodicMs < periodicWindowMs) return false
        return true
    }
}
