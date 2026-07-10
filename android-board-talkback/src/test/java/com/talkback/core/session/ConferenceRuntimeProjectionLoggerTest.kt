package com.talkback.core.session

import org.junit.Assert.assertTrue
import org.junit.Test

class ConferenceRuntimeProjectionLoggerTest {

    @Test
    fun formatDecision_includesSessionPresenceAndAuthorityFacts() {
        val line = ConferenceRuntimeProjectionLogger.formatDecision(
            sessionId = "s1",
            channelId = "CH-01",
            phase = ConferenceRuntimePhase.CONNECTING,
            isConferenceHost = false,
            sessionAccepted = true,
            localConferenceReady = true,
            transitionTerminalReady = true,
            authorityReachable = false,
            hostModuleId = "M01",
            hostIce = "DISCONNECTED",
            hostEnginePresent = true,
            hostConferenceEngine = false,
            meshIcePeers = "M01,M02",
            connectedRemoteMediaCount = 2,
            edgeRecovering = false,
            mediaRecovering = false,
            conferenceUiReady = false,
            conferenceSessionPresent = true,
            conferenceGeneration = 3L
        )
        assertTrue(line.startsWith(ConferenceRuntimeProjectionLogger.DECISION_TAG))
        assertTrue(line.contains("conferenceSessionPresent=true"))
        assertTrue(line.contains("conferenceSessionId=s1"))
        assertTrue(line.contains("conferenceGeneration=3"))
        assertTrue(line.contains("authorityReachable=false"))
        assertTrue(line.contains("hostIce=DISCONNECTED"))
        assertTrue(line.contains("hostConfEngine=false"))
        assertTrue(line.contains("meshIcePeers=M01,M02"))
        assertTrue(line.contains("mediaRecovering=false"))
        assertTrue(line.contains("edgeRecoveryFailed=false"))
        assertTrue(line.contains("conferenceDegraded=false"))
        assertTrue(line.contains("phase=CONNECTING"))
    }

    @Test
    fun formatMissing_marksSessionAbsent() {
        val line = ConferenceRuntimeProjectionLogger.formatMissing(
            channelId = "CH-01",
            peerModuleId = "M01",
            iceState = "CONNECTED",
            reason = "no_conference_session",
            conferenceSessionCount = 0
        )
        assertTrue(line.startsWith(ConferenceRuntimeProjectionLogger.MISSING_TAG))
        assertTrue(line.contains("conferenceSessionPresent=false"))
        assertTrue(line.contains("conferenceSessionId=null"))
        assertTrue(line.contains("peer=M01"))
        assertTrue(line.contains("ice=CONNECTED"))
        assertTrue(line.contains("reason=no_conference_session"))
    }
}
