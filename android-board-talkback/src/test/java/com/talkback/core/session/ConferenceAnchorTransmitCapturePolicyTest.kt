package com.talkback.core.session

import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.EndpointId
import com.talkback.core.model.ModuleId
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConferenceAnchorTransmitCapturePolicyTest {
    @Test
    fun defer_whenAnchorHostUnmuted() {
        val session =
            TalkbackSession(
                id = "s1",
                type = SessionType.CONFERENCE,
                local = EndpointAddress(EndpointId("E01"), ModuleId("M01")),
                channelId = "CH-01",
            ).apply {
                accepted = true
                mediaTopology = GroupMediaTopology.ANCHOR
                anchorModuleId = ModuleId("M01")
                muted = false
            }
        assertTrue(ConferenceAnchorTransmitCapturePolicy.deferPeerWebRtcCapture(session, ModuleId("M01")))
    }

    @Test
    fun noDefer_whenMutedOrNonAnchor() {
        val session =
            TalkbackSession(
                id = "s1",
                type = SessionType.CONFERENCE,
                local = EndpointAddress(EndpointId("E02"), ModuleId("M02")),
                channelId = "CH-01",
            ).apply {
                accepted = true
                mediaTopology = GroupMediaTopology.ANCHOR
                anchorModuleId = ModuleId("M01")
                muted = false
            }
        assertFalse(ConferenceAnchorTransmitCapturePolicy.deferPeerWebRtcCapture(session, ModuleId("M02")))
        session.muted = true
        assertFalse(ConferenceAnchorTransmitCapturePolicy.deferPeerWebRtcCapture(session, ModuleId("M01")))
    }
}
