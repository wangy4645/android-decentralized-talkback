package com.talkback.core.session

import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.EndpointId
import com.talkback.core.model.ModuleId
import org.junit.Assert.assertEquals
import org.junit.Test

class ConferenceHostEndpointResolverTest {
    private val m01 = ModuleId("M01")
    private val m02 = ModuleId("M02")
    private val hostEndpoint = EndpointAddress(m01, EndpointId("E01"))
    private val remoteEndpoint = EndpointAddress(m01, EndpointId("E02"))

    @Test
    fun resolve_prefersRosterOverRemoteAndFallback() {
        val roster = listOf(hostEndpoint, EndpointAddress(m02, EndpointId("E01")))
        val resolved = ConferenceHostEndpointResolver.resolve(
            roster = roster,
            remote = remoteEndpoint,
            hostModuleId = "M01",
            fallbackEndpointId = EndpointId("E99"),
        )
        assertEquals(hostEndpoint, resolved)
    }

    @Test
    fun resolve_fallsBackToRemoteWhenRosterEmpty() {
        val resolved = ConferenceHostEndpointResolver.resolve(
            roster = emptyList(),
            remote = remoteEndpoint,
            hostModuleId = "M01",
            fallbackEndpointId = EndpointId("E99"),
        )
        assertEquals(remoteEndpoint, resolved)
    }

    @Test
    fun resolve_constructsEndpointWhenRosterAndRemoteMiss() {
        val resolved = ConferenceHostEndpointResolver.resolve(
            roster = emptyList(),
            remote = EndpointAddress(m02, EndpointId("E01")),
            hostModuleId = "M01",
            fallbackEndpointId = EndpointId("E99"),
        )
        assertEquals(EndpointAddress(m01, EndpointId("E99")), resolved)
    }
}
