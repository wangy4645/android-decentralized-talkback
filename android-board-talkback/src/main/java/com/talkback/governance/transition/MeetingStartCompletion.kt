package com.talkback.governance.transition

/**
 * ADR-0016: MEETING_START establishment predicate (host-owned transition).
 */
object MeetingStartCompletion {
    fun evaluate(
        conferenceAccepted: Boolean,
        expectedInviteeCount: Int,
        connectedInviteeCount: Int
    ): TransitionPredicateEval {
        if (!conferenceAccepted) {
            return TransitionPredicateEval.unsatisfied("conference_not_accepted")
        }
        if (expectedInviteeCount == 0) {
            return TransitionPredicateEval.satisfied("host_solo_conference")
        }
        if (connectedInviteeCount > 0) {
            return TransitionPredicateEval.satisfied("host_peer_ice_connected")
        }
        return TransitionPredicateEval.unsatisfied("awaiting_first_invitee_ice")
    }
}
