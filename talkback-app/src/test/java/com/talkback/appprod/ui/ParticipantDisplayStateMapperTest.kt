package com.talkback.appprod.ui

import com.talkback.core.session.ConferenceMembershipLifecycle
import com.talkback.core.session.ConferenceParticipantDisplayState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ParticipantDisplayStateMapperTest {

    @Test
    fun case1_recoveringWithReceivePathLive_mapsOnline() {
        val state = ParticipantDisplayStateMapper.map(
            input(
                recovering = true,
                displayState = ConferenceParticipantDisplayState.VISIBLE_CONNECTED,
                everConnected = true,
                receivePathLive = true
            )
        )
        assertEquals(ParticipantDisplayStateMapper.ParticipantDisplayState.ONLINE, state)
    }

    @Test
    fun case2_recoveringWithPlaybackUnavailable_mapsReconnecting() {
        val input = input(
            recovering = true,
            displayState = ConferenceParticipantDisplayState.VISIBLE_RECONNECTING,
            everConnected = true,
            receivePathLive = false
        )
        assertEquals(ParticipantDisplayStateMapper.ParticipantDisplayState.RECONNECTING, ParticipantDisplayStateMapper.map(input))
    }

    @Test
    fun case3_playbackUnavailableWithoutRecovering_mapsReconnecting() {
        val input = input(
            recovering = false,
            displayState = ConferenceParticipantDisplayState.VISIBLE_RECONNECTING,
            everConnected = true,
            receivePathLive = false
        )
        assertEquals(ParticipantDisplayStateMapper.ParticipantDisplayState.RECONNECTING, ParticipantDisplayStateMapper.map(input))
    }

    @Test
    fun receivePathLiveFalseWhileVisibleConnected_mapsReconnectingWhenEverConnected() {
        val input = input(
            displayState = ConferenceParticipantDisplayState.VISIBLE_CONNECTED,
            everConnected = true,
            receivePathLive = false
        )
        assertFalse(input.receivePathLive)
        assertEquals(ParticipantDisplayStateMapper.ParticipantDisplayState.RECONNECTING, ParticipantDisplayStateMapper.map(input))
    }

    @Test
    fun leftMembership_mapsLeft() {
        val input = input(
            membership = ConferenceMembershipLifecycle.LEFT,
            displayState = ConferenceParticipantDisplayState.VISIBLE_CONNECTED,
            everConnected = true,
            receivePathLive = true
        )
        assertEquals(ParticipantDisplayStateMapper.ParticipantDisplayState.LEFT, ParticipantDisplayStateMapper.map(input))
    }

    private fun input(
        recovering: Boolean = false,
        displayState: ConferenceParticipantDisplayState,
        everConnected: Boolean,
        mediaUnavailable: Boolean = false,
        membership: ConferenceMembershipLifecycle = ConferenceMembershipLifecycle.JOINED,
        receivePathLive: Boolean = false
    ) = ParticipantDisplayStateMapper.Input(
        membership = membership,
        displayState = displayState,
        everConnected = everConnected,
        mediaUnavailable = mediaUnavailable,
        recovering = recovering,
        receivePathLive = receivePathLive
    )
}
