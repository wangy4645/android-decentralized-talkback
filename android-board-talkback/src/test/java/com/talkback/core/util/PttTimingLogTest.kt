package com.talkback.core.util

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class PttTimingLogTest {
    @Before
    fun setUp() {
        PttTimingLog.resetForTest()
    }

    @After
    fun tearDown() {
        PttTimingLog.resetForTest()
    }

    @Test
    fun sinceGrant_measuredFromGrantApplied_notPttDown() {
        val sessionId = "grp:test"
        PttTimingLog.pttDown(sessionId)
        Thread.sleep(80L)
        PttTimingLog.grantApplied(sessionId)
        Thread.sleep(40L)
        PttTimingLog.captureOn(sessionId)

        val sample = requireNotNull(PttTimingLog.lastSampleForTest())
        assertTrue("sinceGrant must exclude pre-grant PTT wait", sample.first < 70L)
        assertFalse(sample.second)
    }

    @Test
    fun secondCaptureOn_sameSession_isWarmStart() {
        val sessionId = "grp:warm"
        PttTimingLog.grantApplied(sessionId)
        PttTimingLog.captureOn(sessionId)
        PttTimingLog.captureOn(sessionId)

        val sample = requireNotNull(PttTimingLog.lastSampleForTest())
        assertTrue(sample.second)
        assertEquals(2, PttTimingLog.sinceGrantSampleCount())
    }

    @Test
    fun emitP95Report_afterTwentyColdSamples_doesNotThrow() {
        repeat(20) { i ->
            PttTimingLog.recordSinceGrant((10 + i).toLong(), warmStart = false)
        }
        PttTimingLog.emitP95Report()
        assertEquals(20, PttTimingLog.sinceGrantSampleCount())
    }
}
