package com.talkback.core.signaling

import com.talkback.core.model.SignalEnvelope

interface DiscoveryTransport {
    fun start(listenPort: Int, onMessage: (SignalEnvelope, PeerTarget) -> Unit)
    fun stop()
    fun send(target: PeerTarget, envelope: SignalEnvelope)
}
