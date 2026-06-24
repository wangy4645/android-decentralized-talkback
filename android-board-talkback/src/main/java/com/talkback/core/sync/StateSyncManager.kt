package com.talkback.core.sync

import com.talkback.core.discovery.ModulePresence
import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.EndpointId
import com.talkback.core.model.EndpointPriority
import com.talkback.core.model.ModuleId
import com.talkback.core.model.RemoteEndpointInfo
import java.util.concurrent.ConcurrentHashMap

data class RemoteModuleState(
    val presence: ModulePresence,
    val endpoints: List<RemoteEndpointInfo>,
    val lastHelloMs: Long
)

/**
 * Aggregated view of remote modules and their endpoint directories.
 */
class StateSyncManager {
    private val modules = ConcurrentHashMap<String, RemoteModuleState>()

    fun updatePresence(presenceList: List<ModulePresence>) {
        presenceList.forEach { p ->
            val existing = modules[p.moduleId.value]
            modules[p.moduleId.value] = RemoteModuleState(
                presence = p,
                endpoints = existing?.endpoints ?: emptyList(),
                lastHelloMs = existing?.lastHelloMs ?: 0L
            )
        }
    }

    /** Drop modules with no recent HELLO (discovery presence alone does not imply liveness). */
    fun pruneStaleModules(staleAfterMs: Long, now: Long = System.currentTimeMillis()) {
        modules.entries.removeIf { (_, state) ->
            state.lastHelloMs <= 0L || now - state.lastHelloMs > staleAfterMs
        }
    }

    fun updateEndpoints(moduleId: String, endpoints: List<RemoteEndpointInfo>, helloMs: Long = System.currentTimeMillis()) {
        val existing = modules[moduleId]
        if (existing != null) {
            modules[moduleId] = existing.copy(endpoints = endpoints, lastHelloMs = helloMs)
        } else {
            modules[moduleId] = RemoteModuleState(
                presence = ModulePresence(
                    moduleId = ModuleId(moduleId),
                    host = "",
                    port = 0,
                    endpointCount = endpoints.count { it.online },
                    lastSeenMs = helloMs
                ),
                endpoints = endpoints,
                lastHelloMs = helloMs
            )
        }
    }

    fun allModules(): List<RemoteModuleState> = modules.values.sortedBy { it.presence.moduleId.value }

    fun onlineEndpointAddresses(moduleId: String): List<EndpointAddress> {
        val state = modules[moduleId] ?: return emptyList()
        return state.endpoints
            .filter { it.online }
            .map { EndpointAddress(ModuleId(moduleId), EndpointId(it.endpointId)) }
    }

    fun get(moduleId: String): RemoteModuleState? = modules[moduleId]

    fun registeredPriority(endpoint: EndpointAddress): EndpointPriority? {
        val state = modules[endpoint.moduleId.value] ?: return null
        return state.endpoints
            .firstOrNull { it.endpointId == endpoint.endpointId.value }
            ?.priority
    }
}
