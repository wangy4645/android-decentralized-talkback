package com.talkback.appprod.ui

import com.talkback.core.session.ConferenceParticipantDisplayState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * PR5-3 presentation adapter guards (UVCP connectivity axis).
 *
 * Episode completion projection is no longer carried on [MeetingPresenceDisplay.ParticipantPresentationState];
 * see `EpisodeCompletionProjectionFacadeTest` in android-board-talkback for completion ownership tests.
 */
class EpisodeCompletionAxisAdapterTest {

    @Before
    fun setUp() {
        MeetingPresenceDisplay.receivePathLivenessProvider = object : ReceivePathLivenessProvider {
            override fun receivePathLive(sessionId: String, remoteModuleId: String) = true
            override fun mediaEverLive(sessionId: String, remoteModuleId: String) = true
        }
    }

    @After
    fun tearDown() {
        MeetingPresenceDisplay.receivePathLivenessProvider = NoOpReceivePathLivenessProvider
    }

    @Test
    fun recoveringPeer_mediaOk_projectsSyncing_notReconnecting() {
        val state = MeetingPresenceDisplay.resolveParticipantPresentation(
            MeetingPresenceDisplay.ParticipantPresentationFacts(
                sessionId = "s",
                moduleId = "M02",
                isLocal = false,
                displayState = ConferenceParticipantDisplayState.VISIBLE_CONNECTED,
                isRecoveringPeer = true,
                mediaUnavailablePeer = false,
                speaking = false
            )
        )
        assertEquals(
            UserVisibleConnectivityProjection.UserVisibleConnectivityState.SYNCING,
            state.visibleConnectivity
        )
        assertEquals(EndpointStatus.SYNCING, state.endpointStatus)
    }

    @Test
    fun stablePeer_mediaOk_projectsConnected() {
        val state = MeetingPresenceDisplay.resolveParticipantPresentation(
            MeetingPresenceDisplay.ParticipantPresentationFacts(
                sessionId = "s",
                moduleId = "M02",
                isLocal = false,
                displayState = ConferenceParticipantDisplayState.VISIBLE_CONNECTED,
                isRecoveringPeer = false,
                mediaUnavailablePeer = false,
                speaking = false
            )
        )
        assertEquals(
            UserVisibleConnectivityProjection.UserVisibleConnectivityState.CONNECTED,
            state.visibleConnectivity
        )
        assertEquals(EndpointStatus.ONLINE, state.endpointStatus)
    }
}
