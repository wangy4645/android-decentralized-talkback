package com.talkback.core.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConferenceSrdNativeObservabilityTest {

    private fun ctx(remote: String = "M03") = ConferenceSrdObservability.Context(
        sessionId = "sess-1",
        remoteModuleId = remote,
        localModuleId = "M01",
        pcGeneration = 3L,
        conferenceGeneration = 1L
    )

    @Test
    fun watchdogGap_missingNativeCallEnter_afterMutexAcquired() {
        val edgeKey = "sess-1|M03"
        ConferenceSrdNativeObservability.beginAttempt(ctx(), conferenceGeneration = 1L, answerSdp = "v=0\n")
        ConferenceSrdNativeObservability.record(edgeKey, "SRD_ENTER")
        ConferenceSrdNativeObservability.record(edgeKey, "SRD_MUTEX_ACQUIRED")
        val line = ConferenceSrdNativeObservability.formatWatchdogGap(edgeKey, pcHash = 12345)
        assertTrue(line.startsWith("SRD_WATCHDOG_GAP "))
        assertTrue(line.contains("missing=NATIVE_CALL_ENTER"))
        assertTrue(line.contains("lastEvent=SRD_MUTEX_ACQUIRED"))
        assertTrue(line.contains("edgeKey=sess-1|M03"))
        assertTrue(line.contains("conferenceGeneration=1"))
        assertTrue(line.contains("answerSdpBytes="))
    }

    @Test
    fun watchdogGap_missingNativeCallExit_afterNativeEnter() {
        val edgeKey = "sess-1|M03"
        ConferenceSrdNativeObservability.beginAttempt(ctx(), conferenceGeneration = 1L, answerSdp = "v=0\n")
        ConferenceSrdNativeDomainObservability.recordDomainExecutionEnter(edgeKey)
        ConferenceSrdNativeObservability.record(edgeKey, "SRD_NATIVE_CALL_ENTER")
        val line = ConferenceSrdNativeObservability.formatWatchdogGap(edgeKey)
        assertTrue(line.contains("missing=NATIVE_CALL_EXIT"))
        assertTrue(line.contains("state=NATIVE_DOMAIN_EXECUTION_ACTIVE"))
        assertTrue(line.contains("lastEvent=SRD_NATIVE_CALL_ENTER"))
    }

    @Test
    fun watchdogGap_missingJniReturn_afterNativeExit() {
        val edgeKey = "sess-1|M03"
        ConferenceSrdNativeObservability.beginAttempt(ctx(), conferenceGeneration = 1L, answerSdp = "v=0\n")
        ConferenceSrdNativeObservability.record(edgeKey, "SRD_NATIVE_CALL_ENTER")
        ConferenceSrdNativeObservability.recordNativeCallExit(edgeKey, elapsedMs = 42L)
        val line = ConferenceSrdNativeObservability.formatWatchdogGap(edgeKey)
        assertTrue(line.contains("missing=JNI_RETURN"))
        assertTrue(line.contains("nativeCallElapsedMs=42"))
    }

    @Test
    fun watchdogGap_missingCallback_afterJniReturn() {
        val edgeKey = "sess-1|M03"
        ConferenceSrdNativeObservability.beginAttempt(ctx(), conferenceGeneration = 1L, answerSdp = "abc")
        ConferenceSrdNativeObservability.record(edgeKey, "SRD_MUTEX_ACQUIRED")
        ConferenceSrdNativeObservability.record(edgeKey, "SRD_JNI_RETURN")
        val line = ConferenceSrdNativeObservability.formatWatchdogGap(edgeKey)
        assertTrue(line.contains("missing=CALLBACK"))
        assertTrue(line.contains("lastEvent=SRD_JNI_RETURN"))
    }

    @Test
    fun watchdogGap_missingMutexReleased_whenCallbackWithoutRelease() {
        val edgeKey = "sess-1|M03"
        ConferenceSrdNativeObservability.beginAttempt(ctx(), conferenceGeneration = 1L, answerSdp = "abc")
        ConferenceSrdNativeObservability.record(edgeKey, "SRD_MUTEX_ACQUIRED")
        ConferenceSrdNativeObservability.record(edgeKey, "SRD_JNI_RETURN")
        ConferenceSrdNativeObservability.record(edgeKey, "SRD_CALLBACK_ENTER")
        val line = ConferenceSrdNativeObservability.formatWatchdogGap(edgeKey)
        assertTrue(line.contains("missing=MUTEX_RELEASED"))
    }

    @Test
    fun recordFromTag_updatesAttemptByPlaybackTag() {
        val edgeKey = "sess-1|M04"
        ConferenceSrdNativeObservability.beginAttempt(
            ctx(remote = "M04"),
            conferenceGeneration = 2L,
            answerSdp = "v=0"
        )
        ConferenceSrdNativeObservability.recordFromTag("sess-1|M04", "SRD_ENTER")
        val state = ConferenceSrdNativeObservability.peekAttempt(edgeKey)
        assertEquals("SRD_ENTER", state?.lastEvent)
    }
}
