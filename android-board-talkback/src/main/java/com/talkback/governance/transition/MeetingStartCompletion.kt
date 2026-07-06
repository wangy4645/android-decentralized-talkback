package com.talkback.governance.transition

/**
 * ADR-0016 + ADR-0017: MEETING_START establishment predicate (host-owned transition).
 */
object MeetingStartCompletion {
    fun evaluate(
        declaration: MeetingStartDeclaration?,
        conferenceAccepted: Boolean,
        connectedInviteeCount: Int
    ): TransitionPredicateEval {
        if (declaration == null) {
            return TransitionPredicateEval.unsatisfied("declaration_missing")
        }
        if (!declaration.isFrozen) {
            return TransitionPredicateEval.unsatisfied("declaration_not_frozen")
        }
        if (MeetingStartDeclaration.validateConsistency(
                declaration.mode,
                declaration.expectedInviteTargets
            ) != null
        ) {
            return TransitionPredicateEval.unsatisfied("invalid_declaration")
        }
        if (!conferenceAccepted) {
            return TransitionPredicateEval.unsatisfied("conference_not_accepted")
        }
        return when (declaration.mode) {
            MeetingMode.SOLO_HOST -> TransitionPredicateEval.satisfied("host_solo_conference")
            MeetingMode.MULTI_PARTY -> {
                if (!declaration.inviteDispatchFinished) {
                    return TransitionPredicateEval.unsatisfied("invite_dispatch_pending")
                }
                if (connectedInviteeCount > 0) {
                    TransitionPredicateEval.satisfied("host_peer_ice_connected")
                } else {
                    TransitionPredicateEval.unsatisfied("awaiting_first_invitee_ice")
                }
            }
        }
    }
}
