package com.talkback.core.discovery

import com.talkback.core.model.ModuleId
import com.talkback.core.model.SignalEnvelope
import com.talkback.core.signaling.PeerTarget
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Merges static peer list (override), gossip sweep (primary auto), and NSD/mDNS (fallback).
 * Priority: static > gossip > nsd
 */
class CompositeModuleDiscoveryService(
    private val staticDiscovery: StaticPeerDiscoveryService,
    private val gossipDiscovery: ModuleDiscoveryService,
    private val nsdDiscovery: ModuleDiscoveryService
) : ModuleDiscoveryService, DiscoverySignalHandler, GossipDiscoveryControl {
    private val merged = ConcurrentHashMap<String, ModulePresence>()
    private val sourceByModule = ConcurrentHashMap<String, String>()
    private val listeners = CopyOnWriteArrayList<(List<ModulePresence>) -> Unit>()
    private var localModule: ModuleId? = null

    override fun start(localModule: ModuleId, signalingPort: Int) {
        this.localModule = localModule
        staticDiscovery.onPresenceChanged { applySource("static", it) }
        gossipDiscovery.onPresenceChanged { applySource("gossip", it) }
        nsdDiscovery.onPresenceChanged { applySource("nsd", it) }
        staticDiscovery.start(localModule, signalingPort)
        gossipDiscovery.start(localModule, signalingPort)
        nsdDiscovery.start(localModule, signalingPort)
    }

    override fun stop() {
        listeners.clear()
        runCatching { staticDiscovery.stop() }
        runCatching { gossipDiscovery.stop() }
        runCatching { nsdDiscovery.stop() }
        merged.clear()
        sourceByModule.clear()
    }

    override fun onPresenceChanged(listener: (List<ModulePresence>) -> Unit) {
        listeners.add(listener)
        publish()
    }

    override fun onDiscoveryProbe(signal: SignalEnvelope, fromPeer: PeerTarget) {
        (gossipDiscovery as? DiscoverySignalHandler)?.onDiscoveryProbe(signal, fromPeer)
    }

    override fun onDiscoveryAnnounce(signal: SignalEnvelope, fromPeer: PeerTarget) {
        (gossipDiscovery as? DiscoverySignalHandler)?.onDiscoveryAnnounce(signal, fromPeer)
    }

    override fun resetAndSweep() {
        (gossipDiscovery as? GossipDiscoveryControl)?.resetAndSweep()
    }

    private fun applySource(source: String, modules: List<ModulePresence>) {
        val incomingKeys = modules.map { it.moduleId.value }.toSet()
        sourceByModule.keys.toList().forEach { key ->
            if (sourceByModule[key] == source && key !in incomingKeys) {
                merged.remove(key)
                sourceByModule.remove(key)
            }
        }
        modules.forEach { m ->
            val key = m.moduleId.value
            val prevSource = sourceByModule[key]
            val prevPriority = sourcePriority(prevSource)
            val newPriority = sourcePriority(source)
            if (prevSource != null && prevPriority > newPriority) {
                merged[key]?.let { existing ->
                    merged[key] = existing.copy(
                        endpointCount = maxOf(existing.endpointCount, m.endpointCount),
                        lastSeenMs = maxOf(existing.lastSeenMs, m.lastSeenMs)
                    )
                }
            } else {
                merged[key] = m
                sourceByModule[key] = source
            }
        }
        publish()
    }

    private fun sourcePriority(source: String?): Int = when (source) {
        "static" -> 3
        "gossip" -> 2
        "nsd" -> 1
        else -> 0
    }

    private fun publish() {
        val list = merged.values
            .filter { it.moduleId.value != localModule?.value }
            .sortedBy { it.moduleId.value }
        listeners.forEach { it(list) }
    }

    /** Discovery source for a module: static, gossip, or nsd. */
    fun discoverySource(moduleId: String): String? = sourceByModule[moduleId]
}
