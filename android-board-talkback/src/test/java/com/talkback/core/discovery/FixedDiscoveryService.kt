package com.talkback.core.discovery

import com.talkback.core.model.ModuleId

/**
 * Fixed peer list for deterministic LAN integration tests.
 */
class FixedDiscoveryService(
    private val localModuleId: ModuleId,
    private val allPeers: List<ModulePresence>
) : ModuleDiscoveryService {
    private var listener: ((List<ModulePresence>) -> Unit)? = null

    override fun start(localModule: ModuleId, signalingPort: Int) {
        publish()
    }

    override fun stop() {
        listener = null
    }

    override fun onPresenceChanged(listener: (List<ModulePresence>) -> Unit) {
        this.listener = listener
        publish()
    }

    fun publishNow() {
        publish()
    }

    private fun publish() {
        listener?.invoke(allPeers.filter { it.moduleId != localModuleId })
    }
}
