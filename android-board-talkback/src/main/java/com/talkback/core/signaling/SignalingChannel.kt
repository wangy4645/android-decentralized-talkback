package com.talkback.core.signaling

import com.talkback.core.model.SignalEnvelope

data class PeerTarget(val host: String, val port: Int)

interface SignalingChannel {
    fun start(localPort: Int)
    fun stop()
    fun send(target: PeerTarget, envelope: SignalEnvelope)
    /**
     * ADR-0042: local datagram submission result for reattach consumers only.
     *
     * Returns true only when the datagram was submitted successfully (sendto success).
     * Default treats [send] as success (in-memory / non-UDP fakes). UDP overrides with
     * real sendto truth. Does not change [send] throw/void semantics for other callers.
     */
    fun sendReportingSubmission(target: PeerTarget, envelope: SignalEnvelope): Boolean {
        send(target, envelope)
        return true
    }
    /**
     * Peer-repair / PRR outbound. MUST NOT satisfy L.1 FIRST_OUTBOUND_AFTER_REBIND (INV-SIG-017).
     * Default falls back to [send] for in-memory fakes; UDP overrides.
     */
    fun sendRepairAnnounce(target: PeerTarget, envelope: SignalEnvelope) = send(target, envelope)
    fun onMessage(listener: (SignalEnvelope, PeerTarget) -> Unit)
}
