package com.talkback.core.grouphealth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupConvergenceTrackerTest {

    @Test
    fun convergenceAgeMs_withoutAnchor_isZero() {
        assertTrue(GroupConvergenceTracker.convergenceAgeMs(null, 10_000L) == 0L)
    }

    @Test
    fun convergenceAgeMs_withAnchor() {
        assertTrue(GroupConvergenceTracker.convergenceAgeMs(1_000L, 6_000L) == 5_000L)
    }

    @Test
    fun periodicBuilding_onlyWhenStalledAndWindowElapsed() {
        assertTrue(
            GroupConvergenceTracker.shouldEmitPeriodicBuilding(
                readiness = GroupTopologyReadiness.BUILDING,
                convergenceAgeMs = 15_000L,
                lastPeriodicMs = 0L,
                nowMs = 20_000L,
                stallThresholdMs = 10_000L,
                periodicWindowMs = 30_000L
            )
        )
        assertFalse(
            GroupConvergenceTracker.shouldEmitPeriodicBuilding(
                readiness = GroupTopologyReadiness.BUILDING,
                convergenceAgeMs = 5_000L,
                lastPeriodicMs = 0L,
                nowMs = 20_000L,
                stallThresholdMs = 10_000L,
                periodicWindowMs = 30_000L
            )
        )
        assertFalse(
            GroupConvergenceTracker.shouldEmitPeriodicBuilding(
                readiness = GroupTopologyReadiness.OPERATIONAL,
                convergenceAgeMs = 15_000L,
                lastPeriodicMs = 0L,
                nowMs = 20_000L,
                stallThresholdMs = 10_000L,
                periodicWindowMs = 30_000L
            )
        )
        assertFalse(
            GroupConvergenceTracker.shouldEmitPeriodicBuilding(
                readiness = GroupTopologyReadiness.BUILDING,
                convergenceAgeMs = 15_000L,
                lastPeriodicMs = 18_000L,
                nowMs = 20_000L,
                stallThresholdMs = 10_000L,
                periodicWindowMs = 30_000L
            )
        )
    }
}
