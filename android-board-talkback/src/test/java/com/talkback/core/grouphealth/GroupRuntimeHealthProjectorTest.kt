package com.talkback.core.grouphealth

import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.EndpointId
import com.talkback.core.model.ModuleId
import com.talkback.core.session.ChannelReadiness
import com.talkback.core.session.GroupMembershipSupport
import com.talkback.core.session.SessionType
import com.talkback.core.session.TalkbackSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupRuntimeHealthProjectorTest {

    private val m01 = ModuleId("M01")
    private val m02 = ModuleId("M02")
    private val m03 = ModuleId("M03")

    private fun groupSession(
        local: ModuleId,
        vararg memberIds: String,
        accepted: Boolean = true
    ): TalkbackSession {
        val s = TalkbackSession(
            id = "s1",
            type = SessionType.GROUP,
            local = EndpointAddress(local, EndpointId("E01")),
            channelId = "CH-01"
        )
        s.groupMembers = memberIds.map { EndpointAddress(ModuleId(it), EndpointId("E01")) }
        s.memberModules.addAll(memberIds.map { ModuleId(it) })
        GroupMembershipSupport.syncMembershipFromGroupMembers(s)
        s.initiatorModuleId = m01
        s.accepted = accepted
        return s
    }

    private fun input(
        local: ModuleId,
        session: TalkbackSession?,
        connected: Set<String> = emptySet(),
        digestAligned: Boolean = true,
        dialable: Int = 0
    ) = GroupRuntimeHealthInput(
        localModuleId = local,
        session = session,
        dialablePeerCount = dialable,
        membershipDigestAlignedWithAuthority = digestAligned,
        peerMediaConnected = connected,
        iceStateForModule = { "CONNECTED" }
    )

    @Test
    fun noSession_discovering() {
        val health = GroupRuntimeHealthProjector.project(input(m02, null))
        assertEquals(GroupTopologyReadiness.DISCOVERING, health.groupTopologyReadiness)
        assertEquals(ChannelReadiness.DISCOVERING, health.mappedChannelReadiness)
    }

    @Test
    fun noSession_withDialable_mapsConnecting() {
        val health = GroupRuntimeHealthProjector.project(input(m02, null, dialable = 2))
        assertEquals(ChannelReadiness.CONNECTING, health.mappedChannelReadiness)
    }

    @Test
    fun notAccepted_membershipPending() {
        val session = groupSession(m02, "M01", "M02", "M03", accepted = false)
        val health = GroupRuntimeHealthProjector.project(input(m02, session, connected = setOf("M01", "M03")))
        assertEquals(GroupTopologyReadiness.MEMBERSHIP_PENDING, health.groupTopologyReadiness)
        assertFalse(health.membershipReconciled)
        assertEquals(ChannelReadiness.DIRECTORY_SYNC, health.mappedChannelReadiness)
    }

    @Test
    fun digestNotAligned_membershipPending() {
        val session = groupSession(m02, "M01", "M02", "M03")
        val health = GroupRuntimeHealthProjector.project(
            input(m02, session, digestAligned = false, connected = setOf("M01", "M03"))
        )
        assertEquals(GroupTopologyReadiness.MEMBERSHIP_PENDING, health.groupTopologyReadiness)
        assertFalse(health.membershipDigestAligned)
    }

    @Test
    fun suspectPeer_blocksOperational() {
        val session = groupSession(m02, "M01", "M02", "M03")
        GroupMembershipSupport.markSuspect(session, "M03", System.currentTimeMillis())
        val health = GroupRuntimeHealthProjector.project(
            input(m02, session, connected = setOf("M01", "M03"))
        )
        assertEquals(setOf("M03"), health.suspectPeers)
        assertEquals(GroupTopologyReadiness.MEMBERSHIP_PENDING, health.groupTopologyReadiness)
    }

    @Test
    fun partialTransmit_building() {
        val session = groupSession(m02, "M01", "M02", "M03")
        val health = GroupRuntimeHealthProjector.project(input(m02, session, connected = setOf("M01")))
        assertEquals(GroupTopologyReadiness.BUILDING, health.groupTopologyReadiness)
        assertEquals(setOf("M01", "M03"), health.transmitRequiredPeers)
        assertEquals(setOf("M03"), health.transmitMissingPeers)
        assertTrue(health.meshMissingSignal.isNotEmpty())
    }

    @Test
    fun allTransmitConnected_operational_despiteMeshGap() {
        val session = groupSession(m02, "M01", "M02", "M03")
        val health = GroupRuntimeHealthProjector.project(
            input(m02, session, connected = setOf("M01", "M03"))
        )
        assertEquals(GroupTopologyReadiness.OPERATIONAL, health.groupTopologyReadiness)
        assertEquals(ChannelReadiness.READY, health.mappedChannelReadiness)
        assertTrue(health.transmitMissingPeers.isEmpty())
        assertTrue(health.meshMissingSignal.isNotEmpty())
    }

    @Test
    fun meshDesiredLinks_fromInviteAndJoin() {
        val session = groupSession(m01, "M01", "M02", "M03")
        val health = GroupRuntimeHealthProjector.project(
            input(m01, session, connected = setOf("M02", "M03"))
        )
        assertEquals(
            setOf(MeshLink("M01", "M02"), MeshLink("M01", "M03")),
            health.meshDesiredLinks
        )
    }

    @Test
    fun floorFields_diagnosticOnly() {
        val session = groupSession(m02, "M01", "M02", "M03")
        session.floor.applyGrant(session.local, 3L, 2L)
        val blocked = GroupRuntimeHealthProjector.project(
            input(m02, session, connected = setOf("M01"))
        )
        val ready = GroupRuntimeHealthProjector.project(
            input(m02, session, connected = setOf("M01", "M03"))
        )
        assertEquals(session.local.key, blocked.floorOwnerKey)
        assertEquals(2L, blocked.floorEpoch)
        assertEquals(3L, blocked.floorVersion)
        assertEquals(GroupTopologyReadiness.BUILDING, blocked.groupTopologyReadiness)
        assertEquals(GroupTopologyReadiness.OPERATIONAL, ready.groupTopologyReadiness)
    }

    @Test
    fun schemaVersion_isOne() {
        val session = groupSession(m02, "M01", "M02", "M03")
        val health = GroupRuntimeHealthProjector.project(input(m02, session))
        assertEquals(1, health.schemaVersion)
    }
}
