package com.talkback.appprod.endpointtext

/**
 * Process-local conversation cache for Tactical Message presentation.
 *
 * Not transport state — clearing has zero protocol impact. In-memory only.
 */
class ConversationStore(
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
    fun append(record: EndpointTextRecord) {
        val key = record.endpointKey
        if (key.isBlank()) return
        val buffer = byEndpoint.getOrPut(key) { ArrayDeque() }
        buffer.addLast(record)
        while (buffer.size > capacityPerEndpoint) {
            buffer.removeFirst()
        }
        if (record.direction == EndpointTextDirection.INBOUND && key != openConversationKey) {
            unreadByEndpoint[key] = (unreadByEndpoint[key] ?: 0) + 1
        }
    }

    /** Newest first. */
    @Synchronized
    fun recent(endpointKey: String): List<EndpointTextRecord> {
        val buffer = byEndpoint[endpointKey] ?: return emptyList()
        return buffer.reversed()
    }

    @Synchronized
    fun summaries(): List<ConversationSummary> {
        return byEndpoint.map { (key, buffer) ->
            val last = buffer.last()
            ConversationSummary(
                endpointKey = key,
                lastMessage = last.text,
                lastTimestampMs = last.timestampMs,
                lastDirection = last.direction,
                unreadCount = unreadByEndpoint[key] ?: 0
            )
        }.sortedByDescending { it.lastTimestampMs }
    }

    @Synchronized
    fun totalUnread(): Int = unreadByEndpoint.values.sum()

    @Synchronized
    fun markRead(endpointKey: String) {
        if (endpointKey.isBlank()) return
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
        byEndpoint.clear()
        unreadByEndpoint.clear()
        openConversationKey = null
    }

    companion object {
        const val DEFAULT_CAPACITY_PER_ENDPOINT = 8
    }
}
