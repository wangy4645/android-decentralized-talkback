package com.talkback.core.channel

import com.talkback.core.model.ModuleId

data class Channel(
    val channelId: String,
    val displayName: String,
    val memberModuleIds: MutableSet<ModuleId> = linkedSetOf()
)
