package com.talkback.core.conference.session.integration

import android.media.AudioFormat
import com.talkback.core.webrtc.CapturePcmAbDiagnostic
import com.talkback.core.webrtc.CapturePcmLevelDiagnostic
import com.talkback.core.webrtc.PostApmPcmDispatcher
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.webrtc.audio.JavaAudioDeviceModule
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** S1 observe chain: ADM tap + native APM_OUT without production sinks. */
class D2S1ObserveChainDeskTest {
    @After
    fun tearDown() {
        CapturePcmAbDiagnostic.enabled = false
        CapturePcmLevelDiagnostic.fieldObserveLineSink = null
        CapturePcmLevelDiagnostic.reset()
        D2CaptureObserveBridge.clear()
    }

    @Test
    fun admInjection_emitsApmInWindowWithShadowFields() {
        val detailLines = mutableListOf<String>()
        CapturePcmAbDiagnostic.enabled = true
        CapturePcmLevelDiagnostic.fieldObserveLineSink = { detailLines += it }

        val pcm = ShortArray(480) { 6000 }
        val bytes = ByteArray(pcm.size * 2)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(pcm)
        val samples =
            JavaAudioDeviceModule.AudioSamples(
                AudioFormat.ENCODING_PCM_16BIT,
                1,
                48_000,
                bytes,
            )
        CapturePcmAbDiagnostic.onAdmSamplesReadyAt(samples, nowMs = 0L)
        CapturePcmAbDiagnostic.onAdmSamplesReadyAt(samples, nowMs = 500L)

        val shadow =
            detailLines
                .lastOrNull()
                ?.let { D2CaptureObserveBridge.formatApmCaptureShadowLine(it) }
        assertTrue(shadow != null)
        assertTrue(shadow!!.contains("phase=D2_APM_CAPTURE"))
        assertTrue(shadow.contains("txPath=PRE_APM"))
        assertTrue(shadow.contains("point=APM_IN"))
        assertTrue(shadow.contains("windowEndMs="))
        assertTrue(shadow.contains("samples="))
        assertTrue(shadow.contains("sampleRate=48000"))
        assertTrue(shadow.contains("channels=1"))
    }

    @Test
    fun nativeHarness_emitsApmOutWhenSinksEmpty() {
        val detailLines = mutableListOf<String>()
        CapturePcmLevelDiagnostic.enabled = true
        CapturePcmLevelDiagnostic.fieldObserveLineSink = { detailLines += it }

        val pcm = ShortArray(480) { 7000 }
        val bytes = ByteArray(pcm.size * 2)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(pcm)
        PostApmPcmDispatcher.onNativePcm16FrameAt(bytes, 48_000, 1, pcm.size, nowMs = 0L)
        PostApmPcmDispatcher.onNativePcm16FrameAt(bytes, 48_000, 1, pcm.size, nowMs = 500L)

        val shadow =
            detailLines
                .filter { it.contains("point=APM_OUT") }
                .lastOrNull()
                ?.let { D2CaptureObserveBridge.formatApmCaptureShadowLine(it) }
        assertTrue(shadow != null)
        assertTrue(shadow!!.contains("point=APM_OUT"))
        assertTrue(shadow.contains("txPath=PRE_APM"))
        assertTrue(shadow.contains("windowEndMs="))
        assertTrue(shadow.contains("samples="))
    }

    @Test
    fun observeOff_admInjectionDoesNotEmit() {
        CapturePcmAbDiagnostic.enabled = false
        val detailLines = mutableListOf<String>()
        CapturePcmLevelDiagnostic.fieldObserveLineSink = { detailLines += it }

        val bytes = ByteArray(960)
        val samples =
            JavaAudioDeviceModule.AudioSamples(
                AudioFormat.ENCODING_PCM_16BIT,
                1,
                48_000,
                bytes,
            )
        CapturePcmAbDiagnostic.onAdmSamplesReadyAt(samples, nowMs = 500L)
        assertTrue(detailLines.isEmpty())
    }

    @Test
    fun observeOff_nativeHarnessDoesNotEmit() {
        CapturePcmLevelDiagnostic.enabled = false
        val detailLines = mutableListOf<String>()
        CapturePcmLevelDiagnostic.fieldObserveLineSink = { detailLines += it }
        val bytes = ByteArray(960)
        PostApmPcmDispatcher.onNativePcm16FrameAt(bytes, 48_000, 1, 480, nowMs = 0L)
        assertFalse(detailLines.any { it.contains("point=APM_OUT") })
    }
}
