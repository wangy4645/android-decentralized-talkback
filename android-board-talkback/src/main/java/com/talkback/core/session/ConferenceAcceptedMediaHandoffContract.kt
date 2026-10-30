package com.talkback.core.session

/**
 * P0.1e: MEMBER_ACCEPTED → media realization handoff.
 *
 * Does not change Topology, Anchor admission, Ready, Health, Recovery, Audio, or completion.
 *
 * Spoke records ACCEPTED_WAIT_MEDIA. Anchor starts realization. Coordinator projects facts.
 */
enum class ConferenceAcceptedMediaHandoffPhase {
    INVITE_PENDING,
    ACCEPTED_WAIT_MEDIA,
    REALIZATION_RUNNING,
    REALIZED,
    MEDIA_FAILED
}

object ConferenceAcceptedMediaHandoffContract {

    const val REASON_ALREADY_ACCEPTED = "ALREADY_ACCEPTED"
    const val REASON_NOT_INVITE_PENDING = "NOT_INVITE_PENDING"
    const val REASON_SPOKE_MUST_WAIT = "SPOKE_MUST_WAIT"

    fun blankAcceptIsWaitMedia(sdpBlank: Boolean): Boolean = sdpBlank

    /**
     * Admission TTL may abort only while the user has not accepted.
     */
    fun mayAdmissionTtlAbort(phase: ConferenceAcceptedMediaHandoffPhase): Boolean =
        phase == ConferenceAcceptedMediaHandoffPhase.INVITE_PENDING

    fun mayArmPendingInvite(
        incomingSessionId: String,
        acceptedConferenceSessionId: String?
    ): Boolean {
        if (incomingSessionId.isBlank()) return false
        if (acceptedConferenceSessionId.isNullOrBlank()) return true
        return incomingSessionId != acceptedConferenceSessionId
    }

    /**
     * After membership ACCEPT, only current Anchor may start realization.
     * Spoke waits for Anchor offer. Host≠Anchor does not start ghost edges.
     */
    fun mayStartRealizationAfterMembershipAccept(
        localModuleId: String,
        snapshot: ConferenceTopologySnapshot?
    ): Boolean {
        if (snapshot == null) return false
        if (snapshot.topologyMode != ConferenceTopologyMode.ANCHOR) return false
        return snapshot.anchorId == localModuleId
    }

    fun duplicateInviteAfterAccept(
        existingSessionId: String,
        incomingSessionId: String,
        existingAccepted: Boolean
    ): Boolean =
        existingAccepted &&
            existingSessionId.isNotBlank() &&
            existingSessionId == incomingSessionId
}
