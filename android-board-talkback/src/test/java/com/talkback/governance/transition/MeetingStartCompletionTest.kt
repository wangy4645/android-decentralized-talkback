package com.talkback.governance.transition

import com.talkback.core.model.EndpointId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MeetingStartCompletionTest {
    @Test
    fun soloHost_satisfiedWhenFrozenDeclaration() {
        val declaration = MeetingStartDeclaration.frozen(
            mode = MeetingMode.SOLO_HOST,
            targets = emptySet(),
            inviteDispatchFinished = true
        )
        val eval = MeetingStartCompletion.evaluate(
            declaration = declaration,
            conferenceAccepted = true,
            connectedInviteeCount = 0
        )
        assertTrue(eval.satisfied)
        assertEquals("host_solo_conference", eval.reason)
    }

    @Test
    fun openDeclaration_neverSatisfied() {
        val declaration = MeetingStartDeclaration.open(
            mode = MeetingMode.SOLO_HOST,
            targets = emptySet()
        )
        val eval = MeetingStartCompletion.evaluate(
            declaration = declaration,
            conferenceAccepted = true,
            connectedInviteeCount = 0
        )
        assertFalse(eval.satisfied)
        assertEquals("declaration_not_frozen", eval.reason)
    }

    @Test
    fun multiParty_unsatisfiedUntilDispatchFrozenAndPeerConnected() {
        val targets = setOf(EndpointId("E02"), EndpointId("E03"))
        val open = MeetingStartDeclaration.open(MeetingMode.MULTI_PARTY, targets)!!
        val pendingDispatch = MeetingStartCompletion.evaluate(open, conferenceAccepted = true, 0)
        assertFalse(pendingDispatch.satisfied)
        assertEquals("declaration_not_frozen", pendingDispatch.reason)

        val frozenBeforeIce = MeetingStartDeclaration.frozen(
            mode = MeetingMode.MULTI_PARTY,
            targets = targets,
            inviteDispatchFinished = true
        )
        val awaitingIce = MeetingStartCompletion.evaluate(
            frozenBeforeIce,
            conferenceAccepted = true,
            connectedInviteeCount = 0
        )
        assertFalse(awaitingIce.satisfied)
        assertEquals("awaiting_first_invitee_ice", awaitingIce.reason)

        val ready = MeetingStartCompletion.evaluate(
            frozenBeforeIce,
            conferenceAccepted = true,
            connectedInviteeCount = 1
        )
        assertTrue(ready.satisfied)
        assertEquals("host_peer_ice_connected", ready.reason)
    }

    @Test
    fun notAccepted_unsatisfied() {
        val declaration = MeetingStartDeclaration.frozen(
            mode = MeetingMode.SOLO_HOST,
            targets = emptySet(),
            inviteDispatchFinished = true
        )
        val eval = MeetingStartCompletion.evaluate(
            declaration = declaration,
            conferenceAccepted = false,
            connectedInviteeCount = 0
        )
        assertFalse(eval.satisfied)
        assertEquals("conference_not_accepted", eval.reason)
    }
}
