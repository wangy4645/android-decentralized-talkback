package com.talkback.core.conference.playout.spike

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Healthy source progress must be independent of unhealthy source progress.
 */
class PerSourcePlayoutSpikeDeskTest {
    @Test
    fun lateJoin_healthyContinuesWhileLateSourceStaysEmpty() {
        val m01 = source("M01")
        val m03 = source("M03")
        repeat(4) { m01.enqueue(tone(it)) }

        repeat(4) {
            val ticks = listOf(m01.tick(), m03.tick())
            assertEquals(SourceTickKind.REAL, ticks[0].kind)
            assertEquals(SourceTickKind.EMPTY, ticks[1].kind)
            assertNotNull(RenderMixTick.mix(ticks))
        }
        assertEquals(4, m01.realTicks)
        assertEquals(0, m03.realTicks)
    }

    @Test
    fun stale_healthySourcesStayReal_staleSourcePlc() {
        val m01 = source("M01")
        val m03 = source("M03")
        val m04 = source("M04")
        m03.enqueue(tone(1))
        m03.tick()

        repeat(3) {
            m01.enqueue(tone(10 + it))
            m04.enqueue(tone(20 + it))
            val ticks = listOf(m01.tick(), m03.tick(), m04.tick())
            assertEquals(SourceTickKind.REAL, ticks[0].kind)
            assertEquals(SourceTickKind.PLC, ticks[1].kind)
            assertEquals(SourceTickKind.REAL, ticks[2].kind)
            assertNotNull(RenderMixTick.mix(ticks))
        }
    }

    @Test
    fun hole_affectsOnlyThatSourceTick() {
        val m01 = source("M01")
        val m03 = source("M03")
        m01.enqueue(tone(1))
        m03.enqueue(tone(1))
        m01.tick()
        m03.tick()

        m01.enqueue(tone(2))
        val holed = listOf(m01.tick(), m03.tick())
        assertEquals(SourceTickKind.REAL, holed[0].kind)
        assertEquals(SourceTickKind.PLC, holed[1].kind)
        assertEquals(2, m01.realTicks)
        assertEquals(1, m03.plcTicks)
    }

    @Test
    fun recovery_restoresOnlyThatSource() {
        val m01 = source("M01")
        val m03 = source("M03")
        m01.enqueue(tone(1))
        m03.enqueue(tone(1))
        m01.tick()
        m03.tick()
        m03.tick()

        m01.enqueue(tone(2))
        m03.enqueue(tone(9))
        val recovered = listOf(m01.tick(), m03.tick())
        assertEquals(SourceTickKind.REAL, recovered[0].kind)
        assertEquals(SourceTickKind.REAL, recovered[1].kind)
        assertEquals(2, m01.realTicks)
        assertEquals(2, m03.realTicks)
        assertNull(recovered[1].pcm?.let { if (it.contentEquals(tone(1))) "stale" else null })
    }

    private fun source(id: String) = PerSourcePlayout(id)

    private fun tone(seed: Int): ShortArray = shortArrayOf(seed.toShort(), (seed + 1).toShort())
}
