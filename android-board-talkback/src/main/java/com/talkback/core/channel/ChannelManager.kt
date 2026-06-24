package com.talkback.core.channel

import com.talkback.core.model.ModuleId
import java.util.concurrent.ConcurrentHashMap

class ChannelManager {
    private val channels = ConcurrentHashMap<String, Channel>()

    fun getOrCreate(channelId: String, displayName: String = channelId): Channel {
        return channels.getOrPut(channelId) { Channel(channelId, displayName) }
    }

    fun join(channelId: String, moduleId: ModuleId) {
        getOrCreate(channelId).memberModuleIds.add(moduleId)
    }

    fun leave(channelId: String, moduleId: ModuleId) {
        channels[channelId]?.memberModuleIds?.remove(moduleId)
    }

    fun members(channelId: String): Set<ModuleId> =
        channels[channelId]?.memberModuleIds?.toSet() ?: emptySet()

    fun all(): List<Channel> = channels.values.sortedBy { it.channelId }
}
