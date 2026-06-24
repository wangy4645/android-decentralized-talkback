package com.talkback.core.discovery

import com.talkback.core.model.ModuleId

data class ModulePresence(
    val moduleId: ModuleId,
    val host: String,
    val port: Int,
    val endpointCount: Int,
    val lastSeenMs: Long
)

interface ModuleDiscoveryService {
    fun start(localModule: ModuleId, signalingPort: Int)
    fun stop()
    fun onPresenceChanged(listener: (List<ModulePresence>) -> Unit)
}
