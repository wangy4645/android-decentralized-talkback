package com.talkback.core.signaling.prr

import com.talkback.core.signaling.PeerTarget

/** Sends signaling reachability re-announcement (PRR_REANNOUNCE). */
interface SignalingReannounceSender {
    fun sendReannounce(snapshot: LocalEndpointSnapshot, transportEpoch: Long)

    /** Peer-scoped PRR hint (ADR-0022 Q6). Default: no-op for epoch-only senders. */
    fun sendReannounceToPeer(
        snapshot: LocalEndpointSnapshot,
        transportEpoch: Long,
        target: PeerTarget
    ) {
        // Epoch fan-out senders may ignore; UDP sender overrides.
    }
}