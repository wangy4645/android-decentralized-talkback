package com.talkback.core.util

import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test

class ConferenceAuditTimelineLogTest {
    private val lines = mutableListOf<String>()

    @After
    fun tearDown() {
        ConferenceAuditTimelineLog.resetForTest()
    }

    @Test
    fun sessionCreated_includesIdentityFields() {
        ConferenceAuditTimelineLog.resetForTest { lines += it }
        ConferenceAuditTimelineLog.sessionCreated(
            sessionId = "abc",
            channelId = "CH-01",
            type = "CONFERENCE",
            localModuleId = "M02",
            initiatorModuleId = "M01",
            localRole = "PARTICIPANT",
            creationSource = "INVITE_ACCEPT",
            writer = "acceptGroupInvite",
            cause = "participant_invite_accepted"
        )
        assertTrue(lines.any { it.contains("event=SESSION_CREATED") })
        assertTrue(lines.any { it.contains("localRole=PARTICIPANT") })
        assertTrue(lines.any { it.contains("creationSource=INVITE_ACCEPT") })
    }

    @Test
    fun channelSessionBind_skipsSingleCandidate() {
        ConferenceAuditTimelineLog.resetForTest { lines += it }
        ConferenceAuditTimelineLog.channelSessionBind(
            channelId = "CH-01",
            candidates = listOf(
                ConferenceAuditTimelineLog.ChannelSessionCandidate(
                    sessionId = "only",
                    type = "CONFERENCE",
                    role = "HOST",
                    initiatorModuleId = "M02",
                    state = "READY",
                    accepted = true,
                    score = 30
                )
            ),
            selectedSessionId = "only",
            reason = "score",
            writer = "meshSessionForChannel"
        )
        assertTrue(lines.none { it.contains("CHANNEL_SESSION_BIND") })
    }
}
