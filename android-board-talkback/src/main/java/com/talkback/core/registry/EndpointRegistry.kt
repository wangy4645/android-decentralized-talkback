package com.talkback.core.registry

import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.EndpointId
import com.talkback.core.model.EndpointPriority
import com.talkback.core.model.ModuleId
import java.util.concurrent.ConcurrentHashMap

data class EndpointDescriptor(
    val address: EndpointAddress,
    val displayName: String,
    val online: Boolean,
    val priority: EndpointPriority = EndpointPriority.NORMAL
)

class EndpointRegistry(private val moduleId: ModuleId) {
    private val endpoints = ConcurrentHashMap<String, EndpointDescriptor>()

    fun upsertLocalEndpoint(
        endpointId: EndpointId,
        displayName: String,
        online: Boolean,
        priority: EndpointPriority = EndpointPriority.NORMAL
    ): EndpointDescriptor {
        val descriptor = EndpointDescriptor(
            address = EndpointAddress(moduleId, endpointId),
            displayName = displayName,
            online = online,
            priority = priority
        )
        endpoints[descriptor.address.key] = descriptor
        return descriptor
    }

    fun markOffline(endpointId: EndpointId) {
        val key = EndpointAddress(moduleId, endpointId).key
        endpoints[key]?.let {
            endpoints[key] = it.copy(online = false)
        }
    }

    fun resolve(address: EndpointAddress): EndpointDescriptor? = endpoints[address.key]

    fun allOnline(): List<EndpointDescriptor> = endpoints.values.filter { it.online }.sortedBy { it.address.key }
}
