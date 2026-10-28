package com.talkback.core.session

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConferencePeerReceivePlaybackEligibilityTest {

    private fun eligible(
        sessionGateOpen: Boolean = true,
        conferenceIceConnected: Boolean = true,
        hasRemoteAudioTrack: Boolean = true,
        rosterAdmitted: Boolean = true,
    ): Boolean =
        ConferencePeerReceivePlaybackEligibility.shouldEnablePeerPlayback(
            ConferencePeerReceivePlaybackEligibility.Input(
                sessionGateOpen = sessionGateOpen,
                conferenceIceConnected = conferenceIceConnected,
                hasRemoteAudioTrack = hasRemoteAudioTrack,
                rosterAdmitted = rosterAdmitted,
            )
        )

    @Test
    fun allConditionsMet_enablesPlayback() {
        assertTrue(eligible())
    }

    @Test
    fun sessionGateClosed_disablesPlayback() {
        assertFalse(eligible(sessionGateOpen = false))
    }

    @Test
    fun iceChecking_disablesPlayback() {
        assertFalse(eligible(conferenceIceConnected = false))
    }

    @Test
    fun iceDisconnected_disablesPlayback() {
        assertFalse(eligible(conferenceIceConnected = false))
    }

    @Test
    fun noRemoteTrack_disablesPlayback() {
        assertFalse(eligible(hasRemoteAudioTrack = false))
    }

    @Test
    fun peerRemovedFromRoster_disablesPlayback() {
        assertFalse(eligible(rosterAdmitted = false))
    }
}
