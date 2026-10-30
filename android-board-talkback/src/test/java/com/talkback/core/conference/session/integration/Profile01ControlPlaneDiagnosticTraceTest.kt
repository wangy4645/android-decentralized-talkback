package com.talkback.core.conference.session.integration

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class Profile01ControlPlaneDiagnosticTraceTest {
    @After
    fun tearDown() {
        Profile01ControlPlaneDiagnosticTrace.clearForTest()
    }

    @Test
    fun sessionSnapshot_retainsLastTransitions() {
        val sessionId = "diag-session"
        Profile01ControlPlaneDiagnosticTrace.recordMembershipIngress(
            sessionId = sessionId,
            conferenceIdHex = "abc123",
            channelId = "CH-01",
            membershipVersion = 2L,
            mediaKeyEpoch = 3L,
            phase = "Converged",
        )
        Profile01ControlPlaneDiagnosticTrace.recordMaterializeHead(
            sessionId = sessionId,
            conferenceIdHex = "abc123",
            channelId = "CH-01",
            outcome = "NO_SUPPLEMENT",
        )
        Profile01ControlPlaneDiagnosticTrace.recordPeerReplay(
            sessionId = sessionId,
            conferenceIdHex = "abc123",
            outcome = "DEFERRED_NO_SUPPLEMENT",
        )

        val snap = Profile01ControlPlaneDiagnosticTrace.snapshotForSession(sessionId)
        assertEquals("abc123", snap["conferenceIdHex"])
        assertEquals("CH-01", snap["channelId"])
        assertEquals("Converged", snap["lastMembershipIngress"])
        assertEquals("NO_SUPPLEMENT", snap["lastMaterializeOutcome"])
        assertEquals("DEFERRED_NO_SUPPLEMENT", snap["lastPeerReplayOutcome"])
        assertEquals("2", snap["headMembershipVersion"])
        assertEquals("3", snap["headMediaKeyEpoch"])
    }
}
