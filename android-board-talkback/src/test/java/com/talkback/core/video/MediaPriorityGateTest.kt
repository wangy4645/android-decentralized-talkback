package com.talkback.core.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaPriorityGateTest {
    @Test
    fun pttBlocksVideoAndBulk_thenHoldThenRestore() {
        var now = 1_000L
        val gate = MediaPriorityGate(clockMs = { now }, restoreHoldMs = 400L)
        gate.onLocalVideoPublishing(true)
        assertTrue(gate.videoSendEnabled())
        assertFalse(gate.bulkAllowed())

        gate.onPtt(true)
        assertFalse(gate.videoSendEnabled())
        assertFalse(gate.bulkAllowed())
        assertEquals(0, gate.videoMaxBitrateBps())

        gate.onPtt(false)
        now = 1_100L
        assertTrue(gate.inRestoreHold())
        assertFalse(gate.videoSendEnabled())
        assertFalse(gate.bulkAllowed())

        now = 1_500L
        assertFalse(gate.inRestoreHold())
        assertTrue(gate.videoSendEnabled())
        assertFalse(gate.bulkAllowed())

        gate.onLocalVideoPublishing(false)
        assertTrue(gate.bulkAllowed())
    }
}
