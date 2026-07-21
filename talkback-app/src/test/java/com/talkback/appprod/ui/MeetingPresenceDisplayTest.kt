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
                remote("M01", ConferenceParticipantDisplayState.VISIBLE_CONNECTED),
                remote("M02", ConferenceParticipantDisplayState.VISIBLE_CONNECTED),
                remote("M03", ConferenceParticipantDisplayState.VISIBLE_CONNECTED)
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

            override fun mediaEverLive(sessionId: String, remoteModuleId: String): Boolean =
                remoteModuleId in setOf("M01", "M02")
        }
        val ui = MeetingPresenceDisplay.renderConferencePresence(
            presence = ConferencePresenceProjection(joinedCount = 3, connectedCount = 2),
            participantFacts = listOf(
                remote("M01", ConferenceParticipantDisplayState.VISIBLE_CONNECTED),
                remote("M02", ConferenceParticipantDisplayState.VISIBLE_CONNECTED),
                remote("M03", ConferenceParticipantDisplayState.VISIBLE_CONNECTING)
            )
        )
        assertEquals("M03 joining...", ui.connectingHint)
        assertEquals(EndpointStatus.CONNECTING, ui.avatarStatuses["M03"])
    }

    @Test
    fun g_hist_split_iceConnectedBeforePcm_showsJoiningNotReconnecting() {
        // soak 17:08:21 — transport up, PCM not yet live; mediaEverLive still false for M03 only.
        MeetingPresenceDisplay.receivePathLivenessProvider = object : ReceivePathLivenessProvider {
            override fun receivePathLive(sessionId: String, remoteModuleId: String): Boolean =
                remoteModuleId in setOf("M01", "M02")

            override fun mediaEverLive(sessionId: String, remoteModuleId: String): Boolean =
                remoteModuleId in setOf("M01", "M02")
        }
        val ui = MeetingPresenceDisplay.renderConferencePresence(
            presence = ConferencePresenceProjection(joinedCount = 3, connectedCount = 2),
            participantFacts = listOf(
                remote("M01", ConferenceParticipantDisplayState.VISIBLE_CONNECTED),
                remote("M02", ConferenceParticipantDisplayState.VISIBLE_CONNECTED),
                remote("M03", ConferenceParticipantDisplayState.VISIBLE_CONNECTED)
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
                remote("M01", ConferenceParticipantDisplayState.VISIBLE_CONNECTED),
                remote("M02", ConferenceParticipantDisplayState.VISIBLE_CONNECTED),
                remote("M03", ConferenceParticipantDisplayState.VISIBLE_CONNECTED)
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

            override fun mediaEverLive(sessionId: String, remoteModuleId: String): Boolean =
                remoteModuleId in setOf("M01", "M02", "M03")
        }
        val ui = MeetingPresenceDisplay.renderConferencePresence(
            presence = ConferencePresenceProjection(
                joinedCount = 3,
                connectedCount = 2,
                recoveringPeers = setOf("M03")
            ),
            participantFacts = listOf(
                remote("M01", ConferenceParticipantDisplayState.VISIBLE_CONNECTED),
                remote("M02", ConferenceParticipantDisplayState.VISIBLE_CONNECTED),
                remote(
                    "M03",
                    ConferenceParticipantDisplayState.VISIBLE_RECONNECTING,
                    isRecoveringPeer = true
                )
            )
        )
        assertEquals("M03 reconnecting...", ui.connectingHint)
    }

    @Test
    fun r30i_recoveryPendingWithPlaybackReady_showsReconnecting_rule2() {
        // Rule 2 (session efe1d26d): edge recovering vetoes media liveness.
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
                    isRecoveringPeer = true
                ),
                remote("M02", ConferenceParticipantDisplayState.VISIBLE_CONNECTED),
                remote("M03", ConferenceParticipantDisplayState.VISIBLE_CONNECTED)
            )
        )
        assertEquals("M01 reconnecting...", ui.connectingHint)
        assertEquals(EndpointStatus.RECONNECTING, ui.avatarStatuses["M01"])
    }

    @Test
    fun r30i_recoveringWithPlaybackUnavailable_showsReconnectingHint() {
        MeetingPresenceDisplay.receivePathLivenessProvider = object : ReceivePathLivenessProvider {
            override fun receivePathLive(sessionId: String, remoteModuleId: String): Boolean =
                remoteModuleId != "M01"

            override fun mediaEverLive(sessionId: String, remoteModuleId: String): Boolean = true
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
                    isRecoveringPeer = true
                ),
                remote("M02", ConferenceParticipantDisplayState.VISIBLE_CONNECTED),
                remote("M03", ConferenceParticipantDisplayState.VISIBLE_CONNECTED)
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

            override fun mediaEverLive(sessionId: String, remoteModuleId: String): Boolean = true
        }
        val ui = MeetingPresenceDisplay.renderConferencePresence(
            presence = ConferencePresenceProjection(joinedCount = 3, connectedCount = 2),
            participantFacts = listOf(
                remote(
                    "M01",
                    ConferenceParticipantDisplayState.VISIBLE_RECONNECTING,
                    isRecoveringPeer = false
                ),
                remote("M02", ConferenceParticipantDisplayState.VISIBLE_CONNECTED),
                remote("M03", ConferenceParticipantDisplayState.VISIBLE_CONNECTED)
            )
        )
        assertEquals("M01 reconnecting...", ui.connectingHint)
        assertEquals(EndpointStatus.RECONNECTING, ui.avatarStatuses["M01"])
    }

    @Test
    fun adr0030_failedMediaResidency_mediaUnavailable_vetoesReceivePathLive() {
        MeetingPresenceDisplay.receivePathLivenessProvider = object : ReceivePathLivenessProvider {
            override fun receivePathLive(sessionId: String, remoteModuleId: String): Boolean = true
            override fun mediaEverLive(sessionId: String, remoteModuleId: String): Boolean = true
        }
        val state = MeetingPresenceDisplay.resolveParticipantPresentation(
            remote(
                moduleId = "M01",
                displayState = ConferenceParticipantDisplayState.VISIBLE_FAILED,
                isRecoveringPeer = false,
                mediaUnavailablePeer = true
            )
        )
        assertEquals(EndpointStatus.RECONNECTING, state.endpointStatus)
        assertEquals(
            LocalReachability.ParticipantPresenceState.RECONNECTING,
            state.reachability.state
        )
    }

    @Test
    fun r30i_neverUsesConnectedFractionInHeader() {
        MeetingPresenceDisplay.receivePathLivenessProvider = object : ReceivePathLivenessProvider {
            override fun receivePathLive(sessionId: String, remoteModuleId: String): Boolean =
                remoteModuleId == "M01"

            override fun mediaEverLive(sessionId: String, remoteModuleId: String): Boolean =
                remoteModuleId == "M01"
        }
        val ui = MeetingPresenceDisplay.renderConferencePresence(
            presence = ConferencePresenceProjection(joinedCount = 3, connectedCount = 1),
            participantFacts = listOf(
                remote("M01", ConferenceParticipantDisplayState.VISIBLE_CONNECTED),
                remote("M02", ConferenceParticipantDisplayState.VISIBLE_CONNECTING),
                remote("M03", ConferenceParticipantDisplayState.VISIBLE_CONNECTING)
            )
        )
        assertFalse(ui.headerLabel.contains("/"))
    }

    private fun testProvider(): ReceivePathLivenessProvider = object : ReceivePathLivenessProvider {
        override fun receivePathLive(sessionId: String, remoteModuleId: String): Boolean =
            remoteModuleId in setOf("M01", "M02", "M03")

        override fun mediaEverLive(sessionId: String, remoteModuleId: String): Boolean =
            remoteModuleId in setOf("M01", "M02", "M03")
    }

    private fun remote(
        moduleId: String,
        displayState: ConferenceParticipantDisplayState,
        isRecoveringPeer: Boolean = false,
        mediaUnavailablePeer: Boolean = false
    ) = MeetingPresenceDisplay.ParticipantPresentationFacts(
        sessionId = "sess-test",
        moduleId = moduleId,
        isLocal = false,
        displayState = displayState,
        isRecoveringPeer = isRecoveringPeer,
        mediaUnavailablePeer = mediaUnavailablePeer,
        speaking = false
    )
}
