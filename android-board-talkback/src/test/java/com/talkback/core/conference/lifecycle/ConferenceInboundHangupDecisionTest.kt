package com.talkback.core.conference.lifecycle

import com.talkback.core.conference.lifecycle.ConferenceInboundHangupDecision.Action
import org.junit.Assert.assertEquals
import org.junit.Test

class ConferenceInboundHangupDecisionTest {
    @Test
    fun unknownSession_isNoOp() {
        assertEquals(
            Action.NO_OP,
            ConferenceInboundHangupDecision.decide(
                wireSessionId = "old",
                liveSessionId = null,
                initiatorModuleId = "M01",
                senderModuleId = "M01"
            )
        )
    }

    @Test
    fun lateHangupDifferentSession_isNoOp() {
        assertEquals(
            Action.NO_OP,
            ConferenceInboundHangupDecision.decide(
                wireSessionId = "old-session",
                liveSessionId = "new-session",
                initiatorModuleId = "M01",
                senderModuleId = "M03"
            )
        )
    }

    @Test
    fun hostSender_terminatesConference() {
        assertEquals(
            Action.TERMINATE_CONFERENCE,
            ConferenceInboundHangupDecision.decide(
                wireSessionId = "s1",
                liveSessionId = "s1",
                initiatorModuleId = "M01",
                senderModuleId = "M01"
            )
        )
    }

    @Test
    fun nonHostSender_prunesPeer() {
        assertEquals(
            Action.PRUNE_PEER,
            ConferenceInboundHangupDecision.decide(
                wireSessionId = "s1",
                liveSessionId = "s1",
                initiatorModuleId = "M01",
                senderModuleId = "M03"
            )
        )
    }

    @Test
    fun missingInitiator_isNoOp() {
        assertEquals(
            Action.NO_OP,
            ConferenceInboundHangupDecision.decide(
                wireSessionId = "s1",
                liveSessionId = "s1",
                initiatorModuleId = null,
                senderModuleId = "M03"
            )
        )
    }
}
