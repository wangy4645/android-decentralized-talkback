package com.talkback.core.signaling

import com.talkback.core.model.SignalEnvelope

data class PeerTarget(val host: String, val port: Int)

interface SignalingChannel {
    fun start(localPort: Int)
    fun stop()
    fun send(target: PeerTarget, envelope: SignalEnvelope)
    fun onMessage(listener: (SignalEnvelope, PeerTarget) -> Unit)
}
