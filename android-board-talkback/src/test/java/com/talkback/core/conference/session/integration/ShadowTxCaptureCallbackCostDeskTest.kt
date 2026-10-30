package com.talkback.core.conference.session.integration

import org.junit.Assert.assertEquals
import org.junit.Test

class ShadowTxCaptureCallbackCostDeskTest {
    @Test
    fun percentile_emptyIsSentinel() {
        assertEquals(-1L, Profile01ShadowRuntimeObservability.percentileUs(LongArray(0), 50))
    }

    @Test
    fun percentile_p50AndP95() {
        val sorted = longArrayOf(1, 2, 3, 4, 10)
        assertEquals(3L, Profile01ShadowRuntimeObservability.percentileUs(sorted, 50))
        assertEquals(10L, Profile01ShadowRuntimeObservability.percentileUs(sorted, 95))
    }

    @Test
    fun costLog_isOneInTwentyFive() {
        fun shouldLog(n: Long) = n == 1L || n % 25L == 0L
        assertEquals(true, shouldLog(1))
        assertEquals(false, shouldLog(2))
        assertEquals(true, shouldLog(25))
        assertEquals(true, shouldLog(50))
    }
}
