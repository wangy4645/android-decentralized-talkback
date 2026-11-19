package com.talkback.appprod.endpointtext

/**
 * Direct message presentation + unread tracking.
 * When [persistence] is set, messages survive process restarts (SQLite).
 */
class ConversationStore(
    private val persistence: MessagePersistence? = null,
    private val capacityPerEndpoint: Int = DEFAULT_CAPACITY_PER_ENDPOINT
) {
    init {
        require(capacityPerEndpoint in 5..10) {
            "capacityPerEndpoint must be in 5..10, was $capacityPerEndpoint"
        }
    }

    private val byEndpoint = LinkedHashMap<String, ArrayDeque<EndpointTextRecord>>()
    private val unreadByEndpoint = HashMap<String, Int>()
    @Volatile
    private var openConversationKey: String? = null

    @Synchronized
    fun append(record: EndpointTextRecord): Boolean {
        val key = record.endpointKey
        if (key.isBlank()) return false
        val markRead = key == openConversationKey
        if (persistence != null) {
            return persistence.insertDirect(record, markRead)
        }
        val buffer = byEndpoint.getOrPut(key) { ArrayDeque() }
        buffer.addLast(record)
        while (buffer.size > capacityPerEndpoint) {
            buffer.removeFirst()
        }
        if (record.direction == EndpointTextDirection.INBOUND && !markRead) {
            unreadByEndpoint[key] = (unreadByEndpoint[key] ?: 0) + 1
        }
        return true
    }

    @Synchronized
    fun recent(endpointKey: String): List<EndpointTextRecord> {
        persistence?.let { return it.recentDirect(endpointKey) }
        val buffer = byEndpoint[endpointKey] ?: return emptyList()
        return buffer.toList()
    }

    @Synchronized
    fun summaries(): List<ConversationSummary> {
        persistence?.let { return it.directSummaries() }
        return byEndpoint.map { (key, buffer) ->
            val last = buffer.last()
            ConversationSummary(
                endpointKey = key,
                lastMessage = last.previewText(),
                lastTimestampMs = last.timestampMs,
                lastDirection = last.direction,
                unreadCount = unreadByEndpoint[key] ?: 0
            )
        }.sortedByDescending { it.lastTimestampMs }
    }

    @Synchronized
    fun totalUnread(): Int =
        persistence?.totalDirectUnread() ?: unreadByEndpoint.values.sum()

    @Synchronized
    fun markRead(endpointKey: String) {
        if (endpointKey.isBlank()) return
        persistence?.markDirectRead(endpointKey)
        unreadByEndpoint.remove(endpointKey)
    }

    @Synchronized
    fun openConversationKey(): String? = openConversationKey

    @Synchronized
    fun setOpenConversation(endpointKey: String?) {
        openConversationKey = endpointKey?.takeIf { it.isNotBlank() }
        if (openConversationKey != null) {
            markRead(openConversationKey!!)
        }
    }

    @Synchronized
    fun clear() {
        persistence?.clearAll()
        byEndpoint.clear()
        unreadByEndpoint.clear()
        openConversationKey = null
    }

    companion object {
        const val DEFAULT_CAPACITY_PER_ENDPOINT = 8
    }
}

private fun EndpointTextRecord.previewText(): String {
    if (text.isNotBlank()) return text
    return when (attachment?.kind) {
        com.talkback.core.endpointattachment.AttachmentKind.IMAGE -> "[Image]"
        com.talkback.core.endpointattachment.AttachmentKind.VOICE -> "[Voice]"
        com.talkback.core.endpointattachment.AttachmentKind.FILE -> "[File]"
        com.talkback.core.endpointattachment.AttachmentKind.LOCATION -> "[Location]"
        null -> text
    }
}
