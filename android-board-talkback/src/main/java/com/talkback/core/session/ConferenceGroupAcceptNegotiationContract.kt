package com.talkback.core.session

/**
 * S3 Track-B: Host offer ↔ participant answer on CONFERENCE GROUP_ACCEPT / GROUP_INVITE.
 *
 * A) Host must not treat blank accept as terminal while a host-local offer lineage is outstanding.
 * B) Spoke may answer inbound Host SDP without [mayCreateConferencePeerConnection] alone blocking.
 */
object ConferenceGroupAcceptNegotiationContract {

    fun shouldDeferBlankHostGroupAccept(
        usesHostRealizationAccept: Boolean,
        hostOutstandingOfferLineageId: String?,
    ): Boolean {
        if (!usesHostRealizationAccept) return false
        return isOutstandingHostOfferLineage(hostOutstandingOfferLineageId)
    }

    fun mayApplyMembershipOnlyBlankConferenceAccept(
        usesHostRealizationAccept: Boolean,
        hostOutstandingOfferLineageId: String?,
    ): Boolean = !shouldDeferBlankHostGroupAccept(usesHostRealizationAccept, hostOutstandingOfferLineageId)

    /**
     * Participant: inbound Host invite carries SDP; local is answerer (not offerer).
     * Extends [ConferenceMediaEdgeRealizationContract.shouldAcceptAnchorOfferOnExistingSession]
     * for rejoin when topology has not yet listed the spoke edge.
     */
    fun shouldBeginInboundHostConferenceMediaAnswer(
        existingSessionId: String,
        inviteSessionId: String,
        localModuleId: String,
        hostCallerModuleId: String,
        sessionInitiatorModuleId: String?,
        payloadSdpBlank: Boolean,
        snapshot: ConferenceTopologySnapshot?,
        declaredMode: ConferenceTopologyMode = snapshot?.topologyMode ?: ConferenceTopologyMode.MESH,
        sessionAnchorModuleId: String? = null,
    ): Boolean {
        if (!inboundHostConferenceInviteIdentityValid(
                existingSessionId,
                inviteSessionId,
                localModuleId,
                hostCallerModuleId,
                sessionInitiatorModuleId,
            )
        ) {
            return false
        }
        if (payloadSdpBlank) return false
        if (
            mayAnswerInboundHostConferenceOffer(
                existingSessionId = existingSessionId,
                inviteSessionId = inviteSessionId,
                localModuleId = localModuleId,
                hostCallerModuleId = hostCallerModuleId,
                sessionInitiatorModuleId = sessionInitiatorModuleId,
                payloadSdpBlank = false,
                snapshot = snapshot,
                declaredMode = declaredMode,
            )
        ) {
            return true
        }
        return mayAnswerPreservedAnchorHostInviteWhenSnapshotPending(
            localModuleId = localModuleId,
            hostCallerModuleId = hostCallerModuleId,
            sessionInitiatorModuleId = sessionInitiatorModuleId,
            declaredMode = declaredMode,
            sessionAnchorModuleId = sessionAnchorModuleId,
        )
    }

    fun inboundHostConferenceInviteIdentityValid(
        existingSessionId: String,
        inviteSessionId: String,
        localModuleId: String,
        hostCallerModuleId: String,
        sessionInitiatorModuleId: String?,
    ): Boolean {
        if (existingSessionId != inviteSessionId) return false
        if (sessionInitiatorModuleId != hostCallerModuleId) return false
        if (localModuleId.isBlank() || hostCallerModuleId.isBlank()) return false
        if (localModuleId == hostCallerModuleId) return false
        return true
    }

    /**
     * Rejoin pull-in: session already carries preserved anchor view before authority snapshot is published.
     */
    fun mayAnswerPreservedAnchorHostInviteWhenSnapshotPending(
        localModuleId: String,
        hostCallerModuleId: String,
        sessionInitiatorModuleId: String?,
        declaredMode: ConferenceTopologyMode,
        sessionAnchorModuleId: String?,
    ): Boolean {
        if (sessionInitiatorModuleId != hostCallerModuleId) return false
        if (declaredMode != ConferenceTopologyMode.ANCHOR) return false
        val anchor = sessionAnchorModuleId?.takeIf { it.isNotBlank() } ?: return false
        return hostCallerModuleId == anchor && localModuleId != anchor
    }

    fun mayAnswerInboundHostConferenceOffer(
        existingSessionId: String,
        inviteSessionId: String,
        localModuleId: String,
        hostCallerModuleId: String,
        sessionInitiatorModuleId: String?,
        payloadSdpBlank: Boolean,
        snapshot: ConferenceTopologySnapshot?,
        declaredMode: ConferenceTopologyMode = snapshot?.topologyMode ?: ConferenceTopologyMode.MESH,
    ): Boolean {
        if (
            !inboundHostConferenceInviteIdentityValid(
                existingSessionId,
                inviteSessionId,
                localModuleId,
                hostCallerModuleId,
                sessionInitiatorModuleId,
            )
        ) {
            return false
        }
        if (payloadSdpBlank) return false
        if (existingSessionId != inviteSessionId) return false
        if (sessionInitiatorModuleId != hostCallerModuleId) return false
        if (
            ConferenceMediaEdgeRealizationContract.mayCreateOffer(
                localModuleId,
                hostCallerModuleId,
                snapshot,
                declaredMode,
            )
        ) {
            return false
        }
        if (
            ConferenceMediaEdgeRealizationContract.shouldAcceptAnchorOfferOnExistingSession(
                existingSessionId = existingSessionId,
                inviteSessionId = inviteSessionId,
                localModuleId = localModuleId,
                callerModuleId = hostCallerModuleId,
                payloadSdpBlank = false,
                snapshot = snapshot,
                declaredMode = declaredMode,
            )
        ) {
            return true
        }
        if (declaredMode == ConferenceTopologyMode.MESH) {
            return ConferenceMediaEdgeRealizationContract.mayCreateConferencePeerConnection(
                localModuleId,
                hostCallerModuleId,
                snapshot,
                declaredMode,
            )
        }
        val anchor = snapshot?.anchorId
        return anchor != null && hostCallerModuleId == anchor && localModuleId != anchor
    }

    private fun isOutstandingHostOfferLineage(lineageId: String?): Boolean =
        lineageId?.takeIf { it.isNotBlank() && it != ConferenceRealizationLineage.UNKNOWN } != null
}
