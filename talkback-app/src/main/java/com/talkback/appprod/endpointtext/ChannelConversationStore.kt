package com.talkback.appprod.endpointtext

/**
 * Channel message presentation cache (ADR-0041) with optional SQLite persistence.
 */
class ChannelConversationStore(
    private val persistence: MessagePersistence? = null,
    private val capacityPerChannel: Int = DEFAULT_CAPACITY
) {
    init {
        require(capacityPerChannel in 5..10) {
            "capacityPerChannel must be in 5..10, was $capacityPerChannel"
        }
    }

    private val byChannel = LinkedHashMap<String, ArrayDeque<ChannelTextRecord>>()
    private val unreadByChannel = HashMap<String, Int>()
    @Volatile
    private var openChannelId: String? = null

    @Synchronized
    fun append(record: ChannelTextRecord): Boolean {
        val key = record.channelId
        if (key.isBlank()) return false
        val markRead = key == openChannelId
        if (persistence != null) {
            return persistence.insertChannel(record, markRead)
        }
        val buffer = byChannel.getOrPut(key) { ArrayDeque() }
        buffer.addLast(record)
        while (buffer.size > capacityPerChannel) {
            buffer.removeFirst()
        }
        if (record.direction == EndpointTextDirection.INBOUND && !markRead) {
            unreadByChannel[key] = (unreadByChannel[key] ?: 0) + 1
        }
        return true
    }

    @Synchronized
    fun recent(channelId: String): List<ChannelTextRecord> {
        persistence?.let { return it.recentChannel(channelId) }
        val buffer = byChannel[channelId] ?: return emptyList()
        return buffer.toList()
    }

    @Synchronized
    fun summary(channelId: String): ChannelConversationSummary? {
        persistence?.let { return it.channelSummary(channelId) }
        val buffer = byChannel[channelId] ?: return null
        if (buffer.isEmpty()) return null
        val last = buffer.last()
        return ChannelConversationSummary(
            channelId = channelId,
            lastMessage = last.previewText(),
            lastTimestampMs = last.timestampMs,
            lastSenderLabel = last.senderLabel,
            unreadCount = unreadByChannel[channelId] ?: 0
        )
    }

    @Synchronized
    fun unread(channelId: String): Int =
        persistence?.channelUnread(channelId) ?: (unreadByChannel[channelId] ?: 0)

    @Synchronized
    fun totalUnread(): Int =
        persistence?.totalChannelUnread() ?: unreadByChannel.values.sum()

    @Synchronized
    fun setOpenChannel(channelId: String?) {
        openChannelId = channelId?.takeIf { it.isNotBlank() }
        if (openChannelId != null) {
            persistence?.markChannelRead(openChannelId!!)
            unreadByChannel.remove(openChannelId)
        }
    }

    @Synchronized
    fun isOpen(channelId: String): Boolean = openChannelId == channelId

    @Synchronized
    fun clear() {
        persistence?.clearAll()
        byChannel.clear()
        unreadByChannel.clear()
        openChannelId = null
    }

    companion object {
        const val DEFAULT_CAPACITY = 8
    }
}

private fun ChannelTextRecord.previewText(): String {
    if (text.isNotBlank()) return text
    return when (attachment?.kind) {
        com.talkback.core.endpointattachment.AttachmentKind.IMAGE -> "[Image]"
        com.talkback.core.endpointattachment.AttachmentKind.VOICE -> "[Voice]"
        com.talkback.core.endpointattachment.AttachmentKind.FILE -> "[File]"
        com.talkback.core.endpointattachment.AttachmentKind.LOCATION -> "[Location]"
        null -> text
    }
}

data class ChannelTextRecord(
    val channelId: String,
    val text: String,
    val timestampMs: Long,
    val direction: EndpointTextDirection,
    val senderKey: String,
    val senderLabel: String,
    val attachment: MessageAttachmentInfo? = null
)

data class ChannelConversationSummary(
    val channelId: String,
    val lastMessage: String,
    val lastTimestampMs: Long,
    val lastSenderLabel: String,
    val unreadCount: Int
)
