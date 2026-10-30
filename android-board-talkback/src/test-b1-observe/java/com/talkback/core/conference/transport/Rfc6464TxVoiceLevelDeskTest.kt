package com.talkback.core.conference.transport

import com.talkback.core.conference.aqc.PcmLevelMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class Rfc6464TxVoiceLevelDeskTest {
    @Test
    fun silenceMapsToLevel127() {
        val silent = ShortArray(320) { 0 }
        assertEquals(127, Rfc6464TxVoiceLevel.frameLevelDbov(silent))
    }

    @Test
    fun loudToneMapsToLowerDbovThanSilence() {
        val samples =
            ShortArray(320) { i ->
                (sin(2 * PI * i / 320.0) * 20_000).toInt().toShort()
            }
        val level = Rfc6464TxVoiceLevel.frameLevelDbov(samples)
        assertTrue(level < 40)
        val rmsDbfs = PcmLevelMetrics.rmsDbfs(samples)
        assertTrue(rmsDbfs > -20.0)
    }

    @Test
    fun wireBytePreservesLevelBits() {
        val wire = Rfc6464TxVoiceLevel.toWireByte(voiceActive = true, frameLevelDbov = 10)
        assertEquals(0x80, wire and 0x80)
        assertEquals(10, wire and 0x7F)
        val (v, lvl) = Rfc6464TxVoiceLevel.parseWireByte(wire)
        assertTrue(v)
        assertEquals(10, lvl)
    }
}
