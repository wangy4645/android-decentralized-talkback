package com.talkback.core.conference.session.integration

import com.talkback.core.conference.runtime.MixedBlock
import org.junit.Assert.assertEquals
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
    fun slowMix_repeatsLastFrameOnceThenSilence() {
        val pump = ProductionPlayoutDeadlinePump()
        val prepared = MixedBlock(ShortArray(960) { 7 }, 1, 0.1)
        pump.publishPrepared(prepared)
        assertSame(prepared, pump.blockForDeadlineWrite())
        assertSame(prepared, pump.blockForDeadlineWrite())
        assertTrue(pump.blockForDeadlineWrite().samples.isEmpty())
        assertEquals(0, pump.blockForDeadlineWrite().mixParticipantCount)
    }
}
