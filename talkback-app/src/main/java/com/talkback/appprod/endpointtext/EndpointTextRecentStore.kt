package com.talkback.appprod.endpointtext

/**
 * Process-local ring buffer of recent EndpointText for operator re-read after Toast.
 *
 * Presentation cache only — does not participate in dedup, recovery, resend, or delivery.
 * In-memory; process exit clears everything.
 */
class EndpointTextRecentStore(
    private val capacityPerEndpoint: Int = DEFAULT_CAPACITY_PER_ENDPOINT
) {
    init {
        require(capacityPerEndpoint in 5..10) {
            "capacityPerEndpoint must be in 5..10, was $capacityPerEndpoint"
        }
    }

    private val byEndpoint = LinkedHashMap<String, ArrayDeque<EndpointTextRecord>>()

    @Synchronized
    fun append(record: EndpointTextRecord) {
        val key = record.endpointKey
        if (key.isBlank()) return
        val buffer = byEndpoint.getOrPut(key) { ArrayDeque() }
        buffer.addLast(record)
        while (buffer.size > capacityPerEndpoint) {
            buffer.removeFirst()
        }
    }

    /** Newest first. */
    @Synchronized
    fun recent(endpointKey: String): List<EndpointTextRecord> {
        val buffer = byEndpoint[endpointKey] ?: return emptyList()
        return buffer.reversed()
    }

    @Synchronized
    fun clear() {
        byEndpoint.clear()
    }

    companion object {
        const val DEFAULT_CAPACITY_PER_ENDPOINT = 8
    }
}
