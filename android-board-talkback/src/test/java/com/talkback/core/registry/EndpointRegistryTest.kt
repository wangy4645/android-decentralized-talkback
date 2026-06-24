package com.talkback.core.registry

import com.talkback.core.model.EndpointId
import com.talkback.core.model.EndpointPriority
import com.talkback.core.model.ModuleId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EndpointRegistryTest {
    @Test
    fun supportsMultipleLocalEndpoints() {
        val registry = EndpointRegistry(ModuleId("M01"))
        registry.upsertLocalEndpoint(EndpointId("E01"), "Driver", true)
        registry.upsertLocalEndpoint(EndpointId("E02"), "Copilot", true)

        assertEquals(2, registry.allOnline().size)
    }

    @Test
    fun upsertLocalEndpoint_persistsPriority() {
        val registry = EndpointRegistry(ModuleId("M01"))
        registry.upsertLocalEndpoint(EndpointId("E01"), "Lead", true, EndpointPriority.EMERGENCY)
        assertEquals(EndpointPriority.EMERGENCY, registry.resolve(
            com.talkback.core.model.EndpointAddress(ModuleId("M01"), EndpointId("E01"))
        )?.priority)
    }

    @Test
    fun markOfflineChangesPresence() {
        val registry = EndpointRegistry(ModuleId("M01"))
        val descriptor = registry.upsertLocalEndpoint(EndpointId("E03"), "Backup", true)
        assertTrue(descriptor.online)

        registry.markOffline(EndpointId("E03"))
        val resolved = registry.resolve(descriptor.address)
        assertFalse(resolved?.online ?: true)
    }
}
