package com.talkback.core.media

import org.junit.Assert.assertEquals
import org.junit.Test

class ConferenceRecoveryPolicyTest {
    @Test
    fun defaults_matchAdr0018() {
        val policy = ConferenceRecoveryPolicy()
        assertEquals(8_000L, policy.checkingTimeoutMs)
        assertEquals(2, policy.maxIceRestartAttempts)
    }
}
