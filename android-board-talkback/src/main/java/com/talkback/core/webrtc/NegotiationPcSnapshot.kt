package com.talkback.core.webrtc

/**
 * Observation-only PeerConnection negotiation facts (4.3-E).
 * Used to distinguish glare / illegal signaling transitions / SDP apply-order bugs
 * without changing negotiation behavior.
 */
data class NegotiationPcSnapshot(
    val signalingState: String = "UNKNOWN",
    val iceConnectionState: String = "UNKNOWN",
    val connectionState: String = "UNKNOWN",
    val localDescriptionType: String? = null,
    val remoteDescriptionType: String? = null
) {
    fun formatFields(): String =
        "signalingState=$signalingState iceConnectionState=$iceConnectionState " +
            "connectionState=$connectionState " +
            "localDesc=${localDescriptionType ?: "NONE"} remoteDesc=${remoteDescriptionType ?: "NONE"}"
}