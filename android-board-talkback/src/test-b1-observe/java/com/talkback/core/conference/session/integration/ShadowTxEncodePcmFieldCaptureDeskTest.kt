package com.talkback.core.conference.session.integration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShadowTxEncodePcmFieldCaptureDeskTest {
    @Test
    fun parseDurationMs_clampsToMax() {
        assertEquals(45_000L, ShadowTxEncodePcmFieldCapture.parseDurationMs("45000"))
        assertEquals(120_000L, ShadowTxEncodePcmFieldCapture.parseDurationMs("999999"))
        assertEquals(1L, ShadowTxEncodePcmFieldCapture.parseDurationMs("0"))
    }

    @Test
    fun shouldStopCapture_whenDurationElapsed() {
        val start = 1_000L
        val limit = 30_000L
        assertFalse(
            ShadowTxEncodePcmFieldCapture.shouldStopCapture(
                startedWallMs = start,
                durationMs = limit,
                nowMs = start + limit - 1,
            ),
        )
        assertTrue(
            ShadowTxEncodePcmFieldCapture.shouldStopCapture(
                startedWallMs = start,
                durationMs = limit,
                nowMs = start + limit,
            ),
        )
    }
}
