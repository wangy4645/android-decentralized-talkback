package com.talkback.core.conference.transport

/**
 * Phase 1 Slice 4 — dual-node multicast SRTP network closure constants.
 * Isolated from underlay probe port 46999.
 */
object Slice4MulticastNetworkConstants {
    const val LOG_TAG = "SLICE4_MC_SRTP"
    const val DEFAULT_MULTICAST_ADDRESS: String = "239.255.42.99"
    const val DEFAULT_MEDIA_PORT: Int = 47099
    const val NOMINAL_PPS: Int = 50
    const val DEFAULT_IFACE: String = "wlan0"
}
