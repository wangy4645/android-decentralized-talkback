package com.talkback.core.session

/**
 * Active/terminal evaluation for outbound GROUP_INVITE admission evidence (#180 F2).
 */
object OutboundGroupInviteAttemptSupport {

    fun isAdmissionRelevantSemantic(semantic: GroupInvitePayloadSemantic): Boolean =
        semantic == GroupInvitePayloadSemantic.BOOTSTRAP_SDP_INVITE ||
            semantic == GroupInvitePayloadSemantic.PAIRWISE_MESH_SDP_INVITE

    fun isActive(attempt: OutboundGroupInviteAttempt?): Boolean {
        if (attempt == null) return false
        if (!attempt.handoffSucceeded) return false
        if (attempt.terminalReason != null) return false
        return isAdmissionRelevantSemantic(attempt.semantic)
    }

    fun activeAttempt(session: TalkbackSession, remoteModuleId: String): OutboundGroupInviteAttempt? {
        val attempt = session.outboundGroupInviteAttemptsByRemoteModule[remoteModuleId]
        return attempt?.takeIf { isActive(it) }
    }

    fun isRemoteSignalingInFlight(session: TalkbackSession, remoteModuleId: String): Boolean =
        isActive(session.outboundGroupInviteAttemptsByRemoteModule[remoteModuleId])

    fun recordSuccessfulHandoff(
        session: TalkbackSession,
        remoteModuleId: String,
        sessionId: String,
        semantic: GroupInvitePayloadSemantic,
        offerLineageId: String,
        deliveryAttemptId: Long,
        issuedAtMs: Long = System.currentTimeMillis()
    ) {
        session.outboundGroupInviteAttemptsByRemoteModule[remoteModuleId] =
            OutboundGroupInviteAttempt(
                offerLineageId = offerLineageId,
                deliveryAttemptId = deliveryAttemptId,
                sessionId = sessionId,
                remoteModuleId = remoteModuleId,
                semantic = semantic,
                issuedAtMs = issuedAtMs,
                handoffSucceeded = true,
                terminalReason = null
            )
    }

    fun markTerminal(session: TalkbackSession, remoteModuleId: String, reason: String) {
        val existing = session.outboundGroupInviteAttemptsByRemoteModule[remoteModuleId] ?: return
        session.outboundGroupInviteAttemptsByRemoteModule[remoteModuleId] =
            existing.copy(terminalReason = reason)
    }
}
