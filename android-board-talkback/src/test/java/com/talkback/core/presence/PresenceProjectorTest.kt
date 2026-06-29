package com.talkback.core.presence

import com.talkback.core.audio.ModuleAudioMixer
import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.EndpointId
import com.talkback.core.model.ModuleId
import com.talkback.core.session.SessionDisposition
import com.talkback.core.session.SessionType
import com.talkback.core.session.TalkbackSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PresenceProjectorTest {
    private val local = EndpointAddress(ModuleId("M01"), EndpointId("E01"))

    @Test
    fun groupSession_includesProtocolFloorOwner() {
        val session = TalkbackSession("s1", SessionType.GROUP, local, "ch1")
        session.groupMembers = listOf(local)
        session.floor.applyGrant(local, 1L, 1L)

        val snap = PresenceProjector.sessionSnapshot(session)

        assertEquals(local.key, snap.protocolFloorOwnerKey)
        assertEquals(SessionType.GROUP, snap.type)
        assertEquals(listOf(local.key), snap.membershipKeys)
    }

    @Test
    fun conferenceSession_omitsProtocolFloorOwner() {
        val session = TalkbackSession("s2", SessionType.CONFERENCE, local, "ch2")

        val snap = PresenceProjector.sessionSnapshot(session)

        assertNull(snap.protocolFloorOwnerKey)
    }

    @Test
    fun moduleSnapshot_reflectsCaptureState() {
        val mixer = ModuleAudioMixer()
        mixer.setActiveCapture(local)

        val snap = PresenceProjector.moduleSnapshot(
            moduleMixer = mixer,
            localUplinkGrant = true,
            iceByPeer = mapOf("M02" to "CONNECTED")
        )

        assertTrue(snap.localUplinkGrant)
        assertEquals(local.key, snap.activeCaptureEndpointKey)
        assertEquals("CONNECTED", snap.iceByPeer["M02"])
    }

    @Test
    fun invariantF1_uplinkWithoutProtocolOwner_fails() {
        val sessionSnap = SessionPresenceSnapshot(
            sessionId = "s1",
            type = SessionType.GROUP,
            protocolFloorOwnerKey = "M02-E01",
            membershipKeys = emptyList(),
            rosterEpoch = 0L,
            disposition = SessionDisposition.ACTIVE
        )
        val moduleSnap = ModulePresenceSnapshot(
            localUplinkGrant = true,
            activeCaptureEndpointKey = local.key,
            iceByPeer = emptyMap()
        )

        assertFalse(
            PresenceProjector.satisfiesInvariantF1(sessionSnap, moduleSnap, local.key)
        )
    }

    @Test
    fun invariantF1_grantPendingUplinkOff_passes() {
        val sessionSnap = SessionPresenceSnapshot(
            sessionId = "s1",
            type = SessionType.GROUP,
            protocolFloorOwnerKey = local.key,
            membershipKeys = emptyList(),
            rosterEpoch = 0L,
            disposition = SessionDisposition.ACTIVE
        )
        val moduleSnap = ModulePresenceSnapshot(
            localUplinkGrant = false,
            activeCaptureEndpointKey = null,
            iceByPeer = emptyMap()
        )

        assertTrue(
            PresenceProjector.satisfiesInvariantF1(sessionSnap, moduleSnap, local.key)
        )
    }
}
