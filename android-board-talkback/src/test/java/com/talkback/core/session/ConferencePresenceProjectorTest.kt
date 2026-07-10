package com.talkback.core.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConferencePresenceProjectorTest {

    @Test
    fun presence_notAccepted_zeros() {
        val out = ConferencePresenceProjector.project(
            ConferencePresenceProjector.Input(
                sessionAccepted = false,
                joinedParticipantCount = 3,
                connectedRemoteModuleIds = setOf("M01", "M03"),
                recoveringRemoteModuleIds = setOf("M01")
            )
        )
        assertEquals(0, out.joinedCount)
        assertEquals(0, out.connectedCount)
        assertTrue(out.recoveringPeers.isEmpty())
    }

    @Test
    fun presence_s13bSoak_joined3_connected2_m01Recovering() {
        val out = ConferencePresenceProjector.project(
            ConferencePresenceProjector.Input(
                sessionAccepted = true,
                joinedParticipantCount = 3,
                connectedRemoteModuleIds = setOf("M03"),
                recoveringRemoteModuleIds = setOf("M01")
            )
        )
        assertEquals(3, out.joinedCount)
        assertEquals(2, out.connectedCount)
        assertEquals(setOf("M01"), out.recoveringPeers)
    }

    @Test
    fun presence_hostSolo_connectedLocalOnly() {
        val out = ConferencePresenceProjector.project(
            ConferencePresenceProjector.Input(
                sessionAccepted = true,
                joinedParticipantCount = 1,
                connectedRemoteModuleIds = emptySet()
            )
        )
        assertEquals(1, out.joinedCount)
        assertEquals(1, out.connectedCount)
        assertTrue(out.recoveringPeers.isEmpty())
    }

    @Test
    fun presence_fullMesh_allConnected() {
        val out = ConferencePresenceProjector.project(
            ConferencePresenceProjector.Input(
                sessionAccepted = true,
                joinedParticipantCount = 3,
                connectedRemoteModuleIds = setOf("M01", "M03")
            )
        )
        assertEquals(3, out.joinedCount)
        assertEquals(3, out.connectedCount)
    }
}
