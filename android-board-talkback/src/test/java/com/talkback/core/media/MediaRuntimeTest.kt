package com.talkback.core.media

import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.EndpointId
import com.talkback.core.model.ModuleId
import com.talkback.core.session.ConferenceParticipantManager
import com.talkback.core.session.InviteState
import com.talkback.core.session.MediaState
import com.talkback.core.session.SessionType
import com.talkback.core.session.ParticipantLifecycleTracer
import com.talkback.core.session.TalkbackSession
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class MediaRuntimeTest {
    private val m01 = ModuleId("M01")
    private val m02 = ModuleId("M02")
    private val local = EndpointAddress(m01, EndpointId("E01"))
    private val remote = EndpointAddress(m02, EndpointId("E01"))
    private val sessionId = "grp-1"
    private lateinit var groupSession: TalkbackSession
    private lateinit var conferenceManager: ConferenceParticipantManager

    @Before
    fun setUp() {
        ParticipantLifecycleTracer.resetForTests()
        groupSession = TalkbackSession(sessionId, SessionType.GROUP, local, "CH-01")
        groupSession.accepted = true
        groupSession.participant(m02.value)
        conferenceManager = ConferenceParticipantManager()
        conferenceManager.initSession(sessionId, local, listOf(local, remote))
    }

    @Test
    fun onIceStateChanged_groupConnected_setsMediaAndAcceptsInvite() {
        groupSession.participant(m02.value).invite = InviteState.INVITING
        MediaRuntime.onIceStateChanged(groupSession, m02.value, "CONNECTED", conferenceManager)
        val ps = groupSession.participant(m02.value)
        assertEquals(MediaState.CONNECTED, ps.media)
        assertEquals(InviteState.ACCEPTED, ps.invite)
    }

    @Test
    fun onIceStateChanged_groupDisconnected_setsReconnecting() {
        groupSession.participant(m02.value).media = MediaState.CONNECTED
        MediaRuntime.onIceStateChanged(groupSession, m02.value, "DISCONNECTED", conferenceManager)
        assertEquals(MediaState.RECONNECTING, groupSession.participant(m02.value).media)
    }

    @Test
    fun onIceStateChanged_conferenceDelegatesToManager() {
        MediaRuntime.onIceStateChanged(
            TalkbackSession(sessionId, SessionType.CONFERENCE, local, "CH-01"),
            m02.value,
            "CONNECTED",
            conferenceManager
        )
        assertEquals(MediaState.CONNECTED, conferenceManager.participant(sessionId, m02.value).media)
    }

    @Test
    fun mediaStateFromIce_mapsNegotiatingToConnecting() {
        assertEquals(MediaState.CONNECTING, MediaRuntime.mediaStateFromIce("CHECKING", MediaState.NONE))
    }
}
