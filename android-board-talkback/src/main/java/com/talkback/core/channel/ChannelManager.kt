package com.talkback.core.channel

import com.talkback.core.model.ModuleId
import java.util.concurrent.ConcurrentHashMap

class ChannelManager {
    private val channels = ConcurrentHashMap<String, Channel>()

    fun getOrCreate(channelId: String, displayName: String = channelId): Channel {
        return channels.getOrPut(channelId) { Channel(channelId, displayName) }
    }

    /** Explicit channel configuration only (ADR-0002 R7); not driven by Session evict/rejoin. */
    fun join(channelId: String, moduleId: ModuleId) {
        getOrCreate(channelId).memberModuleIds.add(moduleId)
    }

    fun replaceMembers(channelId: String, moduleIds: Collection<ModuleId>) {
        val channel = getOrCreate(channelId)
        channel.memberModuleIds.clear()
        channel.memberModuleIds.addAll(moduleIds)
    }

    fun leave(channelId: String, moduleId: ModuleId) {
        channels[channelId]?.memberModuleIds?.remove(moduleId)
    }

    fun members(channelId: String): Set<ModuleId> =
        channels[channelId]?.memberModuleIds?.toSet() ?: emptySet()

    fun all(): List<Channel> = channels.values.sortedBy { it.channelId }
}
