package com.talkback.core.model

enum class SignalType {
    HELLO,
    DISCOVERY_PROBE,
    DISCOVERY_ANNOUNCE,
    HEARTBEAT,
    CALL_INVITE,
    CALL_ACCEPT,
    CALL_REJECT,
    GROUP_INVITE,
    GROUP_ACCEPT,
    GROUP_JOIN,
    GROUP_LEAVE,
    /** Member requests authoritative GROUP_INVITE resync from anchor / bootstrap primary. */
    GROUP_RESYNC_REQUEST,
    /** Participant requests silent rejoin into an existing host conference (no new invite UX). */
    CONFERENCE_REJOIN,
    /** ADR-0035: recovery offer ingress ACK (not SDP answer). */
    RECOVERY_REATTACH_ACK,
    FLOOR_REQUEST,
    FLOOR_REQUEST_CANCEL,
    FLOOR_GRANTED,
    FLOOR_DENY,
    FLOOR_PREEMPTED,
    FLOOR_RELEASE,
    WEBRTC_OFFER,
    WEBRTC_ANSWER,
    WEBRTC_ICE,
    HANGUP,
    /** Control-plane transient text between EndpointKeys (ADR-0039). Not a Session. */
    ENDPOINT_TEXT
}

data class SignalEnvelope(
    val type: SignalType,
    val from: EndpointAddress,
    val to: EndpointAddress?,
    val sessionId: String,
    val timestampMs: Long,
    val payload: String,
    val nonce: String,
    val signature: String,
    /** Stamped at datagram accept; not on the wire (ADR-0022 Q2 receiveGeneration). */
    val receiveGeneration: Long? = null,
    /** Socket that accepted the datagram; not on the wire. */
    val receiveSocketId: Long? = null
)
