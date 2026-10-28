package com.talkback.core.session

import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.EndpointId
import com.talkback.core.model.ModuleId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ConferenceMembershipReducerTest {
    private val m01 = ModuleId("M01")
    private val m02 = ModuleId("M02")
    private val m03 = ModuleId("M03")
    private val local = EndpointAddress(m01, EndpointId("E01"))
    private val e02 = EndpointAddress(m02, EndpointId("E01"))
    private val e03 = EndpointAddress(m03, EndpointId("E01"))
    private val sessionId = "conf-1"
    private lateinit var manager: ConferenceParticipantManager
    private lateinit var reducer: ConferenceMembershipReducer

    @Before
    fun setUp() {
        ParticipantLifecycleTracer.resetForTests()
        manager = ConferenceParticipantManager()
        manager.initSession(sessionId, local, listOf(local, e02, e03))
        reducer = ConferenceMembershipReducer(manager)
    }

    private fun snapshot(members: List<EndpointAddress> = listOf(local, e02, e03)) =
        reducer.apply(sessionId, m03, ConferenceMembershipEvent.SnapshotCorrected(members))

    @Test
    fun peerLeft_prunesAndMarksMeshInvalidation() {
        val result = reducer.apply(sessionId, m01, ConferenceMembershipEvent.PeerLeft("M02"))
        assertTrue(result.rosterMutated)
        assertEquals(setOf("M02"), result.meshInvalidatedModuleIds)
        assertTrue(manager.leftMemberEndpoints(sessionId)?.containsKey("M02") == true)
        assertFalse(manager.roster(sessionId).any { it.moduleId == m02 })
    }

    @Test
    fun peerReactivated_restoresAfterPrune() {
        reducer.apply(sessionId, m01, ConferenceMembershipEvent.PeerLeft("M02"))
        val result = reducer.apply(sessionId, m03, ConferenceMembershipEvent.PeerReactivated(e02))
        assertTrue(result.rosterMutated)
        assertEquals(setOf("M02"), result.meshInvalidatedModuleIds)
        assertFalse(manager.leftMemberEndpoints(sessionId)?.containsKey("M02") == true)
        assertTrue(manager.roster(sessionId).any { it.moduleId == m02 })
        assertEquals(InviteState.ACCEPTED, manager.participant(sessionId, "M02").invite)
    }

    @Test
    fun snapshotCorrected_idempotentReplay() {
        reducer.apply(sessionId, m01, ConferenceMembershipEvent.PeerLeft("M02"))
        val members = listOf(local, e02, e03)
        val first = reducer.apply(sessionId, m03, ConferenceMembershipEvent.SnapshotCorrected(members))
        val second = reducer.apply(sessionId, m03, ConferenceMembershipEvent.SnapshotCorrected(members))
        assertTrue(first.rosterMutated)
        assertTrue(manager.roster(sessionId).any { it.moduleId == m02 })
        assertFalse(manager.leftMemberEndpoints(sessionId)?.containsKey("M02") == true)
        assertFalse(second.rosterMutated)
    }

    @Test
    fun snapshotCorrected_neverAcceptsPendingInvitee() {
        val ps = manager.participant(sessionId, "M02")
        assertEquals(InviteState.NONE, ps.invite)
        assertEquals(MediaState.NONE, ps.media)

        snapshot()

        assertEquals(InviteState.NONE, ps.invite)
        assertEquals(MediaState.NONE, ps.media)
    }

    @Test
    fun snapshotCorrected_preservesAlreadyAccepted() {
        manager.onInviteAccepted(sessionId, "M02")

        snapshot()

        assertEquals(InviteState.ACCEPTED, manager.participant(sessionId, "M02").invite)
        assertEquals(MediaState.CONNECTING, manager.participant(sessionId, "M02").media)
    }

    @Test
    fun snapshotCorrected_preservesConnected() {
        manager.onInviteAccepted(sessionId, "M02")
        manager.onMediaConnected(sessionId, "M02")

        snapshot()

        assertEquals(InviteState.ACCEPTED, manager.participant(sessionId, "M02").invite)
        assertEquals(MediaState.CONNECTED, manager.participant(sessionId, "M02").media)
    }

    @Test
    fun snapshotCorrected_afterLeft_restoresMembershipWithoutAccepting() {
        reducer.apply(sessionId, m01, ConferenceMembershipEvent.PeerLeft("M02"))

        snapshot()

        assertTrue(manager.roster(sessionId).any { it.moduleId == m02 })
        assertEquals(InviteState.NONE, manager.participant(sessionId, "M02").invite)
        assertEquals(MediaState.NONE, manager.participant(sessionId, "M02").media)
    }
}
