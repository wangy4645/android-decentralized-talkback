package com.talkback.core.discovery

import com.talkback.core.model.ModuleId
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Primary discovery source from configured static peer list (no mDNS required).
 */
class StaticPeerDiscoveryService(
    initialPeers: List<StaticPeerEntry> = emptyList()
) : ModuleDiscoveryService {
    private val peers = CopyOnWriteArrayList<StaticPeerEntry>()
    private var listener: ((List<ModulePresence>) -> Unit)? = null
    private var localModule: ModuleId? = null

    init {
        peers.addAll(initialPeers)
    }

    fun updatePeers(entries: List<StaticPeerEntry>) {
        peers.clear()
        peers.addAll(entries)
        publish()
    }

    override fun start(localModule: ModuleId, signalingPort: Int) {
        this.localModule = localModule
        publish()
    }

    override fun stop() {
        listener = null
    }

    override fun onPresenceChanged(listener: (List<ModulePresence>) -> Unit) {
        this.listener = listener
        publish()
    }

    private fun publish() {
        val local = localModule?.value
        val presence = peers
            .filter { it.moduleId.value != local }
            .map {
                ModulePresence(
                    moduleId = it.moduleId,
                    host = it.host,
                    port = it.port,
                    endpointCount = 0,
                    lastSeenMs = 0L
                )
            }
        listener?.invoke(presence.sortedBy { it.moduleId.value })
    }
}
