package com.talkback.core.session

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConferenceMediaTransmitGateTest {

    @Test
    fun healthyLocalPublisher_canPublish() {
        assertTrue(
            ConferenceMediaTransmitGate.canPublishConferenceAudio(
                ConferenceMediaTransmitGate.Input(
                    localConferenceActive = true,
                    localMuted = false,
                    localPublisherReady = true
                )
            )
        )
    }

    @Test
    fun conferenceInactive_cannotPublish() {
        assertFalse(
            ConferenceMediaTransmitGate.canPublishConferenceAudio(
                ConferenceMediaTransmitGate.Input(
                    localConferenceActive = false,
                    localMuted = false,
                    localPublisherReady = true
                )
            )
        )
    }

    @Test
    fun localMuted_cannotPublish() {
        assertFalse(
            ConferenceMediaTransmitGate.canPublishConferenceAudio(
                ConferenceMediaTransmitGate.Input(
                    localConferenceActive = true,
                    localMuted = true,
                    localPublisherReady = true
                )
            )
        )
    }

    @Test
    fun noPublishPath_cannotPublish() {
        assertFalse(
            ConferenceMediaTransmitGate.canPublishConferenceAudio(
                ConferenceMediaTransmitGate.Input(
                    localConferenceActive = true,
                    localMuted = false,
                    localPublisherReady = false
                )
            )
        )
    }
}
