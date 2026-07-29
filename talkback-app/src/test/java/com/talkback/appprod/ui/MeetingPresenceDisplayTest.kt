package com.talkback.appprod.ui

import com.talkback.core.session.ConferenceParticipantDisplayState
import com.talkback.core.session.ConferencePresenceProjection
import com.talkback.core.session.MediaState
import com.talkback.core.session.MediaUsabilityFact
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
    fun adr0034_recoveryPendingWithPlaybackReady_showsSyncing_notReconnecting() {
        // ADR-0034: MEDIA_OK + recovering/control pending → SYNCING (supersedes ADR-0030 Rule 2 UX).
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
        assertEquals("M01 syncing...", ui.connectingHint)
        assertEquals(EndpointStatus.SYNCING, ui.avatarStatuses["M01"])
        assertEquals(
            UserVisibleConnectivityProjection.UserVisibleConnectivityState.SYNCING,
            ui.participantStates.first { it.moduleId == "M01" }.visibleConnectivity
        )
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
    fun invPres006_iceDisconnected_stickyPcm_mediaFactUnavailable_showsReconnecting_notSyncing() {
        // obs-pres-e-20260729-140050: ice=DISCONNECTED, media=RECONNECTING, residency=false,
        // sticky receivePathLive=true → must not paint SYNCING (INV-PRES-006).
        MeetingPresenceDisplay.receivePathLivenessProvider = object : ReceivePathLivenessProvider {
            override fun receivePathLive(sessionId: String, remoteModuleId: String): Boolean = true
            override fun mediaEverLive(sessionId: String, remoteModuleId: String): Boolean = true
        }
        val mediaUnavailable = MediaUsabilityFact.isUnavailable(
            mediaState = MediaState.RECONNECTING,
            failedMediaResidency = false
        )
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
                    isRecoveringPeer = true,
                    mediaUnavailablePeer = mediaUnavailable
                )
            )
        )
        assertEquals("M03 reconnecting...", ui.connectingHint)
        assertEquals(EndpointStatus.RECONNECTING, ui.avatarStatuses["M03"])
        assertNotEquals(EndpointStatus.SYNCING, ui.avatarStatuses["M03"])
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

    @Test
    fun adr0034_recoveringPeersAggregate_alone_doesNotDriveHint() {
        // recoveringPeers on presence projection is diagnostic; per-peer facts own UX.
        val ui = MeetingPresenceDisplay.renderConferencePresence(
            presence = ConferencePresenceProjection(
                joinedCount = 3,
                connectedCount = 3,
                recoveringPeers = setOf("M03")
            ),
            participantFacts = listOf(
                remote("M01", ConferenceParticipantDisplayState.VISIBLE_CONNECTED),
                remote("M02", ConferenceParticipantDisplayState.VISIBLE_CONNECTED),
                remote(
                    "M03",
                    ConferenceParticipantDisplayState.VISIBLE_CONNECTED,
                    isRecoveringPeer = false
                )
            )
        )
        assertNull(ui.connectingHint)
        assertEquals(EndpointStatus.ONLINE, ui.avatarStatuses["M03"])
    }

    @Test
    fun adr0034_controlDegraded_withMediaOk_showsDegraded() {
        val ui = MeetingPresenceDisplay.renderConferencePresence(
            presence = ConferencePresenceProjection(joinedCount = 2, connectedCount = 2),
            participantFacts = listOf(
                remote("M01", ConferenceParticipantDisplayState.VISIBLE_CONNECTED),
                remote(
                    "M03",
                    ConferenceParticipantDisplayState.VISIBLE_CONNECTED,
                    controlDegraded = true
                )
            )
        )
        assertEquals("M03 degraded...", ui.connectingHint)
        assertEquals(EndpointStatus.DEGRADED, ui.avatarStatuses["M03"])
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
        mediaUnavailablePeer: Boolean = false,
        controlDegraded: Boolean = false,
        controlSyncPending: Boolean = false
    ) = MeetingPresenceDisplay.ParticipantPresentationFacts(
        sessionId = "sess-test",
        moduleId = moduleId,
        isLocal = false,
        displayState = displayState,
        isRecoveringPeer = isRecoveringPeer,
        mediaUnavailablePeer = mediaUnavailablePeer,
        speaking = false,
        controlDegraded = controlDegraded,
        controlSyncPending = controlSyncPending
    )
}
