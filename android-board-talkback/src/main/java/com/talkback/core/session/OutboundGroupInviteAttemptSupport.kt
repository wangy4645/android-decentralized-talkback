package com.talkback.core.session

import com.talkback.core.qos.IceConnectivity

/**
 * Active/terminal evaluation for outbound GROUP_INVITE admission evidence (#180 F2).
 */
object OutboundGroupInviteAttemptSupport {

    /**
     * An attempt whose peer never answered must not gate admission forever: MESH_LINK_COMPLETED
     * is not reachable when the remote GROUP_ACCEPT was lost.
     */
    const val ATTEMPT_STALE_TIMEOUT_MS = 10_000L

    const val TERMINAL_REASON_TIMEOUT = "ATTEMPT_TIMEOUT"

    const val TERMINAL_REASON_GROUP_ACCEPT = "GROUP_ACCEPT_RECEIVED"

    private val DELIVERY_SATISFIED_TERMINAL_REASONS =
        setOf(
            "MESH_LINK_COMPLETED",
            TERMINAL_REASON_GROUP_ACCEPT,
        )

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

    fun isDeliverySatisfied(attempt: OutboundGroupInviteAttempt?): Boolean {
        if (attempt == null || !attempt.handoffSucceeded) return false
        val reason = attempt.terminalReason ?: return false
        return reason in DELIVERY_SATISFIED_TERMINAL_REASONS
    }

    fun isDeliverySatisfied(session: TalkbackSession, remoteModuleId: String): Boolean =
        isDeliverySatisfied(session.outboundGroupInviteAttemptsByRemoteModule[remoteModuleId])

    /** Positive completion from correlated GROUP_ACCEPT (GIDR-T2). */
    fun markDeliverySatisfiedFromGroupAccept(session: TalkbackSession, remoteModuleId: String) {
        val existing = session.outboundGroupInviteAttemptsByRemoteModule[remoteModuleId] ?: return
        if (!isAdmissionRelevantSemantic(existing.semantic)) return
        if (isDeliverySatisfied(existing)) return
        markTerminal(session, remoteModuleId, TERMINAL_REASON_GROUP_ACCEPT)
    }

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

    fun isStale(
        attempt: OutboundGroupInviteAttempt?,
        nowMs: Long,
        timeoutMs: Long = ATTEMPT_STALE_TIMEOUT_MS
    ): Boolean {
        val active = attempt?.takeIf { isActive(it) } ?: return false
        return nowMs - active.issuedAtMs >= timeoutMs
    }

    /**
     * When mesh signaling/ICE is still incomplete, expire in-flight attempts sooner than
     * [ATTEMPT_STALE_TIMEOUT_MS] while ICE is settling ([CHECKING]/[NEW]) or lost.
     */
    fun staleTimeoutMsForMediaProgress(
        iceState: String?,
        meshLinkCompleted: Boolean,
    ): Long {
        if (meshLinkCompleted || IceConnectivity.isConnected(iceState)) {
            return ATTEMPT_STALE_TIMEOUT_MS
        }
        return when {
            IceConnectivity.isNegotiating(iceState) || IceConnectivity.isLost(iceState) ->
                GroupMeshReconciler.CHECKING_STUCK_MS
            else -> ATTEMPT_STALE_TIMEOUT_MS
        }
    }

    /**
     * Pairwise mesh re-offer after a failed cycle must ICE-restart when a local engine exists;
     * otherwise retries leave the PC in HAVE_LOCAL_OFFER while the remote stays CHECKING.
     */
    fun shouldIceRestartForPairwiseOffer(
        session: TalkbackSession,
        remoteModuleId: String,
        iceState: String?,
        hasMeshEngine: Boolean,
    ): Boolean {
        if (remoteModuleId in session.meshCompletedModules) return true
        if (IceConnectivity.isConnected(iceState)) return false
        if (!hasMeshEngine) return false
        val attempt = session.outboundGroupInviteAttemptsByRemoteModule[remoteModuleId]
        if (isActive(attempt)) return false
        if (attempt?.terminalReason == TERMINAL_REASON_TIMEOUT) return true
        if (attempt != null && attempt.terminalReason != null && !isDeliverySatisfied(attempt)) {
            return true
        }
        // No attempt history and no ICE snapshot yet means the first offer may still be in flight:
        // an ICE-restart offer here changes ufrag and fences the pending answer as STALE (11-06).
        if (iceState == null) return false
        return IceConnectivity.isNegotiating(iceState) || IceConnectivity.isLost(iceState)
    }

    /** Returns the age of the expired attempt, or null when nothing was expired. */
    fun expireStaleAttempt(
        session: TalkbackSession,
        remoteModuleId: String,
        nowMs: Long,
        timeoutMs: Long = ATTEMPT_STALE_TIMEOUT_MS,
        iceState: String? = null,
        meshLinkCompleted: Boolean = false,
        controllerOwnsNegotiation: Boolean = false,
    ): Long? {
        // EdgeLinkController already retransmits / restarts / rebuilds this edge; expiring the
        // attempt would let GIDR mint a new lineage (11-06 deadlock class).
        if (controllerOwnsNegotiation && isActive(session.outboundGroupInviteAttemptsByRemoteModule[remoteModuleId])) {
            return null
        }
        val attempt = session.outboundGroupInviteAttemptsByRemoteModule[remoteModuleId]
        val effectiveTimeout =
            if (timeoutMs != ATTEMPT_STALE_TIMEOUT_MS) {
                timeoutMs
            } else {
                staleTimeoutMsForMediaProgress(iceState, meshLinkCompleted)
            }
        if (!isStale(attempt, nowMs, effectiveTimeout)) return null
        markTerminal(session, remoteModuleId, TERMINAL_REASON_TIMEOUT)
        return nowMs - (attempt?.issuedAtMs ?: nowMs)
    }

    fun markTerminal(session: TalkbackSession, remoteModuleId: String, reason: String) {
        val existing = session.outboundGroupInviteAttemptsByRemoteModule[remoteModuleId] ?: return
        session.outboundGroupInviteAttemptsByRemoteModule[remoteModuleId] =
            existing.copy(terminalReason = reason)
    }
}
