package com.talkback.core.transport

import com.talkback.core.model.ModuleId
import com.talkback.core.signaling.PeerTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DelegatingTransportRegistryTest {

    @Test
    fun resolve_delegatesToLegacySignalPeerLookup() {
        val peer = PeerTarget("10.0.0.2", 9_001)
        val registry = DelegatingTransportRegistry { moduleId ->
            if (moduleId.value == "M01") peer else null
        }
        val binding = registry.resolve(ModuleId("M01"))
        assertEquals(ModuleId("M01"), binding?.moduleId)
        assertEquals(peer, binding?.peer)
        assertEquals(TransportSource.LEGACY_SIGNAL_PEER, binding?.source)
        assertNull(registry.resolve(ModuleId("M02")))
    }

    @Test
    fun bindingEpoch_returnsZeroInStub() {
        val registry = DelegatingTransportRegistry { PeerTarget("127.0.0.1", 1) }
        assertEquals(0L, registry.bindingEpoch(ModuleId("M01")))
    }

    @Test
    fun invalidate_isNoOpInStub() {
        var lookups = 0
        val peer = PeerTarget("10.0.0.3", 9_002)
        val registry = DelegatingTransportRegistry {
            lookups++
            peer
        }
        registry.invalidate(ModuleId("M01"))
        assertEquals(peer, registry.resolve(ModuleId("M01"))?.peer)
        assertEquals(1, lookups)
    }
}
