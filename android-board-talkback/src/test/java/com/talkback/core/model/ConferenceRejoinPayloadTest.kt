package com.talkback.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class ConferenceRejoinPayloadTest {
    @Test
    fun encodeDecode_roundTrip() {
        val original = ConferenceRejoinPayload(
            channelId = "CH-01",
            hostSessionId = "session-abc"
        )
        val decoded = ConferenceRejoinPayload.decode(original.encode())
        assertNotNull(decoded)
        assertEquals(original, decoded)
    }
}
