package com.talkback.core.session

import com.talkback.core.model.SignalType

/**
 * ADR-0050 R2a — negotiation ingress readiness (pure helpers).
 *
 * Ready = peer can receive/process a restart offer.
 * Must NOT be inferred from ICE/media/EDGE_RECOVERED/HELLO/HEARTBEAT alone.
 */
internal object NegotiationIngressGate {

    /** Signal types that evidence negotiation-plane ingress (not discovery heartbeat). */
    fun isNegotiationCapableSignal(type: SignalType): Boolean = when (type) {
        SignalType.HELLO,
        SignalType.HEARTBEAT,
        SignalType.DISCOVERY_PROBE,
        SignalType.DISCOVERY_ANNOUNCE -> false
        SignalType.WEBRTC_OFFER,
        SignalType.WEBRTC_ANSWER,
        SignalType.WEBRTC_ICE,
        SignalType.GROUP_JOIN,
        SignalType.GROUP_INVITE,
        SignalType.GROUP_ACCEPT,
        SignalType.GROUP_LEAVE,
        SignalType.GROUP_RESYNC_REQUEST,
        SignalType.CONFERENCE_REJOIN,
        SignalType.RECOVERY_REATTACH_ACK,
        SignalType.CALL_INVITE,
        SignalType.CALL_ACCEPT,
        SignalType.CALL_REJECT,
        SignalType.MEMBERSHIP_CONTEXT_EXISTENCE_QUERY,
        SignalType.MEMBERSHIP_CONTEXT_EXISTENCE_RESPONSE,
        SignalType.ENDPOINT_TEXT,
        SignalType.CHANNEL_TEXT,
        SignalType.FLOOR_REQUEST,
        SignalType.FLOOR_REQUEST_CANCEL,
        SignalType.FLOOR_GRANTED,
        SignalType.FLOOR_DENY,
        SignalType.FLOOR_PREEMPTED,
        SignalType.FLOOR_RELEASE,
        SignalType.HANGUP -> true
    }

    /**
     * Episode-scoped ready: negotiation-capable inbound observed at/after recovery start,
     * and still fresh relative to [nowMs].
     */
    fun isReady(
        lastNegotiationCapableInboundAtMs: Long?,
        recoveryStartedAtMs: Long,
        nowMs: Long,
        freshMs: Long
    ): Boolean {
        val lastAt = lastNegotiationCapableInboundAtMs ?: return false
        if (lastAt < recoveryStartedAtMs) return false
        if (nowMs - lastAt > freshMs) return false
        return true
    }
}
