package com.talkback.core.session

import com.talkback.core.model.ModuleId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupMeshPlannerTest {
    private val m01 = ModuleId("M01")
    private val m02 = ModuleId("M02")
    private val m03 = ModuleId("M03")
    private val m04 = ModuleId("M04")
    private val m05 = ModuleId("M05")

    @Test
    fun inviteTargets_smallestModuleInvitesHigherPeers() {
        val members = setOf(m01, m02, m03)
        assertEquals(listOf(m02, m03), GroupMeshPlanner.inviteTargets(m01, members))
        assertEquals(listOf(m03), GroupMeshPlanner.inviteTargets(m02, members))
        assertTrue(GroupMeshPlanner.inviteTargets(m03, members).isEmpty())
    }

    @Test
    fun initiatorSendsNoJoin() {
        val members = setOf(m01, m02, m03)
        assertTrue(GroupMeshPlanner.joinTargets(m01, m01, members).isEmpty())
    }

    @Test
    fun middleMemberOffersToHigherModule() {
        val members = setOf(m01, m02, m03)
        assertEquals(listOf(m03), GroupMeshPlanner.joinTargets(m02, m01, members))
    }

    @Test
    fun highestMemberOffersToNobody() {
        val members = setOf(m01, m02, m03)
        assertTrue(GroupMeshPlanner.joinTargets(m03, m01, members).isEmpty())
    }

    @Test
    fun fourModuleMeshOffers() {
        val members = setOf(m01, m02, m03, m04)
        assertEquals(listOf(m03, m04), GroupMeshPlanner.joinTargets(m02, m01, members))
        assertEquals(listOf(m04), GroupMeshPlanner.joinTargets(m03, m01, members))
    }

    @Test
    fun fiveModulePlanner() {
        val members = setOf(m01, m02, m03, m04, m05)
        assertEquals(listOf(m03, m04, m05), GroupMeshPlanner.joinTargets(m02, m01, members))
        assertEquals(listOf(m04, m05), GroupMeshPlanner.joinTargets(m03, m01, members))
        assertEquals(listOf(m05), GroupMeshPlanner.joinTargets(m04, m01, members))
    }
}
