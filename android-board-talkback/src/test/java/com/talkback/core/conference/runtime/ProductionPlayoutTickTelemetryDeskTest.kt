package com.talkback.core.conference.runtime

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ProductionPlayoutTickTelemetryDeskTest {
    private val lines = mutableListOf<String>()

    @Before
    fun setUp() {
        ProductionPlayoutTickTelemetry.resetForTest()
        ProductionPlayoutTickTelemetry.testLogSink = { lines += it }
    }

    @After
    fun tearDown() {
        ProductionPlayoutTickTelemetry.resetForTest()
    }

    @Test
    fun normalTicks_doNotEmitAnomaly() {
        record(
            elapsedMs = 0L,
            intervalUs = 20_000L,
            intervalValid = false,
            tickTotalUs = 800L,
        )
        record(
            elapsedMs = 20L,
            intervalUs = 20_000L,
            tickTotalUs = 900L,
        )
        assertTrue(lines.none { it.startsWith(ProductionPlayoutTickTelemetry.PHASE_ANOMALY) })
        assertTrue(lines.none { it.startsWith(ProductionPlayoutTickTelemetry.PHASE_STATS) })
    }

    @Test
    fun intervalAtLeast40ms_emitsFullSplit_andTickTotalIsIndependent() {
        record(elapsedMs = 0L, intervalUs = 20_000L, intervalValid = false, tickTotalUs = 700L)
        record(
            elapsedMs = 80L,
            intervalUs = 80_000L,
            lockWaitUs = 1_000L,
            resolveUs = 200L,
            decodeUs = 300L,
            mixUs = 400L,
            writeUs = 50L,
            tickTotalUs = 12_000L,
            skipReason = "TOP_K_BUFFER_EMPTY",
            writtenSamples = 0,
            ticksFired = 4L,
            ticksSkipped = 2L,
        )
        val anomaly = lines.filter { it.startsWith(ProductionPlayoutTickTelemetry.PHASE_ANOMALY) }
        assertEquals(1, anomaly.size)
        val line = anomaly.single()
        assertTrue(line.contains("tickIntervalUs=80000"))
        assertTrue(line.contains("tickTotalUs=12000"))
        assertTrue(line.contains("lockWaitUs=1000"))
        assertTrue(line.contains("resolveUs=200"))
        assertTrue(line.contains("decodeUs=300"))
        assertTrue(line.contains("mixUs=400"))
        assertTrue(line.contains("writeUs=50"))
        assertTrue(line.contains("skipReason=TOP_K_BUFFER_EMPTY"))
        assertTrue(line.contains("writtenSamples=0"))
        assertTrue(line.contains("playoutOwner=PRODUCTION"))
        val stageSum = 1000L + 200L + 300L + 400L + 50L
        assertTrue(12_000L > stageSum)
        assertFalse(line.contains("tickTotalUs=$stageSum"))
    }

    @Test
    fun oneSecondWindow_emitsFiredAndSkipped() {
        record(elapsedMs = 0L, intervalUs = 20_000L, intervalValid = false, ticksFired = 1L, ticksSkipped = 0L)
        record(elapsedMs = 20L, intervalUs = 20_000L, ticksFired = 2L, ticksSkipped = 1L)
        record(elapsedMs = 1_000L, intervalUs = 20_000L, ticksFired = 50L, ticksSkipped = 3L)
        val stats = lines.filter { it.startsWith(ProductionPlayoutTickTelemetry.PHASE_STATS) }
        assertEquals(1, stats.size)
        val line = stats.single()
        assertTrue(line.contains("ticksFired=2"))
        assertTrue(line.contains("ticksSkipped=1"))
        assertTrue(line.contains("ticksFiredDelta=1"))
        assertTrue(line.contains("ticksSkippedDelta=1"))
    }

    private fun record(
        elapsedMs: Long,
        intervalUs: Long,
        intervalValid: Boolean = true,
        lockWaitUs: Long = 0L,
        resolveUs: Long = 0L,
        decodeUs: Long = 0L,
        mixUs: Long = 0L,
        writeUs: Long = 0L,
        tickTotalUs: Long = 500L,
        skipReason: String = "none",
        writtenSamples: Int = 960,
        ticksFired: Long = 1L,
        ticksSkipped: Long = 0L,
    ) {
        ProductionPlayoutTickTelemetry.record(
            elapsedMs = elapsedMs,
            tickIntervalUs = intervalUs,
            intervalValid = intervalValid,
            lockWaitUs = lockWaitUs,
            resolveUs = resolveUs,
            decodeUs = decodeUs,
            mixUs = mixUs,
            writeUs = writeUs,
            tickTotalUs = tickTotalUs,
            skipReason = skipReason,
            writtenSamples = writtenSamples,
            ticksFired = ticksFired,
            ticksSkipped = ticksSkipped,
        )
    }
}
