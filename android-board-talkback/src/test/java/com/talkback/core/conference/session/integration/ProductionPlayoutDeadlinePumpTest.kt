package com.talkback.core.conference.session.integration

import com.talkback.core.conference.runtime.MixedBlock
import com.talkback.core.conference.runtime.OpusCodecConstants
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductionPlayoutDeadlinePumpTest {
    @Test
    fun beforeFirstMix_writesEmptySoTrackCanKeepalive() {
        val pump = ProductionPlayoutDeadlinePump()
        assertTrue(pump.blockForDeadlineWrite().samples.isEmpty())
    }

    @Test
    fun afterPublish_deadlineWriteUsesPreparedBlockWithoutWaitingForNextMix() {
        val pump = ProductionPlayoutDeadlinePump()
        val prepared = MixedBlock(ShortArray(960) { 12 }, 1, 0.1)
        pump.publishPrepared(prepared)
        assertSame(prepared, pump.blockForDeadlineWrite())
    }

    @Test
    fun consecutiveUniqueMixes_areSampleIdentical_noFade() {
        val pump = ProductionPlayoutDeadlinePump()
        val a = toneBlock(8_000)
        val b = toneBlock(9_000)
        pump.publishPrepared(a)
        val wroteA = pump.blockForDeadlineWrite()
        assertSame(a, wroteA)
        assertArrayEquals(a.samples, wroteA.samples)
        pump.publishPrepared(b)
        val wroteB = pump.blockForDeadlineWrite()
        assertSame(b, wroteB)
        assertArrayEquals(b.samples, wroteB.samples)
    }

    @Test
    fun stall_fadesOutLastUniqueFrame_thenEmpty_neverRepeatsRawPcm() {
        val pump = ProductionPlayoutDeadlinePump()
        val prepared = toneBlock(7)
        pump.publishPrepared(prepared)
        val first = pump.blockForDeadlineWrite()
        assertSame(prepared, first)
        assertArrayEquals(prepared.samples, first.samples)

        val faded = pump.blockForDeadlineWrite()
        assertNotSame(prepared, faded)
        assertEquals(OpusCodecConstants.FRAME_SAMPLES_20MS, faded.samples.size)
        assertArrayEquals(ProductionPlayoutDeadlinePump.fadeOut(prepared.samples), faded.samples)
        assertFalse(faded.samples.contentEquals(prepared.samples))
        assertEquals(prepared.samples[0], faded.samples[0])
        assertEquals(0, faded.samples[faded.samples.lastIndex].toInt())

        val empty = pump.blockForDeadlineWrite()
        assertTrue(empty.samples.isEmpty())
        assertEquals(0, empty.mixParticipantCount)
        assertTrue(pump.blockForDeadlineWrite().samples.isEmpty())
    }

    @Test
    fun recoverAfterStall_fadesInFirst2p5ms_restUnchanged() {
        val pump = ProductionPlayoutDeadlinePump()
        val first = toneBlock(4_000)
        pump.publishPrepared(first)
        pump.blockForDeadlineWrite()
        pump.blockForDeadlineWrite()
        assertTrue(pump.blockForDeadlineWrite().samples.isEmpty())

        val recovered = toneBlock(12_000)
        pump.publishPrepared(recovered)
        val wrote = pump.blockForDeadlineWrite()
        assertNotSame(recovered, wrote)
        val expected = ProductionPlayoutDeadlinePump.fadeIn(recovered.samples)
        assertArrayEquals(expected, wrote.samples)
        val fade = ProductionPlayoutDeadlinePump.FADE_IN_SAMPLES
        for (i in fade until recovered.samples.size) {
            assertEquals(recovered.samples[i], wrote.samples[i])
        }
        assertEquals(0, wrote.samples[0].toInt())
    }

    private fun toneBlock(value: Int): MixedBlock =
        MixedBlock(
            samples = ShortArray(OpusCodecConstants.FRAME_SAMPLES_20MS) { value.toShort() },
            mixParticipantCount = 1,
            peakAbsFs = 0.1,
        )
}
