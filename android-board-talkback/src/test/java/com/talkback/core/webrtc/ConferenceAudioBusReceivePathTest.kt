package com.talkback.core.webrtc

import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.EndpointId
import com.talkback.core.model.ModuleId
import com.talkback.core.session.GroupMediaTopology
import com.talkback.core.session.SessionType
import com.talkback.core.session.TalkbackSession
import com.talkback.core.signaling.PeerTarget
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class ConferenceAudioBusReceivePathTest {

    @Test
    fun anchorInboundPcm_notifiesReceivePathObserver() {
        var now = 0L
        val observer = ReceivePathLivenessObserver(
            debounceMs = 500L,
            clock = { now }
        )
        val m01Engine = StubWebRtcAudioEngine()
        val m02Engine = StubWebRtcAudioEngine()
        val m03Engine = StubWebRtcAudioEngine()
        val engines = mapOf(
            "M01" to m01Engine,
            "M02" to m02Engine,
            "M03" to m03Engine
        )
        val bus = ConferenceAudioBus(
            engineLookup = { moduleId -> engines[moduleId] },
            onInboundPcm = { sessionId, sourceModuleId ->
                observer.onInboundPcm(sessionId, sourceModuleId)
            }
        )
        val session = anchorConferenceSession(
            sessionId = "conf-1",
            anchorId = "M01",
            remoteModuleIds = listOf("M02", "M03")
        )

        bus.updateParticipants(session, ModuleId("M01"))

        repeat(6) { step ->
            now = step * 100L
            m02Engine.simulateInboundPcm()
        }
        assertTrue(observer.receivePathLive("conf-1", "M02"))

        now = 1_200L
        assertFalse(observer.receivePathLive("conf-1", "M02"))
    }

    private fun anchorConferenceSession(
        sessionId: String,
        anchorId: String,
        remoteModuleIds: List<String>
    ): TalkbackSession {
        val local = EndpointAddress(
            moduleId = ModuleId(anchorId),
            endpointId = EndpointId("E01")
        )
        return TalkbackSession(sessionId, SessionType.CONFERENCE, local, "CH-01").apply {
            mediaTopology = GroupMediaTopology.ANCHOR
            anchorModuleId = ModuleId(anchorId)
            remoteModuleIds.forEach { remoteId ->
                remotePeersByModule[remoteId] = PeerTarget(
                    host = "127.0.0.1",
                    port = 9_002
                )
            }
        }
    }
}
