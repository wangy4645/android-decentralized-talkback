package com.talkback.core.webrtc

import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.EndpointId
import com.talkback.core.model.ModuleId
import com.talkback.core.session.GroupMediaTopology
import com.talkback.core.session.SessionType
import com.talkback.core.session.TalkbackSession
import com.talkback.core.signaling.PeerTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** ADR-0056 Phase 1a-4.1 — ConferenceAudioBus must resolve CONFERENCE-scoped engines. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class ConferenceAudioBusEngineLookupTest {

    @Test
    fun conferenceScopedEngine_notVisibleViaGetGroup() {
        val registry = registry()
        registry.conferenceEngine("M02")
        assertNull(registry.getGroup("M02"))
        assertNotNull(registry.getConference("M02"))
    }

    @Test
    fun busWithConferenceLookup_wiresReceiveAndRelayPath() {
        val registry = registry()
        val m02 = registry.conferenceEngine("M02") as StubWebRtcAudioEngine
        val m03 = registry.conferenceEngine("M03") as StubWebRtcAudioEngine
        val bus = ConferenceAudioBus(engineLookup = registry::getConference)
        val session = anchorConferenceSession(remotes = listOf("M02", "M03"))

        bus.updateParticipants(session, ModuleId("M01"))

        m02.simulateInboundPcm(fill = 4_000)
        assertTrue(m03.injectedProgramFrames.isNotEmpty())
        assertEquals(ProgramRelayMode.PROGRAM, m03.programRelayMode)
    }

    private fun registry(): SessionMediaRegistry =
        SessionMediaRegistry(
            RuntimeEnvironment.getApplication(),
            useStub = true,
            onMeshIce = { _, _, _ -> },
            onUnicastIce = { _, _ -> }
        )

    private fun anchorConferenceSession(remotes: List<String>): TalkbackSession {
        val local = EndpointAddress(ModuleId("M01"), EndpointId("E01"))
        return TalkbackSession("conf-lookup", SessionType.CONFERENCE, local, "CH-01").apply {
            mediaTopology = GroupMediaTopology.ANCHOR
            anchorModuleId = ModuleId("M01")
            remotes.forEach { remoteId ->
                remotePeersByModule[remoteId] = PeerTarget(host = "127.0.0.1", port = 9_002)
            }
        }
    }
}
