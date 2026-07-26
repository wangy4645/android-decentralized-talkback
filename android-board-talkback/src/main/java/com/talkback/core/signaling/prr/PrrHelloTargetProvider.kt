package com.talkback.core.signaling.prr

import com.talkback.core.discovery.ModulePresence
import com.talkback.core.model.ModuleId
import com.talkback.core.signaling.PeerTarget

/** Callable peer targets for PRR re-announce (discovery/static only; not Coordinator). */
fun interface PrrHelloTargetProvider {
    fun helloTargets(): List<PeerTarget>
}

/** Tracks latest discovery presence for PRR HELLO targets. */
class DiscoveryPrrHelloTargetProvider(
    private val localModuleId: ModuleId
) : PrrHelloTargetProvider {
    @Volatile
    private var presence: List<ModulePresence> = emptyList()

    fun updatePresence(modules: List<ModulePresence>) {
        presence = modules
    }

    override fun helloTargets(): List<PeerTarget> =
        presence
            .asSequence()
            .filter { it.moduleId != localModuleId && it.host.isNotBlank() && it.port > 0 }
            .map { PeerTarget(it.host, it.port) }
            .distinctBy { "${it.host}:${it.port}" }
            .toList()
}
