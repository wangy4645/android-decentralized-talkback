package com.talkback.appprod.ui

import com.talkback.core.session.ConferenceParticipantDisplayState
import com.talkback.core.session.EpisodeCompletionProjection
import com.talkback.core.session.EpisodeCompletionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * PR5-3 M1/M2 — completion axis on presentation; Class C recovering demoted from connectivity.
 */
class EpisodeCompletionAxisAdapterTest {

    @Before
    fun setUp() {
        UserVisibleConnectivityProjection.resetForTest(demoteClassC = true)
        MeetingPresenceDisplay.receivePathLivenessProvider = object : ReceivePathLivenessProvider {
            override fun receivePathLive(sessionId: String, remoteModuleId: String) = true
            override fun mediaEverLive(sessionId: String, remoteModuleId: String) = true
        }
    }

    @After
    fun tearDown() {
        MeetingPresenceDisplay.receivePathLivenessProvider = NoOpReceivePathLivenessProvider
        UserVisibleConnectivityProjection.resetForTest(demoteClassC = true)
    }

    @Test
    fun completionAxis_independentOfConnectivity_whenRecoveringDemoted() {
        val projection = EpisodeCompletionProjection(
            sessionId = "s",
            remoteModuleId = "M02",
            obligationGeneration = 1L,
            completionState = EpisodeCompletionState.OPEN,
            completionReason = "DELIVERY_PENDING",
            completionSource = "COMPLETION_OBSERVATION",
            completionEpochMs = 1L
        )
        val state = MeetingPresenceDisplay.resolveParticipantPresentation(
            MeetingPresenceDisplay.ParticipantPresentationFacts(
                sessionId = "s",
                moduleId = "M02",
                isLocal = false,
                displayState = ConferenceParticipantDisplayState.VISIBLE_CONNECTED,
                isRecoveringPeer = true,
                mediaUnavailablePeer = false,
                speaking = false,
                episodeCompletion = projection
            )
        )
        assertEquals(projection, state.episodeCompletion)
        assertEquals(
            UserVisibleConnectivityProjection.UserVisibleConnectivityState.CONNECTED,
            state.visibleConnectivity
        )
        assertEquals(EndpointStatus.ONLINE, state.endpointStatus)
    }

    @Test
    fun completionAxis_nullWhenAbsent() {
        val state = MeetingPresenceDisplay.resolveParticipantPresentation(
            MeetingPresenceDisplay.ParticipantPresentationFacts(
                sessionId = "s",
                moduleId = "M02",
                isLocal = false,
                displayState = ConferenceParticipantDisplayState.VISIBLE_CONNECTED,
                isRecoveringPeer = false,
                mediaUnavailablePeer = false,
                speaking = false,
                episodeCompletion = null
            )
        )
        assertNull(state.episodeCompletion)
        assertEquals(
            UserVisibleConnectivityProjection.UserVisibleConnectivityState.CONNECTED,
            state.visibleConnectivity
        )
    }
}