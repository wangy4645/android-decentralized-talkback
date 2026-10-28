package com.talkback.core.webrtc

import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** B-13: PRE_HW window log; no TX path change. */
class Profile01B13PreHwDeskTest {
    @After
    fun tearDown() {
        CapturePcmAbDiagnostic.enabled = false
        CapturePcmLevelDiagnostic.reset()
    }

    @Test
    fun loudPcm_emitsPreHwWindow() {
        val lines = mutableListOf<String>()
        CapturePcmLevelDiagnostic.testLogSink = { lines += it }
        CapturePcmLevelDiagnostic.enabled = true
        val pcm = ShortArray(480) { 8000 }
        val bytes = ByteArray(pcm.size * 2)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(pcm)
        CapturePcmLevelDiagnostic.onPreHwPcm16Samples(
            buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN),
            bitsPerSample = 16,
            sampleRateHz = 48_000,
            channels = 1,
            frames = pcm.size,
            nowMs = 0L,
        )
        CapturePcmLevelDiagnostic.onPreHwPcm16Samples(
            buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN),
            bitsPerSample = 16,
            sampleRateHz = 48_000,
            channels = 1,
            frames = pcm.size,
            nowMs = 500L,
        )
        assertTrue(lines.any { it.contains("point=PRE_HW") && it.contains("rmsDbFS=") })
        assertTrue(lines.any { it.contains("PRE_JAVA_HW_EFFECT") })
    }
}
