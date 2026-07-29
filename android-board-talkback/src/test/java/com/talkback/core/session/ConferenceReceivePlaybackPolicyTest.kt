package com.talkback.core.session

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConferenceReceivePlaybackPolicyTest {

    @Test
    fun acceptedConference_enablesReceivePlayback() {
        assertTrue(
            ConferenceReceivePlaybackPolicy.shouldEnableReceivePlayback(
                ConferenceReceivePlaybackPolicy.Input(
                    accepted = true,
                    foregroundSuspended = false
                )
            )
        )
    }

    @Test
    fun userMute_doesNotDisableReceivePlayback() {
        assertTrue(
            ConferenceReceivePlaybackPolicy.shouldEnableReceivePlayback(
                ConferenceReceivePlaybackPolicy.Input(
                    accepted = true,
                    foregroundSuspended = false
                )
            )
        )
    }

    @Test
    fun foregroundSuspended_disablesReceivePlayback() {
        assertFalse(
            ConferenceReceivePlaybackPolicy.shouldEnableReceivePlayback(
                ConferenceReceivePlaybackPolicy.Input(
                    accepted = true,
                    foregroundSuspended = true
                )
            )
        )
    }

    @Test
    fun notAccepted_disablesReceivePlayback() {
        assertFalse(
            ConferenceReceivePlaybackPolicy.shouldEnableReceivePlayback(
                ConferenceReceivePlaybackPolicy.Input(
                    accepted = false,
                    foregroundSuspended = false
                )
            )
        )
    }
}
