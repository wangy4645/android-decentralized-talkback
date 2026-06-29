package com.talkback.core.session

import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.EndpointId
import com.talkback.core.model.ModuleId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConferenceParticipantManagerTest {
    private val m01 = ModuleId("M01")
    private val m02 = ModuleId("M02")
    private val m03 = ModuleId("M03")
    private val local = EndpointAddress(m01, EndpointId("E01"))
    private val manager = ConferenceParticipantManager()

    @Test
    fun applyPrune_removesRosterButPreservesLeftMemberHint() {
        val sessionId = "CONF-1"
        val members = listOf(
            local,
            EndpointAddress(m02, EndpointId("E01")),
            EndpointAddress(m03, EndpointId("E01"))
        )
        manager.initSession(sessionId, local, members)
        manager.onInviteAccepted(sessionId, "M02")
        manager.onMediaConnected(sessionId, "M02")

        val removed = manager.applyPrune(sessionId, "M03")
        assertEquals(EndpointAddress(m03, EndpointId("E01")), removed)
        assertEquals(2, manager.roster(sessionId).size)
        assertTrue(manager.leftMemberEndpoints(sessionId)?.containsKey("M03") == true)
        assertFalse(manager.containsParticipant(sessionId, "M03"))
    }

    @Test
    fun snapshot_exposesMemberViewsWithoutLocal() {
        val sessionId = "CONF-2"
        manager.initSession(
            sessionId,
            local,
            listOf(local, EndpointAddress(m02, EndpointId("E01")))
        )
        manager.onInviteAccepted(sessionId, "M02")
        manager.onMediaConnected(sessionId, "M02")

        val snap = manager.snapshot(sessionId, m01)
        assertEquals(2, snap.roster.size)
        assertEquals(1, snap.memberViews.size)
        assertEquals("M02", snap.memberViews.single().moduleId)
        assertEquals(MediaState.CONNECTED, snap.memberViews.single().media)
        assertTrue(snap.everConnectedModules.contains(m02))
    }

    @Test
    fun onLateJoin_restoresMissingParticipant() {
        val sessionId = "CONF-3"
        manager.initSession(sessionId, local, listOf(local, EndpointAddress(m02, EndpointId("E01"))))
        manager.applyPrune(sessionId, "M02")

        manager.onLateJoin(sessionId, "M02", EndpointAddress(m02, EndpointId("E01")))
        val snap = manager.snapshot(sessionId, m01)
        assertTrue(snap.roster.any { it.moduleId == m02 })
        assertEquals(InviteState.ACCEPTED, snap.memberViews.single().invite)
    }
}
