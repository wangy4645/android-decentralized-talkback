package com.talkback.core.session

import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.EndpointId
import com.talkback.core.model.ModuleId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupMembershipSupportTest {

    private fun session(vararg memberIds: String): TalkbackSession {
        val local = ModuleId("M02")
        val s = TalkbackSession(
            id = "s1",
            type = SessionType.GROUP,
            local = EndpointAddress(local, EndpointId("E01")),
            channelId = "CH-01"
        )
        s.groupMembers = memberIds.map { EndpointAddress(ModuleId(it), EndpointId("E01")) }
        s.memberModules.addAll(memberIds.map { ModuleId(it) })
        GroupMembershipSupport.syncMembershipFromGroupMembers(s)
        return s
    }

    @Test
    fun suspectWithIceAlive_isActiveMember() {
        val s = session("M01", "M02", "M03")
        GroupMembershipSupport.markSuspect(s, "M01", System.currentTimeMillis())
        assertTrue(GroupMembershipSupport.isActiveMember(s, "M01", "CONNECTED"))
    }

    @Test
    fun onlineWithIceDead_notActiveMember() {
        val s = session("M01", "M02", "M03")
        assertFalse(GroupMembershipSupport.isActiveMember(s, "M01", "DISCONNECTED"))
    }

    @Test
    fun suspectWithIceDead_notActiveMember() {
        val s = session("M01", "M02", "M03")
        GroupMembershipSupport.markSuspect(s, "M01", System.currentTimeMillis())
        assertFalse(GroupMembershipSupport.isActiveMember(s, "M01", "DISCONNECTED"))
    }

    @Test
    fun evicted_notInActiveOrCanonical() {
        val s = session("M01", "M02", "M03")
        GroupMembershipSupport.markEvicted(s, "M01")
        s.groupMembers = s.groupMembers.filter { it.moduleId.value != "M01" }
        assertFalse(GroupMembershipSupport.canonicalMemberModuleIds(s).any { it.value == "M01" })
        val active = GroupMembershipSupport.activeMemberModuleIds(s) { "CONNECTED" }
        assertFalse(active.any { it.value == "M01" })
    }

    @Test
    fun bumpRosterEpoch_incrementsMonotonically() {
        val s = session("M02", "M03")
        assertEquals(1L, s.rosterEpoch)
        assertEquals(2L, GroupMembershipSupport.bumpRosterEpoch(s))
        assertEquals(3L, GroupMembershipSupport.bumpRosterEpoch(s))
    }

    @Test
    fun memberHash_stableForSameRoster() {
        val h1 = GroupMembershipSupport.memberHash("CH-01", 1L, listOf("M01", "M02", "M03"))
        val h2 = GroupMembershipSupport.memberHash("CH-01", 1L, listOf("M03", "M02", "M01"))
        assertEquals(h1, h2)
    }

    @Test
    fun memberHash_changesWhenRosterEpochChanges() {
        val h1 = GroupMembershipSupport.memberHash("CH-01", 1L, listOf("M01", "M02"))
        val h2 = GroupMembershipSupport.memberHash("CH-01", 2L, listOf("M01", "M02"))
        assertFalse(h1 == h2)
    }

    @Test
    fun applyMembershipSnapshot_advancesEpochWithoutMediaSideEffects() {
        val s = session("M01", "M02", "M03")
        assertEquals(1L, s.rosterEpoch)
        val updated = listOf(
            EndpointAddress(ModuleId("M01"), EndpointId("E01")),
            EndpointAddress(ModuleId("M03"), EndpointId("E01"))
        )
        val result = GroupMembershipSupport.applyMembershipSnapshot(
            s, 2L, 0L, updated, "M01", "M01"
        )
        assertEquals(GroupMembershipSupport.MembershipSnapshotApplyResult.APPLIED, result)
        assertEquals(2L, s.rosterEpoch)
        assertEquals(2, s.groupMembers.size)
    }

    @Test
    fun applyMembershipSnapshot_forceAlignsWhenLocalEpochAheadFromAuthority() {
        val s = session("M01", "M02", "M03")
        s.rosterEpoch = 3L
        val authorityMembers = listOf(
            EndpointAddress(ModuleId("M01"), EndpointId("E01")),
            EndpointAddress(ModuleId("M02"), EndpointId("E01")),
            EndpointAddress(ModuleId("M03"), EndpointId("E01"))
        )
        val result = GroupMembershipSupport.applyMembershipSnapshot(
            s, 2L, 0L, authorityMembers, "M01", "M01"
        )
        assertEquals(GroupMembershipSupport.MembershipSnapshotApplyResult.APPLIED, result)
        assertEquals(2L, s.rosterEpoch)
        assertEquals(3, s.groupMembers.size)
    }

    @Test
    fun applyMembershipSnapshot_rejectsHigherEpochFromNonAuthority() {
        val s = session("M01", "M03")
        assertEquals(1L, s.rosterEpoch)
        val followerMembers = listOf(
            EndpointAddress(ModuleId("M01"), EndpointId("E01")),
            EndpointAddress(ModuleId("M02"), EndpointId("E01")),
            EndpointAddress(ModuleId("M03"), EndpointId("E01"))
        )
        val result = GroupMembershipSupport.applyMembershipSnapshot(
            s, 2L, 0L, followerMembers, "M03", "M01"
        )
        assertEquals(GroupMembershipSupport.MembershipSnapshotApplyResult.IGNORED_NOT_AUTHORITY, result)
        assertEquals(1L, s.rosterEpoch)
        assertEquals(2, s.groupMembers.size)
    }

    @Test
    fun applyMembershipSnapshot_sameEpochAuthorityHealsStaleRoster() {
        val s = session("M01", "M03")
        val authorityMembers = listOf(
            EndpointAddress(ModuleId("M01"), EndpointId("E01")),
            EndpointAddress(ModuleId("M02"), EndpointId("E01")),
            EndpointAddress(ModuleId("M03"), EndpointId("E01"))
        )
        val result = GroupMembershipSupport.applyMembershipSnapshot(
            s, 1L, 0L, authorityMembers, "M01", "M01"
        )
        assertEquals(GroupMembershipSupport.MembershipSnapshotApplyResult.APPLIED, result)
        assertEquals(1L, s.rosterEpoch)
        assertEquals(3, s.groupMembers.size)
    }

    @Test
    fun applyMembershipSnapshot_sameEpochNonAuthorityIgnored() {
        val s = session("M01", "M03")
        val followerMembers = listOf(
            EndpointAddress(ModuleId("M01"), EndpointId("E01")),
            EndpointAddress(ModuleId("M02"), EndpointId("E01")),
            EndpointAddress(ModuleId("M03"), EndpointId("E01"))
        )
        val result = GroupMembershipSupport.applyMembershipSnapshot(
            s, 1L, 0L, followerMembers, "M03", "M01"
        )
        assertEquals(GroupMembershipSupport.MembershipSnapshotApplyResult.IGNORED_NOT_AUTHORITY, result)
        assertEquals(2, s.groupMembers.size)
    }

    @Test
    fun memberHash_ignoresPendingInvitees() {
        val s = session("M01", "M03")
        val hashBefore = GroupMembershipSupport.memberHashForSession(s)
        s.pendingInviteeEndpoints["M02"] = EndpointAddress(ModuleId("M02"), EndpointId("E01"))
        val hashAfter = GroupMembershipSupport.memberHashForSession(s)
        assertEquals(hashBefore, hashAfter)
    }

    @Test
    fun canonicalMemberKeys_excludesPendingAndEvicted() {
        val s = session("M01", "M02", "M03")
        s.pendingInviteeEndpoints["M04"] = EndpointAddress(ModuleId("M04"), EndpointId("E01"))
        GroupMembershipSupport.markEvicted(s, "M03")
        assertEquals(listOf("M01-E01", "M02-E01"), GroupMembershipSupport.canonicalMemberKeys(s))
    }
}
