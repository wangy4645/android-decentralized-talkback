package com.talkback.governance.transition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MeetingStartCompletionTest {
    @Test
    fun soloHost_satisfiedWhenNoExpectedInvitees() {
        val eval = MeetingStartCompletion.evaluate(
            conferenceAccepted = true,
            expectedInviteeCount = 0,
            connectedInviteeCount = 0
        )
        assertTrue(eval.satisfied)
        assertEquals("host_solo_conference", eval.reason)
    }

    @Test
    fun hostWithInvitees_unsatisfiedUntilFirstPeerConnected() {
        val pending = MeetingStartCompletion.evaluate(
            conferenceAccepted = true,
            expectedInviteeCount = 2,
            connectedInviteeCount = 0
        )
        assertFalse(pending.satisfied)
        assertEquals("awaiting_first_invitee_ice", pending.reason)

        val ready = MeetingStartCompletion.evaluate(
            conferenceAccepted = true,
            expectedInviteeCount = 2,
            connectedInviteeCount = 1
        )
        assertTrue(ready.satisfied)
        assertEquals("host_peer_ice_connected", ready.reason)
    }

    @Test
    fun notAccepted_unsatisfied() {
        val eval = MeetingStartCompletion.evaluate(
            conferenceAccepted = false,
            expectedInviteeCount = 0,
            connectedInviteeCount = 0
        )
        assertFalse(eval.satisfied)
        assertEquals("conference_not_accepted", eval.reason)
    }
}
