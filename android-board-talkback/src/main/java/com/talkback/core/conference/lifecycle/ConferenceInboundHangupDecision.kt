package com.talkback.core.conference.lifecycle

/**
 * Inbound conference [HANGUP] semantics (A7-B).
 *
 * Sender identity is the live session's initiator/host module id — never local role
 * and never the reason string. Unknown or non-live sessionId is no-op (no channel fallback).
 */
object ConferenceInboundHangupDecision {
    enum class Action {
        NO_OP,
        TERMINATE_CONFERENCE,
        PRUNE_PEER
    }

    fun decide(
        wireSessionId: String,
        liveSessionId: String?,
        initiatorModuleId: String?,
        senderModuleId: String
    ): Action {
        if (liveSessionId.isNullOrBlank() || liveSessionId != wireSessionId) {
            return Action.NO_OP
        }
        val host = initiatorModuleId?.takeIf { it.isNotBlank() } ?: return Action.NO_OP
        return if (senderModuleId == host) {
            Action.TERMINATE_CONFERENCE
        } else {
            Action.PRUNE_PEER
        }
    }
}
