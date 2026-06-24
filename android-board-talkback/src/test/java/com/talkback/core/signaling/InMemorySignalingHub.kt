package com.talkback.core.signaling

import com.talkback.core.model.SignalEnvelope
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory signaling router for JVM integration tests (no UDP).
 */
class InMemorySignalingHub {
    private val byPort = ConcurrentHashMap<Int, InMemorySignalingChannel>()

    fun register(channel: InMemorySignalingChannel) {
        byPort[channel.localPort] = channel
    }

    fun unregister(channel: InMemorySignalingChannel) {
        byPort.remove(channel.localPort)
    }

    fun deliver(target: PeerTarget, envelope: SignalEnvelope, from: InMemorySignalingChannel) {
        val channel = byPort[target.port] ?: return
        channel.deliver(envelope, PeerTarget(from.host, from.localPort))
    }
}

class InMemorySignalingChannel(
    private val hub: InMemorySignalingHub,
    val host: String,
    val localPort: Int
) : SignalingChannel {
    private var listener: ((SignalEnvelope, PeerTarget) -> Unit)? = null
    @Volatile
    private var running = false

    override fun start(localPort: Int) {
        require(localPort == this.localPort) {
            "InMemorySignalingChannel bound to ${this.localPort}, got $localPort"
        }
        running = true
        hub.register(this)
    }

    override fun stop() {
        running = false
        hub.unregister(this)
    }

    override fun send(target: PeerTarget, envelope: SignalEnvelope) {
        if (!running) return
        hub.deliver(target, envelope, this)
    }

    override fun onMessage(listener: (SignalEnvelope, PeerTarget) -> Unit) {
        this.listener = listener
    }

    internal fun deliver(envelope: SignalEnvelope, fromPeer: PeerTarget) {
        if (!running) return
        listener?.invoke(envelope, fromPeer)
    }
}
