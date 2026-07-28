package com.talkback.core.signaling.peer

/**
 * Authenticated signaling inbound fact (ADR-0022 INV-SIG-006).
 * States only that a trusted envelope arrived from remoteModuleId on receiveGeneration;
 * never announces PEER_EDGE_SIGNALING_READY.
 */
data class PeerInboundObserved(
    val remoteModuleId: String,
    val socketId: Long,
    val receiveGeneration: Long,
    val observedAtMs: Long,
    val source: String = SOURCE_NETWORK_SIGNAL
) {
    companion object {
        const val SOURCE_NETWORK_SIGNAL = "NETWORK_SIGNAL"
    }
}