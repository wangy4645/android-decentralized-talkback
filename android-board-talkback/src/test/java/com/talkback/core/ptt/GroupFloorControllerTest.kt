package com.talkback.core.ptt

import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.EndpointId
import com.talkback.core.model.EndpointPriority
import com.talkback.core.model.FloorSnapshotDigest
import com.talkback.core.model.ModuleId
import com.talkback.core.session.SessionType
import com.talkback.core.session.TalkbackSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
class GroupFloorControllerTest {
    private val m01 = ModuleId("M01")
    private val m02 = ModuleId("M02")
    private val m01e01 = EndpointAddress(m01, EndpointId("E01"))
    private val m02e01 = EndpointAddress(m02, EndpointId("E01"))

    @Test
    fun groupFloorAuthorityIsInitiator() {
        val session = TalkbackSession("s1", SessionType.GROUP, m01e01, "CH-01")
        session.initiatorModuleId = m01
        session.floorAuthorityModuleId = m01
        assertTrue(GroupFloorController.isFloorAuthority(session, "M01"))
        assertFalse(GroupFloorController.isFloorAuthority(session, "M02"))
    }

    @Test
    fun nonAuthorityIgnoresFloorRequest() {
        val session = TalkbackSession("s1", SessionType.GROUP, m02e01, "CH-01")
        session.initiatorModuleId = m01
        session.floorAuthorityModuleId = m01
        assertFalse(GroupFloorController.shouldProcessFloorRequest(session, "M02"))
    }

    @Test
    fun authorityProcessesFloorRequest() {
        val session = TalkbackSession("s1", SessionType.GROUP, m01e01, "CH-01")
        session.initiatorModuleId = m01
        session.floorAuthorityModuleId = m01
        assertTrue(GroupFloorController.shouldProcessFloorRequest(session, "M01"))
    }

    @Test
    fun unicastAndConferenceDoNotProcessFloorRequest() {
        val unicast = TalkbackSession("s1", SessionType.UNICAST, m01e01, null)
        assertFalse(GroupFloorController.shouldProcessFloorRequest(unicast, "M01"))
        val conference = TalkbackSession("s2", SessionType.CONFERENCE, m01e01, "CONF-01")
        assertFalse(GroupFloorController.shouldProcessFloorRequest(conference, "M01"))
    }

    @Test
    fun applyRemoteGrantSyncsOwner() {
        val session = TalkbackSession("s1", SessionType.GROUP, m02e01, "CH-01")
        session.groupMembers = listOf(m01e01, m02e01)
        GroupFloorController.applyRemoteGrant(session, m01e01, 3, 1, EndpointPriority.NORMAL)
        assertEquals(m01e01, session.floor.owner())
        assertEquals(3, session.floor.version())
    }

    @Test
    fun canPublishFloorSnapshot_onlyAuthority() {
        val session = TalkbackSession("s1", SessionType.GROUP, m01e01, "CH-01")
        session.initiatorModuleId = m01
        session.floorAuthorityModuleId = m01
        assertTrue(GroupFloorController.canPublishFloorSnapshot(session, "M01"))
        assertFalse(GroupFloorController.canPublishFloorSnapshot(session, "M02"))
    }

    @Test
    fun applyAuthorityFloorSnapshot_convergesEpoch() {
        val session = TalkbackSession("s1", SessionType.GROUP, m02e01, "CH-01")
        session.initiatorModuleId = m01
        session.floorAuthorityModuleId = m01
        session.groupMembers = listOf(m01e01, m02e01)
        val digest = FloorSnapshotDigest(epoch = 5, version = 6, ownerKey = "M01-E01")
        val result = GroupFloorController.applyAuthorityFloorSnapshot(session, "M01", digest)
        assertEquals(SnapshotResult.OWNER_CHANGED, result)
        assertEquals(5, session.floor.epoch())
        assertEquals(6, session.floor.version())
        assertEquals(m01e01, session.floor.owner())
    }

    @Test
    fun applyAuthorityFloorSnapshot_nonAuthorityIgnored() {
        val session = TalkbackSession("s1", SessionType.GROUP, m02e01, "CH-01")
        session.initiatorModuleId = m01
        session.floorAuthorityModuleId = m01
        session.groupMembers = listOf(m01e01, m02e01)
        val digest = FloorSnapshotDigest(epoch = 5, version = 6, ownerKey = "M01-E01")
        val result = GroupFloorController.applyAuthorityFloorSnapshot(session, "M02", digest)
        assertEquals(SnapshotResult.UNCHANGED, result)
        assertEquals(0, session.floor.epoch())
        assertNull(session.floor.owner())
    }

    @Test
    fun applyAuthorityFloorSnapshot_rosterMissDeferred() {
        val session = TalkbackSession("s1", SessionType.GROUP, m02e01, "CH-01")
        session.initiatorModuleId = m01
        session.floorAuthorityModuleId = m01
        session.groupMembers = listOf(m01e01, m02e01)
        val digest = FloorSnapshotDigest(epoch = 5, version = 6, ownerKey = "M03-E01")
        val result = GroupFloorController.applyAuthorityFloorSnapshot(session, "M01", digest)
        assertEquals(SnapshotResult.DEFERRED, result)
        assertEquals(0, session.floor.epoch())
        assertNull(session.floor.owner())
    }

    @Test
    fun applyAuthorityFloorSnapshot_ownerChangedInvokesCallback() {
        val session = TalkbackSession("s1", SessionType.GROUP, m02e01, "CH-01")
        session.initiatorModuleId = m01
        session.floorAuthorityModuleId = m01
        session.groupMembers = listOf(m01e01, m02e01)
        var callbackCount = 0
        val digest = FloorSnapshotDigest(epoch = 1, version = 2, ownerKey = "M01-E01")
        GroupFloorController.applyAuthorityFloorSnapshot(session, "M01", digest) {
            callbackCount++
        }
        assertEquals(1, callbackCount)
    }

    @Test
    fun applyAuthorityFloorSnapshot_updatedDoesNotInvokeOwnerChangedCallback() {
        val session = TalkbackSession("s1", SessionType.GROUP, m02e01, "CH-01")
        session.initiatorModuleId = m01
        session.floorAuthorityModuleId = m01
        session.groupMembers = listOf(m01e01, m02e01)
        session.floor.applySnapshot(m01e01, 2, 1, EndpointPriority.NORMAL)
        var callbackCount = 0
        val digest = FloorSnapshotDigest(epoch = 1, version = 4, ownerKey = "M01-E01")
        val result = GroupFloorController.applyAuthorityFloorSnapshot(session, "M01", digest) {
            callbackCount++
        }
        assertEquals(SnapshotResult.UPDATED, result)
        assertEquals(0, callbackCount)
    }
}
