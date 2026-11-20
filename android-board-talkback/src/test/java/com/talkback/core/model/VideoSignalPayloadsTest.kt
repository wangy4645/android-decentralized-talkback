package com.talkback.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoSignalPayloadsTest {

    @Test
    fun publishRoundTrip() {
        val original = VideoPublishPayload(
            streamId = "s1",
            videoGeneration = 2L,
            publisherModuleId = "M02",
            authoritative = true,
            codec = "H264",
            maxBitrateBps = 500_000,
        )
        assertEquals(original, VideoPublishPayload.decode(original.encode()))
    }

    @Test
    fun negotiationRoundTripKeepsIds() {
        val original = VideoNegotiationPayload(
            streamId = "s1",
            videoGeneration = 2L,
            negotiationId = "neg-9",
            publisherModuleId = "M02",
            viewerModuleId = "M03",
            sdp = "v=0",
            candidate = "cand",
        )
        val decoded = VideoNegotiationPayload.decode(original.encode())!!
        assertEquals("neg-9", decoded.negotiationId)
        assertEquals(2L, decoded.videoGeneration)
        assertEquals("M03", decoded.viewerModuleId)
    }

    @Test
    fun decodeRejectsBlankNegotiation() {
        assertEquals(null, VideoNegotiationPayload.decode("{}"))
        assertTrue(VideoPublishDenyPayload.decode(
            VideoPublishDenyPayload("s1", 1L, "M02", "M04").encode()
        ) != null)
    }
}
