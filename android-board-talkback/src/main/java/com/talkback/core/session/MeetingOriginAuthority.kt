package com.talkback.core.session

/**
 * Conference control-plane origin owner (CREATION / MEMBERSHIP / head supplement fanout).
 *
 * Distinct from mesh [anchorId] / bootstrap primary. Canonical owner is the published
 * [ConferenceTopologySnapshot.hostModuleId], checked against the session initiator binding
 * when that binding is present. Does not grant origin from role labels alone.
 */
object MeetingOriginAuthority {
    fun isLocalOriginOwner(
        sessionTypeConference: Boolean,
        sessionAccepted: Boolean,
        localModuleId: String,
        canonicalHostModuleId: String?,
        boundInitiatorModuleId: String?,
    ): Boolean {
        if (!sessionTypeConference || !sessionAccepted) return false
        if (canonicalHostModuleId.isNullOrBlank()) return false
        if (boundInitiatorModuleId != null && boundInitiatorModuleId != canonicalHostModuleId) {
            return false
        }
        return canonicalHostModuleId == localModuleId
    }
}
