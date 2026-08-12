package com.talkback.core.session

import com.talkback.core.model.EndpointAddress

/**
 * #179 Phase 1 — pure bootstrap admission intent lifecycle (producer contract).
 */
object GroupBootstrapAdmissionSupport {

    sealed interface EdgeReadyDecision {
        data object NoAction : EdgeReadyDecision
        data class Deferred(val reason: String) : EdgeReadyDecision
        data class IssueInvite(val moduleId: String, val endpoint: EndpointAddress) : EdgeReadyDecision
    }

    data class EdgeReadyEvaluationInput(
        val intent: BootstrapAdmissionIntent,
        val endpoint: EndpointAddress?,
        val peerEdgeReady: Boolean,
        val authorityAdmissible: Boolean,
        val isInviteProducer: Boolean,
        val admissionIncomplete: Boolean,
        val cooldownElapsed: Boolean
    )

    fun key(channelId: String, targetModuleId: String): BootstrapAdmissionIntentKey =
        BootstrapAdmissionIntentKey(channelId, targetModuleId)

    fun create(
        channelId: String,
        targetModuleId: String,
        createReason: String,
        nowMs: Long = System.currentTimeMillis()
    ): BootstrapAdmissionIntent {
        val intentKey = key(channelId, targetModuleId)
        return BootstrapAdmissionIntent(
            key = intentKey,
            createdAtMs = nowMs,
            createReason = createReason,
            state = BootstrapAdmissionIntentState.PENDING,
            updatedAtMs = nowMs
        )
    }

    fun markWaiting(
        intent: BootstrapAdmissionIntent,
        waitingReason: String,
        nowMs: Long = System.currentTimeMillis()
    ): BootstrapAdmissionIntent = intent.copy(
        state = BootstrapAdmissionIntentState.WAITING_EDGE_READY,
        waitingReason = waitingReason,
        updatedAtMs = nowMs
    )

    fun markInviteSent(
        intent: BootstrapAdmissionIntent,
        sessionId: String,
        nowMs: Long = System.currentTimeMillis()
    ): BootstrapAdmissionIntent = intent.copy(
        state = BootstrapAdmissionIntentState.INVITE_SENT,
        sessionId = sessionId,
        waitingReason = null,
        updatedAtMs = nowMs
    )

    fun markAccepted(
        intent: BootstrapAdmissionIntent,
        nowMs: Long = System.currentTimeMillis()
    ): BootstrapAdmissionIntent = intent.copy(
        state = BootstrapAdmissionIntentState.ACCEPTED,
        updatedAtMs = nowMs
    )

    private val unresolvedBootstrapStates = setOf(
        BootstrapAdmissionIntentState.PENDING,
        BootstrapAdmissionIntentState.WAITING_EDGE_READY,
        BootstrapAdmissionIntentState.INVITE_SENT
    )

    /** Producer-side: unresolved bootstrap intent must not fall back to GROUP_JOIN. */
    fun hasUnresolvedBootstrapAdmissionIntent(intent: BootstrapAdmissionIntent?): Boolean =
        intent != null && intent.state in unresolvedBootstrapStates

    fun shouldSuppressGroupJoinFallback(
        intent: BootstrapAdmissionIntent?,
        session: TalkbackSession,
        moduleId: String
    ): Boolean {
        if (hasUnresolvedBootstrapAdmissionIntent(intent)) return true
        return isBootstrapAdmissionPeer(session, moduleId)
    }

    /** #179-C: edge-ready retry only from pre-invite states (idempotency guard). */
    fun eligibleForEdgeReadyRetry(intent: BootstrapAdmissionIntent): Boolean =
        intent.state == BootstrapAdmissionIntentState.PENDING ||
            intent.state == BootstrapAdmissionIntentState.WAITING_EDGE_READY

    fun peerAdmissionIncomplete(session: TalkbackSession, moduleId: String): Boolean {
        if (moduleId in session.pendingInviteeEndpoints) return true
        val canonical = GroupMembershipSupport.canonicalMemberModuleIds(session)
            .map { it.value }
            .toSet()
        if (moduleId !in canonical) return true
        return moduleId !in session.meshCompletedModules
    }

    fun evaluateEdgeReadyRetry(input: EdgeReadyEvaluationInput): EdgeReadyDecision {
        if (!eligibleForEdgeReadyRetry(input.intent)) return EdgeReadyDecision.NoAction
        if (!input.peerEdgeReady) return EdgeReadyDecision.Deferred("PEER_EDGE_NOT_READY")
        if (!input.isInviteProducer) return EdgeReadyDecision.Deferred("NOT_INVITE_PRODUCER")
        if (!input.authorityAdmissible) return EdgeReadyDecision.Deferred("AUTHORITY_NOT_ADMISSIBLE")
        if (!input.admissionIncomplete) return EdgeReadyDecision.NoAction
        if (input.endpoint == null) return EdgeReadyDecision.Deferred("PEER_NOT_DISCOVERED")
        if (!input.cooldownElapsed) return EdgeReadyDecision.Deferred("COOLDOWN_ACTIVE")
        return EdgeReadyDecision.IssueInvite(
            moduleId = input.intent.key.targetModuleId,
            endpoint = input.endpoint
        )
    }

    /** Bootstrap admission = pending invitee not yet in canonical roster. */
    fun isBootstrapAdmissionPeer(session: TalkbackSession, moduleId: String): Boolean =
        moduleId in session.pendingInviteeEndpoints &&
            session.groupMembers.none { it.moduleId.value == moduleId }
}
