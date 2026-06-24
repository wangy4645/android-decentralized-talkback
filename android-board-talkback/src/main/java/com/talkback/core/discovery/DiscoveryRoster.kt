package com.talkback.core.discovery

import com.talkback.core.model.ModuleId
import java.util.concurrent.ConcurrentHashMap

class DiscoveryRoster(private val peerTtlMs: Long) {
    private val entries = ConcurrentHashMap<String, ModulePresence>()

    fun merge(peer: DiscoveryPeerEntry, lastSeenMs: Long = System.currentTimeMillis()) {
        val key = peer.moduleId.value
        val existing = entries[key]
        entries[key] = ModulePresence(
            moduleId = peer.moduleId,
            host = peer.host,
            port = peer.signalingPort,
            endpointCount = maxOf(existing?.endpointCount ?: 0, peer.endpointCount),
            lastSeenMs = lastSeenMs
        )
    }

    /**
     * PEX/indirect peer: add if unknown but do not refresh TTL for existing entries so
     * offline modules are not kept alive by gossip alone.
     */
    fun mergeIndirect(peer: DiscoveryPeerEntry, lastSeenMs: Long = System.currentTimeMillis()) {
        if (entries.containsKey(peer.moduleId.value)) return
        merge(peer, lastSeenMs)
    }

    fun mergeAll(peers: List<DiscoveryPeerEntry>, lastSeenMs: Long = System.currentTimeMillis()) {
        peers.forEach { merge(it, lastSeenMs) }
    }

    fun mergePresence(presence: ModulePresence) {
        val key = presence.moduleId.value
        val existing = entries[key]
        entries[key] = presence.copy(
            endpointCount = maxOf(existing?.endpointCount ?: 0, presence.endpointCount),
            lastSeenMs = maxOf(existing?.lastSeenMs ?: 0L, presence.lastSeenMs)
        )
    }

    fun removeExpired(now: Long = System.currentTimeMillis()): Boolean {
        val expired = entries.filterValues { now - it.lastSeenMs > peerTtlMs }.keys
        if (expired.isEmpty()) return false
        expired.forEach { entries.remove(it) }
        return true
    }

    fun clear() {
        entries.clear()
    }

    fun snapshot(excludeModuleId: ModuleId? = null): List<ModulePresence> =
        entries.values
            .filter { excludeModuleId == null || it.moduleId.value != excludeModuleId.value }
            .sortedBy { it.moduleId.value }

    fun knownPeerEntries(excludeModuleId: ModuleId? = null): List<DiscoveryPeerEntry> =
        snapshot(excludeModuleId).map {
            DiscoveryPeerEntry(
                moduleId = it.moduleId,
                host = it.host,
                signalingPort = it.port,
                endpointCount = it.endpointCount
            )
        }

    fun size(): Int = entries.size
}
