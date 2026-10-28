package com.talkback.core.conference.session.integration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Profile01MediaKeyHeadRepublishRequestTest {
    @Test
    fun encodeDecode_roundTrip() {
        val sessionId = "db117142-87f0-45e6-9c73-7eb78edc3bd9"
        val text = Profile01MediaKeyHeadRepublishRequest.encode(sessionId, 3L)
        val decoded = Profile01MediaKeyHeadRepublishRequest.decode(text)
        assertEquals(sessionId, decoded?.sessionId)
        assertEquals(3L, decoded?.mediaKeyEpoch)
    }

    @Test
    fun decode_rejectsNonControlText() {
        assertNull(Profile01MediaKeyHeadRepublishRequest.decode("hello"))
    }
}
