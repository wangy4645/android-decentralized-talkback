package com.talkback.core.channeltext

import com.talkback.core.model.EndpointAddress

data class ChannelTextEvent(
    val messageId: String,
    val channelId: String,
    val from: EndpointAddress,
    val text: String,
    val priority: String = "INLINE"
)
