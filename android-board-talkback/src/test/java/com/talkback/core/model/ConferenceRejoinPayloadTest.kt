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
            hostSessionId = "session-abc",
            membershipEpoch = 2L,
            endpointId = "E01",
            intent = ConferenceJoinIntent.RECOVERY_REATTACH
        )
        val decoded = ConferenceRejoinPayload.decode(original.encode())
        assertNotNull(decoded)
        assertEquals(original, decoded)
    }

    @Test
    fun decode_legacyPayload_defaultsToRecoveryIntent() {
        val legacy = """{"channelId":"CH-01","hostSessionId":"session-abc"}"""
        val decoded = ConferenceRejoinPayload.decode(legacy)
        assertNotNull(decoded)
        assertEquals(0L, decoded!!.membershipEpoch)
        assertEquals("", decoded.endpointId)
        assertEquals(ConferenceJoinIntent.RECOVERY_REATTACH, decoded.intent)
    }
}
