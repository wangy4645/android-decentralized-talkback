package com.talkback.core.channel

import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.EndpointId
import com.talkback.core.model.ModuleId
import org.junit.Assert.assertEquals
import org.junit.Test

class ChannelMembershipSnapshotTest {
    private val m01 = ModuleId("M01")
    private val m02 = ModuleId("M02")
    private val m03 = ModuleId("M03")
    private val local = EndpointAddress(m01, EndpointId("E01"))
    private val e02 = EndpointAddress(m02, EndpointId("E01"))
    private val e03 = EndpointAddress(m03, EndpointId("E01"))

    @Test
    fun resolveInitialInvites_usesExplicitWhenChannelEmpty() {
        val invites = ChannelMembershipSnapshot.resolveInitialInvites(
            configured = emptySet(),
            local = local,
            explicitRemotes = listOf(e02),
            resolveModule = { null }
        )
        assertEquals(listOf(local.key, e02.key), invites.map { it.key })
    }

    @Test
    fun resolveInitialInvites_expandsConfiguredChannel() {
        val manager = ChannelManager()
        manager.replaceMembers("CH-1", setOf(m01, m02, m03))
        val configured = ChannelMembershipSnapshot.capture(manager, "CH-1")
        val invites = ChannelMembershipSnapshot.resolveInitialInvites(
            configured = configured,
            local = local,
            explicitRemotes = listOf(e02),
            resolveModule = { mid ->
                when (mid) {
                    m02 -> e02
                    m03 -> e03
                    else -> null
                }
            }
        )
        assertEquals(setOf(local.key, e02.key, e03.key), invites.map { it.key }.toSet())
    }
}
