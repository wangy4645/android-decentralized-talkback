package com.talkback.appprod.endpointtext

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationStoreTest {

    @Test
    fun append_thenRecent_returnsNewestFirst() {
        val store = ConversationStore(capacityPerEndpoint = 8)
        store.append(record("peer-a", "first", 1_000L, EndpointTextDirection.INBOUND))
        store.append(record("peer-a", "second", 2_000L, EndpointTextDirection.OUTBOUND))

        val recent = store.recent("peer-a")
        assertEquals(listOf("second", "first"), recent.map { it.text })
        assertEquals(EndpointTextDirection.OUTBOUND, recent[0].direction)
        assertEquals(EndpointTextDirection.INBOUND, recent[1].direction)
    }

    @Test
    fun recent_isScopedPerEndpointKey() {
        val store = ConversationStore(capacityPerEndpoint = 8)
        store.append(record("peer-a", "a1", 1L, EndpointTextDirection.INBOUND))
        store.append(record("peer-b", "b1", 2L, EndpointTextDirection.INBOUND))

        assertEquals(listOf("a1"), store.recent("peer-a").map { it.text })
        assertEquals(listOf("b1"), store.recent("peer-b").map { it.text })
        assertTrue(store.recent("peer-c").isEmpty())
    }

    @Test
    fun exceedsCapacity_evictsOldest() {
        val store = ConversationStore(capacityPerEndpoint = 8)
        repeat(9) { i ->
            store.append(
                record("peer-a", "msg-$i", (i + 1) * 1_000L, EndpointTextDirection.INBOUND)
            )
        }

        val recent = store.recent("peer-a")
        assertEquals(8, recent.size)
        assertEquals("msg-8", recent.first().text)
        assertEquals("msg-1", recent.last().text)
        assertTrue(recent.none { it.text == "msg-0" })
    }

    @Test
    fun clear_removesAllEndpoints() {
        val store = ConversationStore(capacityPerEndpoint = 8)
        store.append(record("peer-a", "a", 1L, EndpointTextDirection.INBOUND))
        store.append(record("peer-b", "b", 2L, EndpointTextDirection.OUTBOUND))
        store.clear()
        assertTrue(store.recent("peer-a").isEmpty())
        assertTrue(store.recent("peer-b").isEmpty())
        assertTrue(store.summaries().isEmpty())
    }

    @Test
    fun inbound_incrementsUnread_untilMarkRead() {
        val store = ConversationStore(capacityPerEndpoint = 8)
        store.append(record("peer-a", "hello", 1L, EndpointTextDirection.INBOUND))
        assertEquals(1, store.summaries().single().unreadCount)
        store.markRead("peer-a")
        assertEquals(0, store.summaries().single().unreadCount)
    }

    @Test
    fun openConversation_suppressesUnreadForInbound() {
        val store = ConversationStore(capacityPerEndpoint = 8)
        store.setOpenConversation("peer-a")
        store.append(record("peer-a", "live", 1L, EndpointTextDirection.INBOUND))
        assertEquals(0, store.summaries().single().unreadCount)
    }

    @Test
    fun summaries_sortedByLastTimestampDescending() {
        val store = ConversationStore(capacityPerEndpoint = 8)
        store.append(record("peer-a", "a", 100L, EndpointTextDirection.INBOUND))
        store.append(record("peer-b", "b", 200L, EndpointTextDirection.INBOUND))
        assertEquals(listOf("peer-b", "peer-a"), store.summaries().map { it.endpointKey })
    }

    private fun record(
        endpointKey: String,
        text: String,
        timestampMs: Long,
        direction: EndpointTextDirection
    ) = EndpointTextRecord(
        endpointKey = endpointKey,
        text = text,
        timestampMs = timestampMs,
        direction = direction
    )
}
