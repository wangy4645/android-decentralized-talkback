package com.talkback.core.channel

import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.ModuleId

/**
 * ADR-0002 R8: frozen Channel.members at Session create — not live-synced afterward.
 */
object ChannelMembershipSnapshot {

    fun capture(channelManager: ChannelManager, channelId: String): Set<ModuleId> =
        channelManager.members(channelId)

    fun moduleIds(snapshot: Set<ModuleId>): Set<String> =
        snapshot.map { it.value }.toSet()

    /**
     * Initial invite set: configured channel members when present, else explicit call targets.
     */
    fun resolveInitialInvites(
        configured: Set<ModuleId>,
        local: EndpointAddress,
        explicitRemotes: List<EndpointAddress>,
        resolveModule: (ModuleId) -> EndpointAddress?
    ): List<EndpointAddress> {
        if (configured.isEmpty()) {
            return (listOf(local) + explicitRemotes).distinctBy { it.key }
        }
        val invites = linkedSetOf<EndpointAddress>()
        invites.add(local)
        configured.forEach { moduleId ->
            if (moduleId == local.moduleId) return@forEach
            explicitRemotes.find { it.moduleId == moduleId }?.let {
                invites.add(it)
                return@forEach
            }
            resolveModule(moduleId)?.let { invites.add(it) }
        }
        return invites.toList()
    }
}
