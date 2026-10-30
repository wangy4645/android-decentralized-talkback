package com.talkback.core.conference.probe

import com.talkback.core.conference.wire.ConferenceWireConstants

/**
 * ADR-0058 underlay dual-node multicast probe — frozen wire references only.
 * Does not modify Profile 02/03 contracts; uses them as adjudication baselines.
 */
object UnderlayMulticastProbeConstants {
    const val LOG_TAG = "UNDERLAY_MC_PROBE"

    /** Profile 02 frozen nominal send rate. */
    const val NOMINAL_PPS: Int = 50

    /** Profile 02 SafeUdpPayloadBytes. */
    val PAYLOAD_BYTES: Int = ConferenceWireConstants.SAFE_UDP_PAYLOAD_BYTES

    /** Nominal inter-arrival at 50 pps (ms). */
    const val NOMINAL_INTERVAL_MS: Long = 20L

    /** Lab default; not a shared-network field. */
    const val DEFAULT_MULTICAST_ADDRESS: String = "239.255.42.99"

    /** Ephemeral lab port; isolated from production GROUP signaling. */
    const val DEFAULT_MEDIA_PORT: Int = 46999

    /** Profile 03 frozen references for gate comparison (not modified here). */
    const val FROZEN_MAX_PLAYOUT_DELAY_MS: Long = 120L

    const val FROZEN_MAX_CONSECUTIVE_PLC_FRAMES: Int = 5

    const val PACKET_MAGIC: Int = 0x54423538 // "TB58" big-endian
}
