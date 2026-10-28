package com.talkback.core.conference.session.integration

import com.talkback.core.conference.aqc.AqcRuntime
import com.talkback.core.conference.runtime.OpusCodecConstants
import com.talkback.core.webrtc.CapturePcmMeasurementPoint
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class D2SendChainDeskTest {
    @After
    fun tearDown() {
        D2SendChainRuntime.postApmMuxEnabled = false
        D2SendChainRuntime.captureChainObserveEnabled = false
        D2SendChainRuntime.sendHeadroomEnabled = false
        D2TxPathTelemetry.lastForwardedPath = "PRE_APM"
        AqcRuntime.resetToProductionDefaults()
    }

    @Test
    fun production_default_preApm_muxOff() {
        D2SendChainRuntime.postApmMuxEnabled = false
        assertTrue(Profile01ShadowMulticastTransmitSeam.usesPreApmMicFrameSource())
        assertEquals(
            CapturePcmMeasurementPoint.POST_HW_PRE_WEBRTC_APM,
            Profile01ShadowMulticastTransmitSeam.currentWireAuthorityMeasurementPoint(),
        )
    }

    @Test
    fun muxFlag_off_doesNotChangeDefaultPathLabel() {
        D2SendChainRuntime.postApmMuxEnabled = false
        D2TxPathTelemetry.lastForwardedPath = "POST_APM"
        assertTrue(Profile01ShadowMulticastTransmitSeam.usesPreApmMicFrameSource())
    }

    @Test
    fun canonicalChunk_rejectsWrongRate() {
        assertFalse(D2ConferenceMicFrameSource.isCanonicalChunk(16, 44_100, 1, 480))
        assertTrue(D2ConferenceMicFrameSource.isCanonicalChunk(16, 48_000, 1, 480))
    }

    @Test
    fun s1ToS2_identityWhenHeadroomOff() {
        D2SendChainRuntime.sendHeadroomEnabled = false
        val frame = frameAtDbfs(-24.0)
        val out = D2SendChainProcessor.processS1ToS2(frame)
        assertFalse(out.headroomApplied)
        assertEquals(frame.toList(), out.samples.toList())
        assertEquals(out.inputRmsDbfs, out.outputRmsDbfs, 0.1)
    }

    @Test
    fun s1ToS2_headroomAttenuatesHotPeak_notQuietSpeech() {
        D2SendChainRuntime.sendHeadroomEnabled = true
        val quiet = frameAtDbfs(-50.0)
        val quietOut = D2SendChainProcessor.processS1ToS2(quiet)
        assertFalse(quietOut.headroomApplied)
        assertEquals(quiet.toList(), quietOut.samples.toList())

        val hot = ShortArray(OpusCodecConstants.FRAME_SAMPLES_20MS) { 30_000 }
        val hotOut = D2SendChainProcessor.processS1ToS2(hot)
        assertTrue(hotOut.headroomApplied)
        assertTrue(hotOut.limiterScale < 1f)
        assertTrue(hotOut.outputRmsDbfs < hotOut.inputRmsDbfs)
    }

    private fun frameAtDbfs(targetDbfs: Double): ShortArray {
        val n = OpusCodecConstants.FRAME_SAMPLES_20MS
        val amp = (32767.0 * Math.pow(10.0, targetDbfs / 20.0)).toInt().coerceAtLeast(1)
        return ShortArray(n) { amp.toShort() }
    }
}
