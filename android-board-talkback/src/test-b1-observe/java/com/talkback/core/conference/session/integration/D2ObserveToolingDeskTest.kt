package com.talkback.core.conference.session.integration

import com.talkback.core.webrtc.CapturePcmAbDiagnostic
import com.talkback.core.webrtc.CapturePcmLevelDiagnostic
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class D2ObserveToolingDeskTest {
    @After
    fun tearDown() {
        D2SendChainRuntime.captureChainObserveEnabled = false
        D2SendChainRuntime.postApmMuxEnabled = false
        CapturePcmAbDiagnostic.enabled = false
        CapturePcmLevelDiagnostic.fieldObserveLineSink = null
        CapturePcmLevelDiagnostic.reset()
        D2CaptureObserveBridge.clear()
    }

    @Test
    fun observeBridge_installsFieldSinkWhenEnabled() {
        D2SendChainRuntime.captureChainObserveEnabled = true
        D2CaptureObserveBridge.installIfNeeded()
        org.junit.Assert.assertNotNull(CapturePcmLevelDiagnostic.fieldObserveLineSink)
    }

    @Test
    fun apmFieldSink_receivesWindowLineWhenSinkSet() {
        val lines = mutableListOf<String>()
        CapturePcmLevelDiagnostic.fieldObserveLineSink = { lines += it }
        CapturePcmLevelDiagnostic.enabled = true
        val pcm = ShortArray(480) { 8000 }
        val bytes = ByteArray(pcm.size * 2)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(pcm)
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        CapturePcmLevelDiagnostic.onAdmPcm16Samples(buf, 16, 48_000, 1, pcm.size, nowMs = 0L)
        CapturePcmLevelDiagnostic.onAdmPcm16Samples(buf.duplicate(), 16, 48_000, 1, pcm.size, nowMs = 500L)
        assertTrue(lines.any { it.contains("point=APM_IN") })
    }

    @Test
    fun pathTelemetry_recordsPreApmWhenObserveOn() {
        D2SendChainRuntime.captureChainObserveEnabled = true
        D2TxPathTelemetry.record("desk-session", D2TxPathTelemetry.Event.PRE_APM_FORWARD)
        assertEquals("PRE_APM", D2TxPathTelemetry.lastForwardedPath)
    }

    private fun assertEquals(
        expected: String,
        actual: String,
    ) {
        org.junit.Assert.assertEquals(expected, actual)
    }
}
