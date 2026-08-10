package com.talkback.core.session

/**
 * ConferenceSameSessionRejoinAcceptancePatch
 *
 * Gap name: [CONFERENCE_SAME_SESSION_REJOIN_ACCEPTANCE_MISSING]
 *
 * Host recovery may send same-session CONFERENCE GROUP_INVITE(SDP) with [rejoin]=true.
 * Without this gate, still-held conference falls through [prepareForGroupInvite] → BUSY
 * (correct for ordinary duplicates; wrong for explicit Host rejoin).
 *
 * This predicate only admits reconnect. It does not create a new session.
 */
object ConferenceSameSessionRejoinAcceptance {

    /**
     * @return true when the invite must use same-session reconnect (apply SDP / GROUP_ACCEPT),
     *         false when duplicate / stale / wrong lineage must keep BUSY protection.
     */
    fun shouldAcceptReconnect(
        existingType: SessionType,
        existingSessionId: String,
        existingHostModuleId: String?,
        inviteSessionId: String,
        callerModuleId: String,
        payloadRejoin: Boolean,
        payloadSdpBlank: Boolean,
        payloadInitiatorModuleId: String?
    ): Boolean {
        if (existingType != SessionType.CONFERENCE) return false
        if (existingSessionId != inviteSessionId) return false
        if (!payloadRejoin) return false
        if (payloadSdpBlank) return false
        // Lineage: only the conference host may drive same-session rejoin reconnect.
        if (existingHostModuleId != null && callerModuleId != existingHostModuleId) {
            return false
        }
        if (!payloadInitiatorModuleId.isNullOrBlank() &&
            existingHostModuleId != null &&
            payloadInitiatorModuleId != existingHostModuleId
        ) {
            return false
        }
        return true
    }
}
