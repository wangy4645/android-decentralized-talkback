package com.talkback.appprod.ui

import com.talkback.core.session.ConferenceParticipantDisplayState
import org.junit.Assert.assertEquals
import org.junit.Test

class ConferenceEndpointStatusMapperTest {

    @Test
    fun reconnectingDisplayState_mapsToReconnecting() {
        assertEquals(
            EndpointStatus.RECONNECTING,
            ConferenceEndpointStatusMapper.map(
                displayState = ConferenceParticipantDisplayState.VISIBLE_RECONNECTING,
                speaking = false,
                isRecoveringPeer = false
            )
        )
    }

    @Test
    fun failedDisplayState_mapsToReconnecting_notOffline() {
        assertEquals(
            EndpointStatus.RECONNECTING,
            ConferenceEndpointStatusMapper.map(
                displayState = ConferenceParticipantDisplayState.VISIBLE_FAILED,
                speaking = false,
                isRecoveringPeer = false
            )
        )
    }

    @Test
    fun recoveringPeer_forcesReconnecting_evenWhenConnected() {
        assertEquals(
            EndpointStatus.RECONNECTING,
            ConferenceEndpointStatusMapper.map(
                displayState = ConferenceParticipantDisplayState.VISIBLE_CONNECTED,
                speaking = false,
                isRecoveringPeer = true
            )
        )
    }

    @Test
    fun recoveringPeer_doesNotOverrideSpeaking() {
        assertEquals(
            EndpointStatus.SPEAKING,
            ConferenceEndpointStatusMapper.map(
                displayState = ConferenceParticipantDisplayState.VISIBLE_CONNECTED,
                speaking = true,
                isRecoveringPeer = true
            )
        )
    }

    @Test
    fun connectingAfterEverConnected_mapsToReconnecting() {
        assertEquals(
            EndpointStatus.RECONNECTING,
            ConferenceEndpointStatusMapper.map(
                displayState = ConferenceParticipantDisplayState.VISIBLE_CONNECTING,
                speaking = false,
                isRecoveringPeer = false,
                wasEverConnected = true
            )
        )
    }

    @Test
    fun connectingFirstJoin_staysConnecting() {
        assertEquals(
            EndpointStatus.CONNECTING,
            ConferenceEndpointStatusMapper.map(
                displayState = ConferenceParticipantDisplayState.VISIBLE_CONNECTING,
                speaking = false,
                isRecoveringPeer = false,
                wasEverConnected = false
            )
        )
    }

    @Test
    fun connectingMediaUnavailable_mapsToReconnecting() {
        assertEquals(
            EndpointStatus.RECONNECTING,
            ConferenceEndpointStatusMapper.map(
                displayState = ConferenceParticipantDisplayState.VISIBLE_CONNECTING,
                speaking = false,
                isRecoveringPeer = false,
                mediaUnavailablePeer = true
            )
        )
    }

    @Test
    fun signalBars_reconnectingIsOne() {
        assertEquals(1, ConferenceEndpointStatusMapper.signalBarsFor(EndpointStatus.RECONNECTING))
    }
}
