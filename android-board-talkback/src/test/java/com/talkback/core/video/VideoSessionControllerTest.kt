package com.talkback.core.video

import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.EndpointId
import com.talkback.core.model.ModuleId
import com.talkback.core.model.SignalEnvelope
import com.talkback.core.model.SignalType
import com.talkback.core.model.VideoNegotiationPayload
import com.talkback.core.model.VideoPublishPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoSessionControllerTest {

    private val publisher = EndpointAddress(ModuleId("M02"), EndpointId("E01"))
    private val viewer = EndpointAddress(ModuleId("M03"), EndpointId("E01"))

    @Test
    fun authorityGrantsThenDeniesSecondPublisher() {
        val controller = VideoSessionController()
        val first = controller.onInbound(
            envelope(
                SignalType.VIDEO_PUBLISH,
                publisher,
                VideoPublishPayload("s1", 0L, "M02", authoritative = false).encode(),
            ),
            localIsMembershipAuthority = true,
        )
        assertTrue(first is VideoInboundResult.AcceptPublish)
        assertEquals(1L, (first as VideoInboundResult.AcceptPublish).canonical.videoGeneration)

        val second = controller.onInbound(
            envelope(
                SignalType.VIDEO_PUBLISH,
                EndpointAddress(ModuleId("M04"), EndpointId("E01")),
                VideoPublishPayload("s2", 0L, "M04", authoritative = false).encode(),
            ),
            localIsMembershipAuthority = true,
        )
        val deny = second as VideoInboundResult.DenyPublish
        assertEquals("M02", deny.deny.currentPublisherModuleId)
        assertEquals("M04", deny.deny.requesterModuleId)
    }

    @Test
    fun nonAuthorityIgnoresNonAuthoritativePublish() {
        val controller = VideoSessionController()
        val result = controller.onInbound(
            envelope(
                SignalType.VIDEO_PUBLISH,
                publisher,
                VideoPublishPayload("s1", 0L, "M02", authoritative = false).encode(),
            ),
            localIsMembershipAuthority = false,
        )
        assertEquals(VideoInboundResult.Ignored, result)
        assertEquals(null, controller.canonical)
    }

    @Test
    fun staleGenerationIceDropped() {
        val controller = VideoSessionController()
        controller.onInbound(
            envelope(
                SignalType.VIDEO_PUBLISH,
                publisher,
                VideoPublishPayload("s1", 4L, "M02", authoritative = true).encode(),
            ),
            localIsMembershipAuthority = false,
        )
        val offer = VideoNegotiationPayload(
            streamId = "s1",
            videoGeneration = 4L,
            negotiationId = "n1",
            publisherModuleId = "M02",
            viewerModuleId = "M03",
            sdp = "v=0",
        )
        val accepted = controller.onInbound(
            envelope(SignalType.VIDEO_OFFER, viewer, offer.encode()),
            localIsMembershipAuthority = false,
        )
        assertTrue(accepted is VideoInboundResult.AcceptNegotiation)

        val staleIce = controller.onInbound(
            envelope(
                SignalType.VIDEO_ICE,
                viewer,
                offer.copy(videoGeneration = 3L, candidate = "cand").encode(),
            ),
            localIsMembershipAuthority = false,
        )
        val drop = staleIce as VideoInboundResult.DropStale
        assertEquals("STALE_GENERATION", drop.reason)
        assertFalse(
            controller.hasNegotiation(
                VideoNegotiationKey("sess-1", "s1", 3L, "n1")
            )
        )
    }

    @Test
    fun grantAsAuthority_thenNoteNegotiation() {
        val controller = VideoSessionController()
        val grant = controller.grantAsAuthority("M02", "s1") as com.talkback.core.video.VideoPublishDecision.Grant
        val key = VideoNegotiationKey("sess-1", "s1", grant.canonical.videoGeneration, "n1")
        assertTrue(controller.noteNegotiation(key, "M03"))
        val deny = controller.grantAsAuthority("M04", "s2")
        assertTrue(deny is com.talkback.core.video.VideoPublishDecision.Deny)
    }

    @Test
    fun audioWebrtcNotAcceptedAsVideo() {
        val controller = VideoSessionController()
        val result = controller.onInbound(
            envelope(SignalType.WEBRTC_ICE, viewer, "candidate"),
            localIsMembershipAuthority = true,
        )
        assertEquals(VideoInboundResult.Ignored, result)
        assertTrue(VideoSignalingDemux.isAudioWebrtcSignal(SignalType.WEBRTC_ICE))
        assertFalse(VideoSignalingDemux.isVideoSignal(SignalType.WEBRTC_ICE))
        assertFalse(VideoSignalingDemux.isAudioWebrtcSignal(SignalType.VIDEO_ICE))
    }

    private fun envelope(type: SignalType, from: EndpointAddress, payload: String): SignalEnvelope =
        SignalEnvelope(
            type = type,
            from = from,
            to = publisher,
            sessionId = "sess-1",
            timestampMs = 1L,
            payload = payload,
            nonce = "n",
            signature = "",
        )
}
