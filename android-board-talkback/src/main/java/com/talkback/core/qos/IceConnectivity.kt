package com.talkback.core.qos

/**
 * Centralized ICE connection-state semantics for WebRTC [PeerConnection.IceConnectionState].
 * WebRTC progresses NEW -> CHECKING -> CONNECTED -> COMPLETED; LAN links often settle on COMPLETED.
 */
object IceConnectivity {
    val CONNECTED_STATES: Set<String> = setOf("CONNECTED", "COMPLETED")

    fun isConnected(state: String?): Boolean = state in CONNECTED_STATES

    fun isNegotiating(state: String?): Boolean =
        state == null || state == "NEW" || state == "CHECKING"

    fun isLost(state: String?): Boolean =
        state == "FAILED" || state == "DISCONNECTED"

    fun isLiveNegotiation(state: String?): Boolean =
        state == "CHECKING" || state == "NEW" || state == "CONNECTED" || state == "COMPLETED"
}
