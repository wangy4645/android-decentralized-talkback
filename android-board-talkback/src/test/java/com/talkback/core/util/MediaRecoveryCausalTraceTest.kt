package com.talkback.core.util

import com.talkback.core.webrtc.MediaBearerScope
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MediaRecoveryCausalTraceTest {

    private val lines = mutableListOf<String>()

    @Before
    fun setUp() {
        lines.clear()
        MediaRecoveryCausalTrace.resetForTest { lines.add(it) }
    }

    @After
    fun tearDown() {
        MediaRecoveryCausalTrace.resetForTest(null)
    }

    @Test
    fun recoveryIceRestartDispatched_includesCorrelationFields() {
        MediaRecoveryCausalTrace.recoveryIceRestartDispatched(
            MediaRecoveryCausalTrace.Context(
                sessionId = "sess-1",
                sessionTraceId = "abc12345",
                scope = MediaBearerScope.CONFERENCE,
                remoteModuleId = "M03",
                remoteEndpointId = "E03",
                recoveryAttemptId = 2L,
                conferenceGeneration = 1L,
                pcGeneration = 6L,
                transportGeneration = 6L,
                iceRestart = true
            )
        )
        val line = lines.single()
        assertTrue(line.startsWith("RECOVERY_ICE_RESTART_DISPATCHED"))
        assertTrue(line.contains("session=sess-1"))
        assertTrue(line.contains("sessionTraceId=abc12345"))
        assertTrue(line.contains("scope=CONFERENCE"))
        assertTrue(line.contains("remote=M03"))
        assertTrue(line.contains("remoteEndpoint=E03"))
        assertTrue(line.contains("attempt=2"))
        assertTrue(line.contains("conferenceGeneration=1"))
        assertTrue(line.contains("pcGeneration=6"))
        assertTrue(line.contains("transportGeneration=6"))
        assertTrue(line.contains("iceRestart=true"))
    }

    @Test
    fun mediaSignalCandidateApplied_omitsOptionalAttempt() {
        MediaRecoveryCausalTrace.mediaIceCandidateApplied(
            MediaRecoveryCausalTrace.Context(
                sessionId = "grp:CH-01",
                sessionTraceId = "trace-1",
                scope = MediaBearerScope.GROUP,
                remoteModuleId = "M02",
                pcGeneration = 3L,
                transportGeneration = 3L
            )
        )
        val line = lines.single()
        assertTrue(line.startsWith("MEDIA_ICE_CANDIDATE_APPLIED"))
        assertTrue(line.contains("scope=GROUP"))
        assertTrue(!line.contains("attempt="))
    }
}
