package com.talkback.core.conference.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class Rfc6464TxVoiceLevelTest {
    @Test
    fun silenceMapsToHighLevelAndInactiveAfterHangover() {
        val silent = ShortArray(320) { 0 }
        val level = Rfc6464TxVoiceLevel.frameLevelDbov(silent)
        assertEquals(127, level)
        val hangover = TxVoiceActivityHangover(activeWhenLevelAtOrBelow = 55, hangoverFrames = 2)
        assertFalse(hangover.observeFrameLevel(level))
        val wire = Rfc6464TxVoiceLevel.toWireByte(false, level)
        assertEquals(127, wire and 0x7F)
        assertEquals(0, wire and 0x80)
    }

    @Test
    fun loudToneMapsToLowerLevelAndActiveWithHangover() {
        val samples =
            ShortArray(320) { i ->
                (sin(2 * PI * i / 320.0) * 20_000).toInt().toShort()
            }
        val level = Rfc6464TxVoiceLevel.frameLevelDbov(samples)
        assertTrue(level < 40)
        val hangover = TxVoiceActivityHangover(activeWhenLevelAtOrBelow = 55, hangoverFrames = 2)
        assertTrue(hangover.observeFrameLevel(level))
        val wire = Rfc6464TxVoiceLevel.toWireByte(true, level)
        assertTrue((wire and 0x80) != 0)
        assertEquals(level, wire and 0x7F)
        // Two silent frames still active (hangover)
        assertTrue(hangover.observeFrameLevel(127))
        assertTrue(hangover.observeFrameLevel(127))
        assertFalse(hangover.observeFrameLevel(127))
    }

    @Test
    fun wireRoundTrip() {
        val (v, lvl) = Rfc6464TxVoiceLevel.parseWireByte(0x8A)
        assertTrue(v)
        assertEquals(10, lvl)
    }
}
