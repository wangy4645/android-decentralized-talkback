package com.talkback.core.sync

import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.EndpointId
import com.talkback.core.model.EndpointPriority
import com.talkback.core.model.ModuleId
import com.talkback.core.model.RemoteEndpointInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StateSyncManagerTest {
    @Test
    fun registeredPriority_returnsHelloDirectoryValue() {
        val sync = StateSyncManager()
        sync.updateEndpoints(
            "M02",
            listOf(
                RemoteEndpointInfo(
                    endpointId = "E01",
                    displayName = "Ops",
                    online = true,
                    priority = EndpointPriority.NORMAL
                )
            )
        )
        val endpoint = EndpointAddress(ModuleId("M02"), EndpointId("E01"))
        assertEquals(EndpointPriority.NORMAL, sync.registeredPriority(endpoint))
    }

    @Test
    fun registeredPriority_missingEndpoint_returnsNull() {
        val sync = StateSyncManager()
        val endpoint = EndpointAddress(ModuleId("M99"), EndpointId("E01"))
        assertNull(sync.registeredPriority(endpoint))
    }

    @Test
    fun updatePresence_doesNotImplyHelloLiveness() {
        val sync = StateSyncManager()
        val now = System.currentTimeMillis()
        sync.updatePresence(
            listOf(
                com.talkback.core.discovery.ModulePresence(
                    moduleId = ModuleId("M02"),
                    host = "10.0.0.2",
                    port = 50001,
                    endpointCount = 1,
                    lastSeenMs = now
                )
            )
        )
        val state = sync.get("M02")!!
        assertEquals(0L, state.lastHelloMs)
        sync.pruneStaleModules(15_000L, now + 20_000L)
        assertNull(sync.get("M02"))
    }
}
