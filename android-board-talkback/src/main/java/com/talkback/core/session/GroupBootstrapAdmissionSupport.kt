package com.talkback.core.session

/**
 * #179 Phase 1 — pure bootstrap admission intent lifecycle (producer contract).
 */
object GroupBootstrapAdmissionSupport {

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

    /** Bootstrap admission = pending invitee not yet in canonical roster. */
    fun isBootstrapAdmissionPeer(session: TalkbackSession, moduleId: String): Boolean =
        moduleId in session.pendingInviteeEndpoints &&
            session.groupMembers.none { it.moduleId.value == moduleId }
}
