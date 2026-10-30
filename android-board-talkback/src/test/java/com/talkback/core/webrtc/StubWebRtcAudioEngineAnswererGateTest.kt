package com.talkback.core.webrtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StubWebRtcAudioEngineAnswererGateTest {

    @Test
    fun impoliteRemoteOfferOnHaveLocalOffer_doesNotSettle() {
        val engine = StubWebRtcAudioEngine()
        engine.configureNegotiationState(
            signalingState = "HAVE_LOCAL_OFFER",
            localDescriptionType = "OFFER",
            rejectImpoliteRemoteOfferWithoutSettling = true
        )

        engine.applyRemoteOffer("v=0", polite = false)

        assertFalse(engine.justSettledAsAnswerer())
        assertEquals("HAVE_LOCAL_OFFER", engine.negotiationSnapshot().signalingState)
        assertEquals("OFFER", engine.negotiationSnapshot().localDescriptionType)
    }

    @Test
    fun impoliteRemoteOfferOnStable_doesNotSettle() {
        val engine = StubWebRtcAudioEngine()
        engine.configureNegotiationState(
            signalingState = "STABLE",
            rejectImpoliteRemoteOfferWithoutSettling = true
        )

        engine.applyRemoteOffer("v=0", polite = false)

        assertFalse(engine.justSettledAsAnswerer())
        assertEquals("STABLE", engine.negotiationSnapshot().signalingState)
    }

    @Test
    fun politeRemoteOfferOnHaveLocalOffer_settlesAfterRollback() {
        val engine = StubWebRtcAudioEngine()
        engine.configureNegotiationState(
            signalingState = "HAVE_LOCAL_OFFER",
            localDescriptionType = "OFFER",
            rejectImpoliteRemoteOfferWithoutSettling = true
        )

        engine.applyRemoteOffer("v=0", polite = true)

        assertTrue(engine.justSettledAsAnswerer())
        assertEquals("STABLE", engine.negotiationSnapshot().signalingState)
    }
}
