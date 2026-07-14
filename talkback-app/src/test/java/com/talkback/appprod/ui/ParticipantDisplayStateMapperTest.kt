package com.talkback.appprod.ui

import com.talkback.core.session.ConferenceMembershipLifecycle
import com.talkback.core.session.ConferenceParticipantDisplayState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ParticipantDisplayStateMapperTest {

    @Test
    fun case1_recoveringWithPlaybackReady_mapsOnline() {
        val state = ParticipantDisplayStateMapper.map(
            input(
                recovering = true,
                displayState = ConferenceParticipantDisplayState.VISIBLE_CONNECTED,
                everConnected = true
            )
        )
        assertEquals(ParticipantDisplayStateMapper.ParticipantDisplayState.ONLINE, state)
        assertTrue(
            ParticipantDisplayStateMapper.playbackReady(
                input(
                    recovering = true,
                    displayState = ConferenceParticipantDisplayState.VISIBLE_CONNECTED,
                    everConnected = true
                )
            )
        )
    }

    @Test
    fun case2_recoveringWithPlaybackUnavailable_mapsReconnecting() {
        val input = input(
            recovering = true,
            displayState = ConferenceParticipantDisplayState.VISIBLE_RECONNECTING,
            everConnected = true
        )
        assertEquals(ParticipantDisplayStateMapper.ParticipantDisplayState.RECONNECTING, ParticipantDisplayStateMapper.map(input))
    }

    @Test
    fun case3_playbackUnavailableWithoutRecovering_mapsReconnecting() {
        val input = input(
            recovering = false,
            displayState = ConferenceParticipantDisplayState.VISIBLE_RECONNECTING,
            everConnected = true
        )
        assertEquals(ParticipantDisplayStateMapper.ParticipantDisplayState.RECONNECTING, ParticipantDisplayStateMapper.map(input))
    }

    @Test
    fun playbackReady_requiresVisibleConnected_notIceAlone() {
        val input = input(
            displayState = ConferenceParticipantDisplayState.VISIBLE_CONNECTING,
            everConnected = true
        )
        assertFalse(ParticipantDisplayStateMapper.playbackReady(input))
    }

    @Test
    fun leftMembership_mapsLeft() {
        val input = input(
            membership = ConferenceMembershipLifecycle.LEFT,
            displayState = ConferenceParticipantDisplayState.VISIBLE_CONNECTED,
            everConnected = true
        )
        assertEquals(ParticipantDisplayStateMapper.ParticipantDisplayState.LEFT, ParticipantDisplayStateMapper.map(input))
    }

    private fun input(
        recovering: Boolean = false,
        displayState: ConferenceParticipantDisplayState,
        everConnected: Boolean,
        mediaUnavailable: Boolean = false,
        membership: ConferenceMembershipLifecycle = ConferenceMembershipLifecycle.JOINED
    ) = ParticipantDisplayStateMapper.Input(
        membership = membership,
        displayState = displayState,
        everConnected = everConnected,
        mediaUnavailable = mediaUnavailable,
        recovering = recovering
    )
}
