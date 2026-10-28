package com.talkback.core.conference.runtime

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ProductionPlayoutWriteTelemetryDeskTest {
    private val lines = mutableListOf<String>()

    @Before
    fun setUp() {
        ProductionPlayoutWriteTelemetry.resetForTest()
        ProductionPlayoutWriteTelemetry.testLogSink = { lines += it }
        ProductionPlayoutWriteTelemetry.bindTrackIdentity(audioSessionId = 1657, bufferFrames = 3840)
    }

    @After
    fun tearDown() {
        ProductionPlayoutWriteTelemetry.resetForTest()
    }

    @Test
    fun classify_failShortComplete() {
        assertEquals(
            ProductionPlayoutWriteTelemetry.WriteKind.FAIL,
            ProductionPlayoutWriteTelemetry.classify(-3, 960),
        )
        assertEquals(
            ProductionPlayoutWriteTelemetry.WriteKind.SHORT,
            ProductionPlayoutWriteTelemetry.classify(480, 960),
        )
        assertEquals(
            ProductionPlayoutWriteTelemetry.WriteKind.SHORT,
            ProductionPlayoutWriteTelemetry.classify(0, 960),
        )
        assertEquals(
            ProductionPlayoutWriteTelemetry.WriteKind.COMPLETE,
            ProductionPlayoutWriteTelemetry.classify(960, 960),
        )
    }

    @Test
    fun completeWrites_doNotEmitEveryCall() {
        repeat(40) { i ->
            recordComplete(elapsedMs = i * 20L, durationUs = 1_000L + i)
        }
        assertTrue(lines.none { it.startsWith(ProductionPlayoutWriteTelemetry.PHASE_STATS) })
        assertTrue(lines.none { it.startsWith(ProductionPlayoutWriteTelemetry.PHASE_ANOMALY) })
    }

    @Test
    fun oneSecondWindow_emitsPercentilesAndIdentity() {
        recordComplete(elapsedMs = 0L, durationUs = 1_000L)
        recordComplete(elapsedMs = 200L, durationUs = 2_000L, intervalMs = 20L)
        recordComplete(elapsedMs = 400L, durationUs = 10_000L, intervalMs = 21L)
        recordComplete(elapsedMs = 1_000L, durationUs = 3_000L, intervalMs = 20L)
        val stats = lines.filter { it.startsWith(ProductionPlayoutWriteTelemetry.PHASE_STATS) }
        assertEquals(1, stats.size)
        val line = stats.single()
        assertTrue(line.contains("playoutOwner=PRODUCTION"))
        assertTrue(line.contains("audioSessionId=1657"))
        assertTrue(line.contains("bufferFrames=3840"))
        assertTrue(line.contains("trackId=na"))
        assertTrue(line.contains("writeCalls=3"))
        assertTrue(line.contains("completeWrites=3"))
        assertTrue(line.contains("shortWrites=0"))
        assertTrue(line.contains("failWrites=0"))
        assertTrue(line.contains("requestedSamples=2880"))
        assertTrue(line.contains("writtenSamples=2880"))
        assertTrue(line.contains("writeDurationUsP50="))
        assertTrue(line.contains("writeDurationUsP95="))
        assertTrue(line.contains("writeDurationUsMax=10000"))
        assertTrue(line.contains("mixCycleIntervalMsMax=21"))
    }

    @Test
    fun shortAndFail_areDistinct_andAnomalyRateLimited() {
        ProductionPlayoutWriteTelemetry.record(
            elapsedMs = 0L,
            requestedSamples = 960,
            writtenSamples = 480,
            writeDurationUs = 500L,
            mixCycleIntervalMs = 20L,
            failedWrites = 0L,
            appUnderrunCount = 2L,
            intervalValid = true,
        )
        ProductionPlayoutWriteTelemetry.record(
            elapsedMs = 20L,
            requestedSamples = 960,
            writtenSamples = -6,
            writeDurationUs = 100L,
            mixCycleIntervalMs = 20L,
            failedWrites = 1L,
            appUnderrunCount = 2L,
            intervalValid = true,
        )
        val anomalies = lines.filter { it.startsWith(ProductionPlayoutWriteTelemetry.PHASE_ANOMALY) }
        assertEquals(1, anomalies.size)
        assertTrue(anomalies.single().contains("kind=SHORT"))
        assertFalse(anomalies.single().contains("kind=FAIL"))
        ProductionPlayoutWriteTelemetry.record(
            elapsedMs = 1_020L,
            requestedSamples = 960,
            writtenSamples = 960,
            writeDurationUs = 800L,
            mixCycleIntervalMs = 20L,
            failedWrites = 1L,
            appUnderrunCount = 3L,
            intervalValid = true,
        )
        val stats = lines.filter { it.startsWith(ProductionPlayoutWriteTelemetry.PHASE_STATS) }
        assertEquals(1, stats.size)
        val line = stats.single()
        assertTrue(line.contains("shortWrites=1"))
        assertTrue(line.contains("failWrites=1"))
        assertTrue(line.contains("completeWrites=0"))
        assertTrue(line.contains("writtenSamples=480"))
        assertTrue(line.contains("requestedSamples=1920"))
        assertTrue(line.contains("failedWrites=1"))
        assertTrue(line.contains("appUnderrunCount=2"))
    }

    private fun recordComplete(
        elapsedMs: Long,
        durationUs: Long,
        intervalMs: Long = 20L,
    ) {
        ProductionPlayoutWriteTelemetry.record(
            elapsedMs = elapsedMs,
            requestedSamples = 960,
            writtenSamples = 960,
            writeDurationUs = durationUs,
            mixCycleIntervalMs = intervalMs,
            failedWrites = 0L,
            appUnderrunCount = 0L,
            intervalValid = elapsedMs != 0L,
        )
    }
}
