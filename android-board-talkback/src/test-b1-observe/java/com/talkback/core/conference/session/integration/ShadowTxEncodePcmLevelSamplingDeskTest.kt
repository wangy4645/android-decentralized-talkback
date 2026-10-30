package com.talkback.core.conference.session.integration

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mirrors [Profile01ShadowRuntimeObservability] 1-in-25 encode PCM level sampling. */
class ShadowTxEncodePcmLevelSamplingDeskTest {
    private fun shouldLog(packetOrdinal: Long): Boolean =
        packetOrdinal == 1L || packetOrdinal % 25L == 0L

    @Test
    fun logsFirstFrameAndEveryTwentyFifth() {
        assertTrue(shouldLog(1))
        assertFalse(shouldLog(2))
        assertFalse(shouldLog(24))
        assertTrue(shouldLog(25))
        assertTrue(shouldLog(50))
    }
}
