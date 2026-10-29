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
    fun participantMedia_readOnly_doesNotCreateRecord() {
        val sessionId = "CONF-4"
        manager.initSession(
            sessionId,
            local,
            listOf(local, EndpointAddress(m02, EndpointId("E01")))
        )
        manager.onInviteAccepted(sessionId, "M02")
        manager.updateMediaState(sessionId, "M02", MediaState.RECONNECTING)

        assertEquals(MediaState.RECONNECTING, manager.participantMedia(sessionId, "M02"))
        assertEquals(MediaState.NONE, manager.participantMedia(sessionId, "M03"))
        assertFalse(manager.containsParticipant(sessionId, "M03"))
    }

    @Test
    fun applyCanonicalMembershipRoster_marksRemotesAcceptedWithoutDowngradingConnected() {
        val sessionId = "CONF-10"
        manager.initSession(sessionId, local, listOf(local, EndpointAddress(m02, EndpointId("E01"))))
        manager.onInviteAccepted(sessionId, "M02")
        manager.onMediaConnected(sessionId, "M02")
        val ten = (2..10).map { i ->
            EndpointAddress(ModuleId("M%02d".format(i)), EndpointId("E01"))
        }
        manager.applyCanonicalMembershipRoster(
            sessionId,
            listOf(local) + ten,
            m01,
        )
        val snap = manager.snapshot(sessionId, m01)
        assertEquals(10, snap.roster.size)
        assertEquals(9, snap.memberViews.size)
        assertEquals(MediaState.CONNECTED, snap.memberViews.single { it.moduleId == "M02" }.media)
        assertTrue(snap.memberViews.all { it.invite == InviteState.ACCEPTED })
        assertEquals(MediaState.CONNECTED, snap.memberViews.single { it.moduleId == "M10" }.media)
    }

    @Test
    fun applyCanonicalMembershipRoster_doesNotOverwriteHostMemberMediaFacts() {
        val sessionId = "CONF-11"
        val m04 = ModuleId("M04")
        manager.initSession(
            sessionId,
            local,
            listOf(
                local,
                EndpointAddress(m02, EndpointId("E01")),
                EndpointAddress(m03, EndpointId("E01")),
                EndpointAddress(m04, EndpointId("E01")),
            ),
        )
        manager.onInviteAccepted(sessionId, "M02")
        manager.updateMediaState(sessionId, "M03", MediaState.RECONNECTING)
        manager.onInviteAccepted(sessionId, "M04")
        manager.updateMediaState(sessionId, "M04", MediaState.FAILED)

        manager.applyCanonicalMembershipRoster(
            sessionId,
            listOf(
                local,
                EndpointAddress(m02, EndpointId("E01")),
                EndpointAddress(m03, EndpointId("E01")),
                EndpointAddress(m04, EndpointId("E01")),
            ),
            m01,
        )

        val snap = manager.snapshot(sessionId, m01)
        assertEquals(MediaState.CONNECTING, snap.memberViews.single { it.moduleId == "M02" }.media)
        assertEquals(MediaState.RECONNECTING, snap.memberViews.single { it.moduleId == "M03" }.media)
        assertEquals(MediaState.FAILED, snap.memberViews.single { it.moduleId == "M04" }.media)
    }

    @Test
    fun applyCanonicalMembershipRoster_admittedOnly_keepsUnlistedInviteeRecord() {
        val sessionId = "CONF-ADMITTED"
        val m04 = ModuleId("M04")
        manager.initSession(
            sessionId,
            local,
            listOf(
                local,
                EndpointAddress(m02, EndpointId("E01")),
                EndpointAddress(m03, EndpointId("E01")),
                EndpointAddress(m04, EndpointId("E01")),
            ),
        )
        manager.onInviteSent(sessionId, "M02", 1L, forReconnect = false)
        manager.onInviteSent(sessionId, "M03", 1L, forReconnect = false)
        manager.onInviteSent(sessionId, "M04", 1L, forReconnect = false)
        manager.onInviteAccepted(sessionId, "M02")

        manager.applyCanonicalMembershipRoster(
            sessionId,
            listOf(local, EndpointAddress(m02, EndpointId("E01"))),
            m01,
        )

        val snap = manager.snapshot(sessionId, m01)
        assertEquals(listOf("M01", "M02"), snap.roster.map { it.moduleId.value })
        assertEquals(InviteState.ACCEPTED, snap.memberViews.single { it.moduleId == "M02" }.invite)
        assertTrue(manager.containsParticipant(sessionId, "M03"))
        assertTrue(manager.containsParticipant(sessionId, "M04"))
        assertEquals(InviteState.INVITING, manager.participant(sessionId, "M03").invite)
        assertEquals(InviteState.INVITING, manager.participant(sessionId, "M04").invite)
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
