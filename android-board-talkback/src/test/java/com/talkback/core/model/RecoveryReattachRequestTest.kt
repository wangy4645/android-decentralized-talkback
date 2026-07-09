package com.talkback.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class RecoveryReattachRequestTest {
    @Test
    fun toRejoinPayload_roundTrip() {
        val request = RecoveryReattachRequest(
            conferenceId = "CH-01",
            hostSessionId = "session-abc",
            membershipEpoch = 3L,
            endpointId = "E01"
        )
        val payload = request.toRejoinPayload()
        val decoded = ConferenceRejoinPayload.decode(payload.encode())
        assertNotNull(decoded)
        assertEquals(request, RecoveryReattachRequest.fromRejoinPayload(decoded!!))
    }

    @Test
    fun fromRejoinPayload_requiresHostSessionId() {
        val payload = ConferenceRejoinPayload(channelId = "CH-01", hostSessionId = "")
        assertNull(RecoveryReattachRequest.fromRejoinPayload(payload))
    }
}