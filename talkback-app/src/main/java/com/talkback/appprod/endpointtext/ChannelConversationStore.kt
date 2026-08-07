package com.talkback.appprod.endpointtext

/**
 * Process-local Channel Text presentation cache (ADR-0041).
 * Keyed by channelId — not by multi-select recipient lists.
 */
class ChannelConversationStore(
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
    fun append(record: ChannelTextRecord) {
        val key = record.channelId
        if (key.isBlank()) return
        val buffer = byChannel.getOrPut(key) { ArrayDeque() }
        buffer.addLast(record)
        while (buffer.size > capacityPerChannel) {
            buffer.removeFirst()
        }
        if (record.direction == EndpointTextDirection.INBOUND && key != openChannelId) {
            unreadByChannel[key] = (unreadByChannel[key] ?: 0) + 1
        }
    }

    @Synchronized
    fun recent(channelId: String): List<ChannelTextRecord> {
        val buffer = byChannel[channelId] ?: return emptyList()
        return buffer.toList()
    }

    @Synchronized
    fun summary(channelId: String): ChannelConversationSummary? {
        val buffer = byChannel[channelId] ?: return null
        if (buffer.isEmpty()) return null
        val last = buffer.last()
        return ChannelConversationSummary(
            channelId = channelId,
            lastMessage = last.text,
            lastTimestampMs = last.timestampMs,
            lastSenderLabel = last.senderLabel,
            unreadCount = unreadByChannel[channelId] ?: 0
        )
    }

    @Synchronized
    fun unread(channelId: String): Int = unreadByChannel[channelId] ?: 0

    @Synchronized
    fun totalUnread(): Int = unreadByChannel.values.sum()

    @Synchronized
    fun setOpenChannel(channelId: String?) {
        openChannelId = channelId?.takeIf { it.isNotBlank() }
        if (openChannelId != null) {
            unreadByChannel.remove(openChannelId)
        }
    }

    @Synchronized
    fun isOpen(channelId: String): Boolean = openChannelId == channelId

    @Synchronized
    fun clear() {
        byChannel.clear()
        unreadByChannel.clear()
        openChannelId = null
    }

    companion object {
        const val DEFAULT_CAPACITY = 8
    }
}

data class ChannelTextRecord(
    val channelId: String,
    val text: String,
    val timestampMs: Long,
    val direction: EndpointTextDirection,
    val senderKey: String,
    val senderLabel: String
)

data class ChannelConversationSummary(
    val channelId: String,
    val lastMessage: String,
    val lastTimestampMs: Long,
    val lastSenderLabel: String,
    val unreadCount: Int
)
