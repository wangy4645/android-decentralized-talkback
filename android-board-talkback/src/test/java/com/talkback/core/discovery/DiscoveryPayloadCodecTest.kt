package com.talkback.core.discovery

import com.talkback.core.model.ModuleId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DiscoveryPayloadCodecTest {
    @Test
    fun probeRoundTrip() {
        val self = DiscoveryPeerEntry(ModuleId("M01"), "10.0.0.1", 50000, 1)
        val encoded = DiscoveryPayload.encodeProbe(self)
        val decoded = DiscoveryPayload.decodeProbe(encoded)
        assertNotNull(decoded)
        assertEquals("M01", decoded!!.moduleId.value)
        assertEquals("10.0.0.1", decoded.host)
        assertEquals(50000, decoded.signalingPort)
    }

    @Test
    fun announceRoundTrip() {
        val self = DiscoveryPeerEntry(ModuleId("M01"), "10.0.0.1", 50000, 1)
        val peers = listOf(
            DiscoveryPeerEntry(ModuleId("M02"), "10.0.0.2", 50001, 2)
        )
        val encoded = DiscoveryPayload.encodeAnnounce(self, peers)
        val decoded = DiscoveryPayload.decodeAnnounce(encoded)
        assertNotNull(decoded)
        assertEquals("M01", decoded!!.first.moduleId.value)
        assertEquals(1, decoded.second.size)
        assertEquals("M02", decoded.second.first().moduleId.value)
    }

    @Test
    fun decodeBlankReturnsNull() {
        assertNull(DiscoveryPayload.decodeProbe(""))
        assertNull(DiscoveryPayload.decodeAnnounce(""))
    }
}
