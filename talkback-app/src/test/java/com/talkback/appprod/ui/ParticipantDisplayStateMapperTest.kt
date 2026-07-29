package com.talkback.appprod.ui

import com.talkback.core.session.ConferenceMembershipLifecycle
import com.talkback.core.session.ConferenceParticipantDisplayState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ParticipantDisplayStateMapperTest {

    @Test
    fun case1_recoveringWithReceivePathLive_mapsReconnecting_rule2() {
        // Rule 2 (session efe1d26d): edge recovering vetoes media liveness.
        val state = ParticipantDisplayStateMapper.map(
            input(
                recovering = true,
                displayState = ConferenceParticipantDisplayState.VISIBLE_CONNECTED,
                mediaEverLive = true,
                receivePathLive = true
            )
        )
        assertEquals(ParticipantDisplayStateMapper.ParticipantDisplayState.RECONNECTING, state)
    }

    @Test
    fun case2_recoveringWithPlaybackUnavailable_mapsReconnecting() {
        val input = input(
            recovering = true,
            displayState = ConferenceParticipantDisplayState.VISIBLE_RECONNECTING,
            mediaEverLive = true,
            receivePathLive = false
        )
        assertEquals(ParticipantDisplayStateMapper.ParticipantDisplayState.RECONNECTING, ParticipantDisplayStateMapper.map(input))
    }

    @Test
    fun case3_playbackUnavailableWithoutRecovering_mapsReconnecting() {
        val input = input(
            recovering = false,
            displayState = ConferenceParticipantDisplayState.VISIBLE_RECONNECTING,
            mediaEverLive = true,
            receivePathLive = false
        )
        assertEquals(ParticipantDisplayStateMapper.ParticipantDisplayState.RECONNECTING, ParticipantDisplayStateMapper.map(input))
    }

    @Test
    fun receivePathLiveFalseWhileVisibleConnected_mapsReconnectingWhenMediaEverLive() {
        val input = input(
            displayState = ConferenceParticipantDisplayState.VISIBLE_CONNECTED,
            mediaEverLive = true,
            receivePathLive = false
        )
        assertFalse(input.receivePathLive)
        assertEquals(ParticipantDisplayStateMapper.ParticipantDisplayState.RECONNECTING, ParticipantDisplayStateMapper.map(input))
    }

    @Test
    fun receivePathLiveFalseWithoutMediaEverLive_mapsJoining() {
        val input = input(
            displayState = ConferenceParticipantDisplayState.VISIBLE_CONNECTED,
            mediaEverLive = false,
            receivePathLive = false
        )
        assertEquals(ParticipantDisplayStateMapper.ParticipantDisplayState.JOINING, ParticipantDisplayStateMapper.map(input))
    }

    @Test
    fun leftMembership_mapsLeft() {
        val input = input(
            membership = ConferenceMembershipLifecycle.LEFT,
            displayState = ConferenceParticipantDisplayState.VISIBLE_CONNECTED,
            mediaEverLive = true,
            receivePathLive = true
        )
        assertEquals(ParticipantDisplayStateMapper.ParticipantDisplayState.LEFT, ParticipantDisplayStateMapper.map(input))
    }

    private fun input(
        recovering: Boolean = false,
        displayState: ConferenceParticipantDisplayState,
        mediaEverLive: Boolean,
        mediaUnavailable: Boolean = false,
        membership: ConferenceMembershipLifecycle = ConferenceMembershipLifecycle.JOINED,
        receivePathLive: Boolean = false
    ) = ParticipantDisplayStateMapper.Input(
        membership = membership,
        displayState = displayState,
        mediaEverLive = mediaEverLive,
        mediaUnavailable = mediaUnavailable,
        recovering = recovering,
        receivePathLive = receivePathLive
    )
}
