package com.talkback.core.webrtc

import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** B-10: APM_IN vs APM_OUT window logs; no APM parameter change. */
class Profile01B10ApmInOutDeskTest {
    @After
    fun tearDown() {
        CapturePcmAbDiagnostic.enabled = false
        CapturePcmLevelDiagnostic.reset()
    }

    @Test
    fun loudPcm_emitsInAndOutWindows() {
        val lines = mutableListOf<String>()
        CapturePcmLevelDiagnostic.testLogSink = { lines += it }
        CapturePcmLevelDiagnostic.enabled = true
        val pcm = ShortArray(480) { 8000 }
        val bytes = ByteArray(pcm.size * 2)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(pcm)
        CapturePcmLevelDiagnostic.onAdmPcm16Samples(
            buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN),
            bitsPerSample = 16,
            sampleRateHz = 48_000,
            channels = 1,
            frames = pcm.size,
            nowMs = 0L,
        )
        CapturePcmLevelDiagnostic.onAdmPcm16Samples(
            buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN),
            bitsPerSample = 16,
            sampleRateHz = 48_000,
            channels = 1,
            frames = pcm.size,
            nowMs = 500L,
        )
        CapturePcmLevelDiagnostic.onPostApmPcm16Samples(
            buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN),
            bitsPerSample = 16,
            sampleRateHz = 48_000,
            channels = 1,
            frames = pcm.size,
            nowMs = 0L,
        )
        CapturePcmLevelDiagnostic.onPostApmPcm16Samples(
            buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN),
            bitsPerSample = 16,
            sampleRateHz = 48_000,
            channels = 1,
            frames = pcm.size,
            nowMs = 500L,
        )
        assertTrue(lines.any { it.contains("point=APM_IN") && it.contains("rmsDbFS=") })
        assertTrue(lines.any { it.contains("point=APM_OUT") && it.contains("rmsDbFS=") })
    }

    @Test
    fun disabled_emitsNothing() {
        val lines = mutableListOf<String>()
        CapturePcmLevelDiagnostic.testLogSink = { lines += it }
        CapturePcmLevelDiagnostic.enabled = false
        val pcm = ShortArray(480) { 8000 }
        val bytes = ByteArray(pcm.size * 2)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(pcm)
        CapturePcmLevelDiagnostic.onPostApmPcm16Samples(
            buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN),
            bitsPerSample = 16,
            sampleRateHz = 48_000,
            channels = 1,
            frames = pcm.size,
            nowMs = 500L,
        )
        assertTrue(lines.isEmpty())
    }
}