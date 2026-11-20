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
    /** ADR-0043 P1: issuer queries authority for accepted membership context existence. */
    MEMBERSHIP_CONTEXT_EXISTENCE_QUERY,
    /** ADR-0043 P1: authority answers membership context existence for scope + epoch. */
    MEMBERSHIP_CONTEXT_EXISTENCE_RESPONSE,
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
    ENDPOINT_TEXT,
    /** Control-plane Channel broadcast text (ADR-0041). Address is channelId, not multi-to. */
    CHANNEL_TEXT,
    /** Data-plane attachment meta for 1:1 Endpoint messages (V2). Chunks follow on ENDPOINT_ATTACHMENT_CHUNK. */
    ENDPOINT_ATTACHMENT_META,
    ENDPOINT_ATTACHMENT_CHUNK,
    /** Bulk endpoint attachment meta (payload on DataChannel). */
    ENDPOINT_BULK_OPEN,
    ENDPOINT_BULK_READY,
    ENDPOINT_BULK_ABORT,
    /** Channel-scoped attachment broadcast (fan-out meta + chunks per member). */
    CHANNEL_ATTACHMENT_META,
    CHANNEL_ATTACHMENT_CHUNK,
    /** ADR-0057: request Current Generation Fact candidate from a holder. */
    FACT_REQUEST,
    /** ADR-0057: candidate material for local Verification Boundary (not yet authoritative). */
    FACT_RESPONSE_CANDIDATE,
    /** ADR-0057: holder lacks usable accepted Current to re-provide. */
    FACT_RESPONSE_INSUFFICIENT,
    /** ADR-0058: Profile 01 signed fact relay (base64 SignedFact bytes). Shadow ingress only in Phase A. */
    CONFERENCE_SIGNED_FACT,
    /**
     * Pairwise mesh: answerer asks the lexicographic offerer to re-offer this edge
     * (EDGE-LINK-CONTROLLER-001 Phase 2b). Not a membership resync.
     */
    GROUP_REOFFER_REQUEST,
    /** Conference-scoped live video: non-authoritative request or authority echo. */
    VIDEO_PUBLISH,
    /** Authority denies a second publisher for the same conference slot. */
    VIDEO_PUBLISH_DENY,
    VIDEO_STOP,
    /** Viewer-initiated recvonly offer after tap-to-watch. Not a subscribe grant. */
    VIDEO_OFFER,
    VIDEO_ANSWER,
    VIDEO_ICE,
    /** Channel task dispatch. Target snapshot is in payload; not a Session. */
    TASK_ISSUE,
    TASK_ACK,
    TASK_CLOSE,
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
