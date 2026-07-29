package com.talkback.core.signaling

import com.talkback.core.model.SignalEnvelope

data class PeerTarget(val host: String, val port: Int)

interface SignalingChannel {
    fun start(localPort: Int)
    fun stop()
    fun send(target: PeerTarget, envelope: SignalEnvelope)
    /**
     * Peer-repair / PRR outbound. MUST NOT satisfy L.1 FIRST_OUTBOUND_AFTER_REBIND (INV-SIG-017).
     * Default falls back to [send] for in-memory fakes; UDP overrides.
     */
    fun sendRepairAnnounce(target: PeerTarget, envelope: SignalEnvelope) = send(target, envelope)
    fun onMessage(listener: (SignalEnvelope, PeerTarget) -> Unit)
}
