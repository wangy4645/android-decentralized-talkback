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
    fun recoveryOfferSentAndReceived_includeLineageAndDecision() {
        val ctx = MediaRecoveryCausalTrace.Context(
            sessionId = "sess-1",
            sessionTraceId = "abc12345",
            scope = MediaBearerScope.CONFERENCE,
            remoteModuleId = "M01",
            remoteEndpointId = "E01",
            recoveryAttemptId = 4L,
            obligationGeneration = 2L,
            conferenceGeneration = 1L,
            pcGeneration = 8L,
            transportGeneration = 8L,
            iceRestart = true
        )
        MediaRecoveryCausalTrace.recoveryOfferSent(
            ctx = ctx,
            joinIntent = "RECOVERY_REATTACH",
            transportOutcome = "SENT",
            signalingEpoch = 1L
        )
        MediaRecoveryCausalTrace.recoveryOfferReceived(
            ctx = ctx,
            decision = MediaRecoveryCausalTrace.OfferIngressDecision.DROP_DUPLICATE_ICE_CONNECTED,
            joinIntent = "RECOVERY_REATTACH",
            localIceState = "CONNECTED",
            localAttemptId = 5L,
            localObligationGen = 2L,
            detail = "meshCompleted=true"
        )
        assertTrue(lines[0].startsWith("RECOVERY_OFFER_SENT"))
        assertTrue(lines[0].contains("attempt=4"))
        assertTrue(lines[0].contains("obligationGen=2"))
        assertTrue(lines[0].contains("transportOutcome=SENT"))
        assertTrue(lines[0].contains("signalingEpoch=1"))
        assertTrue(lines[1].startsWith("RECOVERY_OFFER_RECEIVED"))
        assertTrue(lines[1].contains("decision=DROP_DUPLICATE_ICE_CONNECTED"))
        assertTrue(lines[1].contains("localIce=CONNECTED"))
        assertTrue(lines[1].contains("localAttempt=5"))
        assertTrue(lines[1].contains("localObligationGen=2"))
    }
}
