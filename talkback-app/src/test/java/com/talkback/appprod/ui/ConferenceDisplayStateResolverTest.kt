package com.talkback.appprod.ui

import com.talkback.core.session.ConferenceHealthUiProjection
import com.talkback.core.session.ConferenceL4RoomState
import com.talkback.core.session.ConferenceRoomFacing
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
    fun healthOnline_runtimeRecovering_roomStaysLive() {
        val display = ConferenceDisplayStateResolver.resolve(
            lifecycle = ConferenceLifecycleFacts(
                conferenceActive = true,
                runtimePhase = ConferenceRuntimePhase.RECOVERING
            ),
            connectivity = ConferenceConnectivityFacts(
                channelReady = true,
                reconnecting = true
            ),
            healthUi = ConferenceHealthUiProjection(
                roomFacing = ConferenceRoomFacing.ONLINE,
                l4RoomState = ConferenceL4RoomState.ONLINE,
                recoveryInFlightDiagnostic = true,
                recoveringPeerChrome = setOf("M02")
            )
        )
        assertTrue(display.live)
        assertFalse(display.recovering)
        assertEquals(ConferenceDisplayPhase.LIVE, display.phase)
        assertEquals(ConferenceStatusPillKind.LIVE, display.statusPill)
    }

    @Test
    fun healthNotOnline_channelReady_isLive() {
        val display = ConferenceDisplayStateResolver.resolve(
            lifecycle = ConferenceLifecycleFacts(
                conferenceActive = true,
                runtimePhase = ConferenceRuntimePhase.ACTIVE
            ),
            connectivity = ConferenceConnectivityFacts(channelReady = true),
            healthUi = ConferenceHealthUiProjection(
                roomFacing = ConferenceRoomFacing.NOT_ONLINE,
                l4RoomState = ConferenceL4RoomState.NOT_ESTABLISHED,
                recoveryInFlightDiagnostic = false
            )
        )
        assertTrue(display.live)
        assertTrue(display.showLivePanel)
        assertFalse(display.showConnectingPanel)
        assertEquals(ConferenceDisplayPhase.LIVE, display.phase)
        assertEquals(ConferenceStatusPillKind.LIVE, display.statusPill)
    }

    @Test
    fun conferenceActive_channelNotReady_isConnecting() {
        val display = ConferenceDisplayStateResolver.resolve(
            lifecycle = ConferenceLifecycleFacts(
                conferenceActive = true,
                runtimePhase = ConferenceRuntimePhase.CONNECTING
            ),
            connectivity = ConferenceConnectivityFacts(channelReady = false),
            healthUi = ConferenceHealthUiProjection(
                roomFacing = ConferenceRoomFacing.NOT_ONLINE,
                l4RoomState = ConferenceL4RoomState.NOT_ESTABLISHED,
                recoveryInFlightDiagnostic = false
            )
        )
        assertFalse(display.live)
        assertTrue(display.mediaConnecting)
        assertTrue(display.showConnectingPanel)
        assertFalse(display.showLivePanel)
        assertEquals(ConferenceDisplayPhase.MEDIA_CONNECTING, display.phase)
        assertEquals(ConferenceStatusPillKind.CONNECTING, display.statusPill)
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
    fun overlayReturn_activeSessionStillReady_isLive() {
        val display = ConferenceDisplayStateResolver.resolve(
            lifecycle = ConferenceLifecycleFacts(
                conferenceActive = true,
                conferenceMode = true,
                runtimePhase = ConferenceRuntimePhase.ACTIVE
            ),
            connectivity = ConferenceConnectivityFacts(
                channelReady = true,
                awaitingRejoin = false
            ),
            healthUi = ConferenceHealthUiProjection(
                roomFacing = ConferenceRoomFacing.NOT_ONLINE,
                l4RoomState = ConferenceL4RoomState.NOT_ESTABLISHED,
                recoveryInFlightDiagnostic = false
            )
        )
        assertTrue(display.live)
        assertTrue(display.timerEligible)
        assertFalse(display.showConnectingPanel)
        assertEquals(ConferenceStatusPillKind.LIVE, display.statusPill)
    }

    @Test
    fun noActiveSession_isNotLive() {
        val display = ConferenceDisplayStateResolver.resolve(
            lifecycle = ConferenceLifecycleFacts(
                conferenceActive = false,
                conferenceMode = false
            ),
            connectivity = ConferenceConnectivityFacts(channelReady = true)
        )
        assertFalse(display.live)
        assertFalse(display.timerEligible)
        assertEquals(ConferenceDisplayPhase.INACTIVE, display.phase)
        assertEquals(ConferenceStatusPillKind.INACTIVE, display.statusPill)
    }

    @Test
    fun l4RoomDegraded_channelReady_staysLive() {
        val display = ConferenceDisplayStateResolver.resolve(
            lifecycle = ConferenceLifecycleFacts(
                conferenceActive = true,
                runtimePhase = ConferenceRuntimePhase.ACTIVE,
            ),
            connectivity = ConferenceConnectivityFacts(
                channelReady = true,
                reconnecting = false,
            ),
            healthUi = ConferenceHealthUiProjection(
                roomFacing = ConferenceRoomFacing.NOT_ONLINE,
                l4RoomState = ConferenceL4RoomState.DEGRADED,
                recoveryInFlightDiagnostic = true,
            ),
        )
        assertTrue(display.live)
        assertFalse(display.mediaConnecting)
        assertEquals(ConferenceStatusPillKind.LIVE, display.statusPill)
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
