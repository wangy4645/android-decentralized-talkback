package com.talkback.appprod.ui

import com.talkback.core.session.ConferenceParticipantDisplayState
import com.talkback.core.session.ConferencePresenceProjection
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class MeetingPresenceDisplayTest {

    @Before
    fun setUp() {
        MeetingPresenceDisplay.receivePathLivenessProvider = testProvider()
    }

    @After
    fun tearDown() {
        MeetingPresenceDisplay.receivePathLivenessProvider = NoOpReceivePathLivenessProvider
    }

    @Test
    fun header_usesJoinedCount_only() {
        assertEquals("3 Participants", MeetingPresenceDisplay.participantCountLabel(joinedCount = 3))
    }

    @Test
    fun r30i_visibleConnected_hidesHint_evenWhenGroupPlaybackGateOff() {
        val ui = MeetingPresenceDisplay.renderConferencePresence(
            presence = ConferencePresenceProjection(joinedCount = 3, connectedCount = 3),
            participantFacts = listOf(
                remote("M01", ConferenceParticipantDisplayState.VISIBLE_CONNECTED, everConnected = true),
                remote("M02", ConferenceParticipantDisplayState.VISIBLE_CONNECTED, everConnected = true),
                remote("M03", ConferenceParticipantDisplayState.VISIBLE_CONNECTED, everConnected = true)
            )
        )
        assertEquals("3 Participants", ui.headerLabel)
        assertNull(ui.connectingHint)
    }

    @Test
    fun r30i_firstJoinWithoutReceive_showsJoiningHint() {
        MeetingPresenceDisplay.receivePathLivenessProvider = object : ReceivePathLivenessProvider {
            override fun receivePathLive(sessionId: String, remoteModuleId: String): Boolean =
                remoteModuleId in setOf("M01", "M02")
        }
        val ui = MeetingPresenceDisplay.renderConferencePresence(
            presence = ConferencePresenceProjection(joinedCount = 3, connectedCount = 2),
            participantFacts = listOf(
                remote("M01", ConferenceParticipantDisplayState.VISIBLE_CONNECTED, everConnected = true),
                remote("M02", ConferenceParticipantDisplayState.VISIBLE_CONNECTED, everConnected = true),
                remote("M03", ConferenceParticipantDisplayState.VISIBLE_CONNECTING, everConnected = false)
            )
        )
        assertEquals("M03 joining...", ui.connectingHint)
        assertEquals(EndpointStatus.CONNECTING, ui.avatarStatuses["M03"])
    }

    @Test
    fun r30i_meshBootstrapConnecting_notHintWhenMediaConnected() {
        val ui = MeetingPresenceDisplay.renderConferencePresence(
            presence = ConferencePresenceProjection(joinedCount = 3, connectedCount = 2),
            participantFacts = listOf(
                remote("M01", ConferenceParticipantDisplayState.VISIBLE_CONNECTED, everConnected = true),
                remote("M02", ConferenceParticipantDisplayState.VISIBLE_CONNECTED, everConnected = true),
                remote("M03", ConferenceParticipantDisplayState.VISIBLE_CONNECTED, everConnected = true)
            )
        )
        assertNull(ui.connectingHint)
        assertEquals(EndpointStatus.ONLINE, ui.avatarStatuses["M03"])
    }

    @Test
    fun r30i_reconnectingAfterLoss_showsReconnectingHint() {
        MeetingPresenceDisplay.receivePathLivenessProvider = object : ReceivePathLivenessProvider {
            override fun receivePathLive(sessionId: String, remoteModuleId: String): Boolean =
                remoteModuleId in setOf("M01", "M02")
        }
        val ui = MeetingPresenceDisplay.renderConferencePresence(
            presence = ConferencePresenceProjection(
                joinedCount = 3,
                connectedCount = 2,
                recoveringPeers = setOf("M03")
            ),
            participantFacts = listOf(
                remote("M01", ConferenceParticipantDisplayState.VISIBLE_CONNECTED, everConnected = true),
                remote("M02", ConferenceParticipantDisplayState.VISIBLE_CONNECTED, everConnected = true),
                remote(
                    "M03",
                    ConferenceParticipantDisplayState.VISIBLE_RECONNECTING,
                    everConnected = true,
                    isRecoveringPeer = true
                )
            )
        )
        assertEquals("M03 reconnecting...", ui.connectingHint)
    }

    @Test
    fun r30i_recoveryPendingWithPlaybackReady_showsOnline_noHint() {
        val ui = MeetingPresenceDisplay.renderConferencePresence(
            presence = ConferencePresenceProjection(
                joinedCount = 3,
                connectedCount = 3,
                recoveringPeers = setOf("M01")
            ),
            participantFacts = listOf(
                remote(
                    "M01",
                    ConferenceParticipantDisplayState.VISIBLE_CONNECTED,
                    everConnected = true,
                    isRecoveringPeer = true
                ),
                remote("M02", ConferenceParticipantDisplayState.VISIBLE_CONNECTED, everConnected = true),
                remote("M03", ConferenceParticipantDisplayState.VISIBLE_CONNECTED, everConnected = true)
            )
        )
        assertNull(ui.connectingHint)
        assertEquals(EndpointStatus.ONLINE, ui.avatarStatuses["M01"])
    }

    @Test
    fun r30i_recoveringWithPlaybackUnavailable_showsReconnectingHint() {
        MeetingPresenceDisplay.receivePathLivenessProvider = object : ReceivePathLivenessProvider {
            override fun receivePathLive(sessionId: String, remoteModuleId: String): Boolean =
                remoteModuleId != "M01"
        }
        val ui = MeetingPresenceDisplay.renderConferencePresence(
            presence = ConferencePresenceProjection(
                joinedCount = 3,
                connectedCount = 2,
                recoveringPeers = setOf("M01")
            ),
            participantFacts = listOf(
                remote(
                    "M01",
                    ConferenceParticipantDisplayState.VISIBLE_RECONNECTING,
                    everConnected = true,
                    isRecoveringPeer = true
                ),
                remote("M02", ConferenceParticipantDisplayState.VISIBLE_CONNECTED, everConnected = true),
                remote("M03", ConferenceParticipantDisplayState.VISIBLE_CONNECTED, everConnected = true)
            )
        )
        assertEquals("M01 reconnecting...", ui.connectingHint)
        assertEquals(EndpointStatus.RECONNECTING, ui.avatarStatuses["M01"])
    }

    @Test
    fun r30i_playbackUnavailableWithoutRecoveringFlag_showsReconnectingHint() {
        MeetingPresenceDisplay.receivePathLivenessProvider = object : ReceivePathLivenessProvider {
            override fun receivePathLive(sessionId: String, remoteModuleId: String): Boolean =
                remoteModuleId != "M01"
        }
        val ui = MeetingPresenceDisplay.renderConferencePresence(
            presence = ConferencePresenceProjection(joinedCount = 3, connectedCount = 2),
            participantFacts = listOf(
                remote(
                    "M01",
                    ConferenceParticipantDisplayState.VISIBLE_RECONNECTING,
                    everConnected = true,
                    isRecoveringPeer = false
                ),
                remote("M02", ConferenceParticipantDisplayState.VISIBLE_CONNECTED, everConnected = true),
                remote("M03", ConferenceParticipantDisplayState.VISIBLE_CONNECTED, everConnected = true)
            )
        )
        assertEquals("M01 reconnecting...", ui.connectingHint)
        assertEquals(EndpointStatus.RECONNECTING, ui.avatarStatuses["M01"])
    }

    @Test
    fun r30i_neverUsesConnectedFractionInHeader() {
        MeetingPresenceDisplay.receivePathLivenessProvider = object : ReceivePathLivenessProvider {
            override fun receivePathLive(sessionId: String, remoteModuleId: String): Boolean =
                remoteModuleId == "M01"
        }
        val ui = MeetingPresenceDisplay.renderConferencePresence(
            presence = ConferencePresenceProjection(joinedCount = 3, connectedCount = 1),
            participantFacts = listOf(
                remote("M01", ConferenceParticipantDisplayState.VISIBLE_CONNECTED, everConnected = true),
                remote("M02", ConferenceParticipantDisplayState.VISIBLE_CONNECTING, everConnected = false),
                remote("M03", ConferenceParticipantDisplayState.VISIBLE_CONNECTING, everConnected = false)
            )
        )
        assertFalse(ui.headerLabel.contains("/"))
    }

    private fun testProvider(): ReceivePathLivenessProvider = object : ReceivePathLivenessProvider {
        override fun receivePathLive(sessionId: String, remoteModuleId: String): Boolean =
            remoteModuleId in setOf("M01", "M02", "M03")
    }

    private fun remote(
        moduleId: String,
        displayState: ConferenceParticipantDisplayState,
        everConnected: Boolean,
        isRecoveringPeer: Boolean = false,
        mediaUnavailablePeer: Boolean = false
    ) = MeetingPresenceDisplay.ParticipantPresentationFacts(
        sessionId = "sess-test",
        moduleId = moduleId,
        isLocal = false,
        displayState = displayState,
        everConnected = everConnected,
        isRecoveringPeer = isRecoveringPeer,
        mediaUnavailablePeer = mediaUnavailablePeer,
        speaking = false
    )
}
