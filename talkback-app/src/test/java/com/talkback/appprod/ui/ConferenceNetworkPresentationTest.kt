package com.talkback.appprod.ui

import com.talkback.appprod.ui.ConferenceNetworkBannerProjection.BannerScope
import com.talkback.appprod.ui.UserVisibleConnectivityProjection.UserVisibleConnectivityState
import com.talkback.core.session.ConferenceParticipantDisplayState
import com.talkback.core.session.ConferenceParticipantViewState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** P1a integration — meeting presentation consumes ConferenceNetworkBannerProjection once. */
class ConferenceNetworkPresentationTest {

    @Test
    fun caseA_singlePeerFlap_noMeetingPoorNetwork() {
        val state = ConferenceNetworkPresentation.resolve(
            conferenceLive = true,
            localLanOnline = true,
            joinedParticipantCount = 3,
            peerConnectivity = listOf(
                "M03" to UserVisibleConnectivityState.RECONNECTING,
                "M01" to UserVisibleConnectivityState.CONNECTED
            )
        )
        assertEquals(BannerScope.NONE, state.bannerScope)
        assertFalse(state.showBanner)
        assertFalse(state.showPoorNetworkStatusPill)
    }

    @Test
    fun caseB_localWifiOff_localBanner() {
        val state = ConferenceNetworkPresentation.resolve(
            conferenceLive = true,
            localLanOnline = false,
            joinedParticipantCount = 3,
            peerConnectivity = listOf(
                "M01" to UserVisibleConnectivityState.CONNECTED
            )
        )
        assertEquals(BannerScope.LOCAL, state.bannerScope)
        assertTrue(state.showBanner)
        assertTrue(state.showPoorNetworkStatusPill)
    }

    @Test
    fun caseC_majorityPeersImpaired_conferenceBanner() {
        val state = ConferenceNetworkPresentation.resolve(
            conferenceLive = true,
            localLanOnline = true,
            joinedParticipantCount = 3,
            peerConnectivity = listOf(
                "M01" to UserVisibleConnectivityState.RECONNECTING,
                "M03" to UserVisibleConnectivityState.DEGRADED
            )
        )
        assertEquals(BannerScope.CONFERENCE, state.bannerScope)
        assertTrue(state.showBanner)
        assertTrue(state.showPoorNetworkStatusPill)
    }

    @Test
    fun peerConnectivityFromParticipants_usesUvcpNotIceAggregate() {
        MeetingPresenceDisplay.receivePathLivenessProvider = object : ReceivePathLivenessProvider {
            override fun receivePathLive(sessionId: String, remoteModuleId: String): Boolean = true
            override fun mediaEverLive(sessionId: String, remoteModuleId: String): Boolean = true
        }
        try {
            val participants = listOf(
                remoteParticipant("M03", ConferenceParticipantDisplayState.VISIBLE_RECONNECTING),
                remoteParticipant("M01", ConferenceParticipantDisplayState.VISIBLE_CONNECTED)
            )
            val connectivity = ConferenceNetworkPresentation.peerConnectivityFromParticipants(
                sessionId = "sess-1",
                participants = participants,
                speakingModuleId = null,
                isRecoveringPeer = { it == "M03" },
                isMediaUnavailablePeer = { false }
            )
            assertEquals(2, connectivity.size)
            val state = ConferenceNetworkPresentation.resolve(
                conferenceLive = true,
                localLanOnline = true,
                joinedParticipantCount = 3,
                peerConnectivity = connectivity
            )
            assertEquals(BannerScope.NONE, state.bannerScope)
        } finally {
            MeetingPresenceDisplay.receivePathLivenessProvider = NoOpReceivePathLivenessProvider
        }
    }

    private fun remoteParticipant(
        moduleId: String,
        displayState: ConferenceParticipantDisplayState
    ): ConferenceParticipantViewState = ConferenceParticipantViewState(
        moduleId = moduleId,
        key = "$moduleId-E01",
        isLocal = false,
        displayState = displayState
    )
}
