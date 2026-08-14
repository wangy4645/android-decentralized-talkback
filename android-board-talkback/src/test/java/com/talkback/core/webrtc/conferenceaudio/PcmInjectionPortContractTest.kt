package com.talkback.core.webrtc.conferenceaudio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADR-0056 Phase 1a-2 — PcmInjectionPort contract tests. */
class PcmInjectionPortContractTest {

    @Test
    fun openWriteClose_happyPath() {
        val port = RecordingPcmInjectionPort()
        assertFalse(port.isOpen)
        assertTrue(port.open(ConferencePcmFormat.CANONICAL).isSuccess)
        assertTrue(port.isOpen)
        val frame = PcmFrame.constantLevel(1_000)
        assertTrue(port.write(frame).isSuccess)
        assertEquals(1, port.writtenFrames.size)
        port.close()
        assertFalse(port.isOpen)
    }

    @Test
    fun writeWithoutOpen_isObservableFailure() {
        val port = RecordingPcmInjectionPort()
        val result = port.write(PcmFrame.constantLevel(500))
        assertTrue(result.isFailure)
        assertEquals(listOf(PcmInjectionFailure.NOT_OPEN), port.failures)
    }

    @Test
    fun formatMismatch_isObservableFailure() {
        val port = RecordingPcmInjectionPort()
        port.open(ConferencePcmFormat.CANONICAL)
        val wrongSize = ConferencePcmFormat(
            sampleRateHz = 16_000,
            channels = 1,
            bitsPerSample = 16,
            frameDurationMs = 10
        )
        val result = port.write(PcmFrame(ShortArray(wrongSize.samplesPerFrame), wrongSize))
        assertTrue(result.isFailure)
        assertEquals(listOf(PcmInjectionFailure.FORMAT_MISMATCH), port.failures)
    }

    @Test
    fun injectedFailure_isObservable_noSilentDrop() {
        val port = RecordingPcmInjectionPort()
        port.open(ConferencePcmFormat.CANONICAL)
        port.injectNextFailure(PcmInjectionFailure.INJECT_FAILED)
        val result = port.write(PcmFrame.constantLevel(500))
        assertTrue(result.isFailure)
        assertEquals(listOf(PcmInjectionFailure.INJECT_FAILED), port.failures)
        assertEquals(0, port.writtenFrames.size)
    }

    @Test
    fun writeAfterClose_failsObservably() {
        val port = RecordingPcmInjectionPort()
        port.open(ConferencePcmFormat.CANONICAL)
        port.close()
        val result = port.write(PcmFrame.constantLevel(500))
        assertTrue(result.isFailure)
        assertEquals(listOf(PcmInjectionFailure.CLOSED), port.failures)
    }

    @Test
    fun reOpenAfterClose_notAllowedUntilNewPort() {
        val port = RecordingPcmInjectionPort()
        port.open(ConferencePcmFormat.CANONICAL)
        port.close()
        val reopen = port.open(ConferencePcmFormat.CANONICAL)
        assertTrue(reopen.isFailure)
        assertFalse(port.isOpen)
    }
}
