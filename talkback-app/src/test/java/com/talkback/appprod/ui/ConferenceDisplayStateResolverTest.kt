package com.talkback.appprod.ui

import com.talkback.core.session.ConferenceRuntimePhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConferenceDisplayStateResolverTest {

    @Test
    fun host_channelReady_runtimeConnecting_isLive() {
        val display = resolve(
            conferenceActive = true,
            channelReady = true,
            runtimePhase = ConferenceRuntimePhase.CONNECTING
        )
        assertTrue(display.live)
        assertTrue(display.showLivePanel)
        assertFalse(display.showConnectingPanel)
        assertEquals(ConferenceDisplayPhase.LIVE, display.phase)
        assertEquals(ConferenceStatusPillKind.LIVE, display.statusPill)
    }

    @Test
    fun host_mediaNotReady_isConnecting() {
        val display = resolve(
            conferenceActive = true,
            channelReady = false,
            runtimePhase = ConferenceRuntimePhase.CONNECTING
        )
        assertFalse(display.live)
        assertTrue(display.mediaConnecting)
        assertTrue(display.showConnectingPanel)
        assertEquals(ConferenceStatusPillKind.CONNECTING, display.statusPill)
    }

    @Test
    fun live_withAwaitingParticipants_staysLive_membershipHintOnly() {
        val display = resolve(
            conferenceActive = true,
            channelReady = true,
            runtimePhase = ConferenceRuntimePhase.ACTIVE,
            awaitingAdditionalParticipants = true
        )
        assertTrue(display.live)
        assertTrue(display.membershipHintVisible)
        assertEquals(ConferenceStatusPillKind.LIVE, display.statusPill)
    }

    @Test
    fun live_recovering_showsLivePanelWithRecoveringPill() {
        val display = resolve(
            conferenceActive = true,
            channelReady = true,
            runtimePhase = ConferenceRuntimePhase.RECOVERING,
            reconnecting = true
        )
        assertTrue(display.live)
        assertTrue(display.recovering)
        assertTrue(display.showLivePanel)
        assertFalse(display.showConnectingPanel)
        assertEquals(ConferenceStatusPillKind.RECOVERING, display.statusPill)
    }

    @Test
    fun awaitingRejoin_showsConnectingPanel() {
        val display = ConferenceDisplayStateResolver.resolve(
            lifecycle = ConferenceLifecycleFacts(
                conferenceActive = false,
                conferenceMode = true
            ),
            connectivity = ConferenceConnectivityFacts(channelReady = false)
        )
        assertFalse(display.live)
        assertTrue(display.showConnectingPanel)
        assertEquals(ConferenceDisplayPhase.AWAITING_REJOIN, display.phase)
    }

    @Test
    fun timerEligible_whenLive() {
        val display = resolve(conferenceActive = true, channelReady = true)
        assertTrue(display.timerEligible)
    }

    private fun resolve(
        conferenceActive: Boolean,
        channelReady: Boolean,
        runtimePhase: ConferenceRuntimePhase? = null,
        awaitingAdditionalParticipants: Boolean = false,
        reconnecting: Boolean = false
    ) = ConferenceDisplayStateResolver.resolve(
        lifecycle = ConferenceLifecycleFacts(
            conferenceActive = conferenceActive,
            runtimePhase = runtimePhase
        ),
        connectivity = ConferenceConnectivityFacts(
            channelReady = channelReady,
            reconnecting = reconnecting
        ),
        membership = ConferenceMembershipFacts(
            awaitingAdditionalParticipants = awaitingAdditionalParticipants
        )
    )
}
