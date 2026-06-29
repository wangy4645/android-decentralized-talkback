package com.talkback.core.contacts

import com.talkback.core.sync.RemoteModuleState

/** One Contacts row: a single Endpoint under a callable Module. */
data class ContactEndpointRow(
    val endpointKey: String,
    val moduleOnline: Boolean
)

/**
 * Gate-then-shape Contacts list per ADR-0006: only verified-callable modules with
 * HELLO-filled Endpoint Directory; all endpoints listed (1:N).
 */
object ContactsProjection {

    fun project(
        localModuleId: String,
        callableModuleIds: Set<String>,
        moduleStates: Map<String, RemoteModuleState>,
        isModuleReachable: (moduleId: String, state: RemoteModuleState?) -> Boolean
    ): List<ContactEndpointRow> {
        val rows = LinkedHashMap<String, ContactEndpointRow>()
        callableModuleIds
            .asSequence()
            .filter { it != localModuleId }
            .sorted()
            .forEach { moduleId ->
                val state = moduleStates[moduleId] ?: return@forEach
                if (!hasEndpointDirectory(state)) return@forEach
                val moduleOnline = isModuleReachable(moduleId, state)
                state.endpoints
                    .sortedBy { it.endpointId }
                    .forEach { endpoint ->
                        val key = "$moduleId-${endpoint.endpointId}"
                        rows[key] = ContactEndpointRow(key, moduleOnline)
                    }
            }
        return rows.values.toList()
    }

    /** Directory exists only after verified HELLO populated endpoints. */
    fun hasEndpointDirectory(state: RemoteModuleState): Boolean =
        state.lastHelloMs > 0L && state.endpoints.isNotEmpty()
}
