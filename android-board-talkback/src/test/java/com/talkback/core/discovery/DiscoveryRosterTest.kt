package com.talkback.core.discovery

import com.talkback.core.model.ModuleId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoveryRosterTest {
    @Test
    fun mergeAndSnapshot() {
        val roster = DiscoveryRoster(peerTtlMs = 60_000L)
        roster.merge(DiscoveryPeerEntry(ModuleId("M02"), "10.0.0.2", 50001, 1))
        val list = roster.snapshot(ModuleId("M01"))
        assertEquals(1, list.size)
        assertEquals("M02", list.first().moduleId.value)
    }

    @Test
    fun removeExpired() {
        val roster = DiscoveryRoster(peerTtlMs = 1_000L)
        val now = System.currentTimeMillis()
        roster.merge(
            DiscoveryPeerEntry(ModuleId("M02"), "10.0.0.2", 50001),
            lastSeenMs = now - 5_000L
        )
        assertTrue(roster.removeExpired(now))
        assertEquals(0, roster.size())
    }

    @Test
    fun mergeKeepsHigherEndpointCount() {
        val roster = DiscoveryRoster(peerTtlMs = 60_000L)
        roster.merge(DiscoveryPeerEntry(ModuleId("M02"), "10.0.0.2", 50001, 1))
        roster.merge(DiscoveryPeerEntry(ModuleId("M02"), "10.0.0.2", 50001, 3))
        assertEquals(3, roster.snapshot().first().endpointCount)
    }

    @Test
    fun mergeIndirect_doesNotRefreshTtl() {
        val roster = DiscoveryRoster(peerTtlMs = 1_000L)
        val now = System.currentTimeMillis()
        roster.merge(
            DiscoveryPeerEntry(ModuleId("M02"), "10.0.0.2", 50001),
            lastSeenMs = now - 800L
        )
        roster.mergeIndirect(
            DiscoveryPeerEntry(ModuleId("M02"), "10.0.0.9", 50001),
            lastSeenMs = now
        )
        assertTrue(roster.removeExpired(now + 300L))
        assertEquals(0, roster.size())
    }

    @Test
    fun mergeIndirect_addsUnknownPeer() {
        val roster = DiscoveryRoster(peerTtlMs = 60_000L)
        roster.mergeIndirect(DiscoveryPeerEntry(ModuleId("M03"), "10.0.0.3", 50001))
        assertEquals(1, roster.size())
        assertEquals("M03", roster.snapshot().first().moduleId.value)
    }
}
