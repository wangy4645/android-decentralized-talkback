package com.talkback.core.session

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConferencePeerPlaybackDebugOverrideTest {

    @After
    fun tearDown() {
        ConferencePeerPlaybackDebugOverride.clear()
    }

    @Test
    fun inactive_preservesProductionEligibility() {
        assertTrue(ConferencePeerPlaybackDebugOverride.effectivePlaybackEnabled(true, "M02"))
        assertFalse(ConferencePeerPlaybackDebugOverride.effectivePlaybackEnabled(false, "M02"))
    }

    @Test
    fun active_onlyListedPeersPlay() {
        ConferencePeerPlaybackDebugOverride.setIsolation(setOf("M02"))
        assertTrue(ConferencePeerPlaybackDebugOverride.effectivePlaybackEnabled(true, "M02"))
        assertFalse(ConferencePeerPlaybackDebugOverride.effectivePlaybackEnabled(true, "M03"))
    }

    @Test
    fun active_cannotForceOpenProductionIneligible() {
        ConferencePeerPlaybackDebugOverride.setIsolation(setOf("M02", "M03"))
        assertFalse(ConferencePeerPlaybackDebugOverride.effectivePlaybackEnabled(false, "M02"))
    }

    @Test
    fun clear_restoresProductionBehavior() {
        ConferencePeerPlaybackDebugOverride.setIsolation(setOf("M02"))
        ConferencePeerPlaybackDebugOverride.clear()
        assertTrue(ConferencePeerPlaybackDebugOverride.effectivePlaybackEnabled(true, "M03"))
        assertFalse(ConferencePeerPlaybackDebugOverride.isActive())
    }
}
