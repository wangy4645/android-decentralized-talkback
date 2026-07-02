package com.talkback.core.session

import com.talkback.core.model.EndpointAddress
import com.talkback.core.util.TalkbackLog

/**
 * F1: Group session commit timeline — observe only, never enforce.
 *
 * Correlation keys: sessionId + localModule + channelId + rosterEpoch (when known).
 * Grep one sessionId to reconstruct INVITE → CREATE → DRAIN → JOIN.
 */
object GroupSessionCommitTrace {

    enum class InviteDecision {
        ACCEPT,
        DEFER,
        REJECT,
        IGNORE
    }

    enum class CreateDecision {
        CREATE,
        DEFER,
        IGNORE
    }

    enum class DrainTrigger {
        INVITE_ACCEPT,
        GROUP_ACCEPT,
        GROUP_ACCEPT_DUP,
        CONFERENCE_MESH_REPAIR,
        ICE_CONNECTED,
        ANCHOR_FAILOVER,
        MESH_RETRY,
        MESH_RETRY_TICK
    }

    fun inviteReceived(
        localModule: String,
        sessionId: String,
        channelId: String,
        from: String,
        endpoint: String,
        rosterEpoch: Long? = null
    ) {
        emit(
            "GROUP_INVITE_RECEIVED",
            localModule,
            sessionId,
            channelId,
            rosterEpoch,
            "from=$from endpoint=$endpoint"
        )
    }

    fun inviteSent(
        localModule: String,
        sessionId: String,
        channelId: String,
        target: String,
        rosterEpoch: Long? = null,
        reason: String = "mesh_invite"
    ) {
        emit(
            "GROUP_INVITE_SENT",
            localModule,
            sessionId,
            channelId,
            rosterEpoch,
            "target=$target reason=$reason"
        )
    }

    fun inviteDecision(
        localModule: String,
        sessionId: String,
        channelId: String,
        decision: InviteDecision,
        reason: String,
        rosterEpoch: Long? = null,
        from: String? = null
    ) {
        val fromPart = from?.let { " from=$it" } ?: ""
        emit(
            "GROUP_INVITE_DECISION",
            localModule,
            sessionId,
            channelId,
            rosterEpoch,
            "decision=$decision reason=$reason$fromPart"
        )
    }

    fun sessionCreated(
        localModule: String,
        sessionId: String,
        channelId: String,
        reason: String,
        members: Collection<String>,
        rosterEpoch: Long? = null,
        anchorEpoch: Long? = null
    ) {
        val anchorPart = anchorEpoch?.let { " anchorEpoch=$it" } ?: ""
        emit(
            "GROUP_SESSION_CREATED",
            localModule,
            sessionId,
            channelId,
            rosterEpoch,
            "reason=$reason members=[${members.joinToString(",")}]$anchorPart"
        )
    }

    fun createDecision(
        localModule: String,
        channelId: String,
        decision: CreateDecision,
        reason: String,
        sessionId: String? = null,
        rosterEpoch: Long? = null
    ) {
        val sid = sessionId ?: "-"
        emit(
            "GROUP_CREATE_DECISION",
            localModule,
            sid,
            channelId,
            rosterEpoch,
            "decision=$decision reason=$reason"
        )
    }

    fun joinEnqueue(
        localModule: String,
        sessionId: String,
        channelId: String?,
        reason: String,
        from: String,
        queueSize: Int,
        firstQueuedAtMs: Long?,
        rosterEpoch: Long? = null
    ) {
        val ch = channelId ?: "-"
        val ageMs = firstQueuedAtMs?.let { System.currentTimeMillis() - it }
        val agePart = ageMs?.let { " ageMs=$it" } ?: ""
        val firstPart = firstQueuedAtMs?.let { " firstQueuedAt=$it" } ?: ""
        emit(
            "GROUP_JOIN_ENQUEUE",
            localModule,
            sessionId,
            ch,
            rosterEpoch,
            "reason=$reason from=$from queueSize=$queueSize$firstPart$agePart"
        )
    }

    fun joinDrain(
        localModule: String,
        sessionId: String,
        channelId: String?,
        trigger: DrainTrigger,
        pendingBefore: Int,
        drained: Int,
        remaining: Int,
        rosterEpoch: Long? = null
    ) {
        emit(
            "GROUP_JOIN_DRAIN",
            localModule,
            sessionId,
            channelId ?: "-",
            rosterEpoch,
            "trigger=$trigger pendingBefore=$pendingBefore drained=$drained remaining=$remaining"
        )
    }

    fun joinAccept(
        localModule: String,
        sessionId: String,
        channelId: String?,
        from: String,
        rosterEpoch: Long? = null
    ) {
        emit(
            "GROUP_JOIN_ACCEPT",
            localModule,
            sessionId,
            channelId ?: "-",
            rosterEpoch,
            "from=$from"
        )
    }

    fun joinOffered(
        localModule: String,
        sessionId: String,
        channelId: String,
        target: String,
        signalType: String,
        rosterEpoch: Long? = null,
        ice: String? = null,
        restart: Boolean? = null
    ) {
        val icePart = ice?.let { " ice=$it" } ?: ""
        val restartPart = restart?.let { " restart=$it" } ?: ""
        emit(
            "GROUP_JOIN_OFFERED",
            localModule,
            sessionId,
            channelId,
            rosterEpoch,
            "target=$target signal=$signalType$icePart$restartPart"
        )
    }

    private fun emit(
        event: String,
        localModule: String,
        sessionId: String,
        channelId: String,
        rosterEpoch: Long?,
        details: String
    ) {
        val epochPart = rosterEpoch?.let { " rosterEpoch=$it" } ?: ""
        TalkbackLog.i(
            "$event localModule=$localModule sessionId=$sessionId channelId=$channelId$epochPart $details"
        )
    }

    fun endpointKey(endpoint: EndpointAddress): String =
        "${endpoint.moduleId.value}-${endpoint.endpointId.value}"
}
