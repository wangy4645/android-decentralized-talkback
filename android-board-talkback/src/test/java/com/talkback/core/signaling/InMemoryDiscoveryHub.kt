package com.talkback.core.signaling

import com.talkback.core.model.SignalEnvelope
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class InMemoryDiscoveryHub {
    private val byPort = ConcurrentHashMap<Int, CopyOnWriteArrayList<InMemoryDiscoveryTransport>>()

    fun register(transport: InMemoryDiscoveryTransport) {
        byPort.getOrPut(transport.listenPort) { CopyOnWriteArrayList() }.addIfAbsent(transport)
    }

    fun unregister(transport: InMemoryDiscoveryTransport) {
        byPort[transport.listenPort]?.remove(transport)
    }

    fun deliver(target: PeerTarget, envelope: SignalEnvelope, from: InMemoryDiscoveryTransport) {
        val channels = byPort[target.port] ?: return
        val fromPeer = PeerTarget(from.host, from.listenPort)
        channels.forEach { channel ->
            if (channel !== from) {
                channel.deliver(envelope, fromPeer)
            }
        }
    }
}

class InMemoryDiscoveryTransport(
    private val hub: InMemoryDiscoveryHub,
    val host: String,
    val listenPort: Int
) : DiscoveryTransport {
    private var listener: ((SignalEnvelope, PeerTarget) -> Unit)? = null
    @Volatile
    private var running = false

    override fun start(listenPort: Int, onMessage: (SignalEnvelope, PeerTarget) -> Unit) {
        require(listenPort == this.listenPort)
        this.listener = onMessage
        running = true
        hub.register(this)
    }

    override fun stop() {
        running = false
        hub.unregister(this)
        listener = null
    }

    override fun send(target: PeerTarget, envelope: SignalEnvelope) {
        if (!running) return
        hub.deliver(target, envelope, this)
    }

    internal fun deliver(envelope: SignalEnvelope, fromPeer: PeerTarget) {
        if (!running) return
        listener?.invoke(envelope, fromPeer)
    }
}
