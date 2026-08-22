package com.talkback.core.webrtc.conferenceaudio

import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.EndpointId
import com.talkback.core.model.ModuleId
import com.talkback.core.session.GroupMediaTopology
import com.talkback.core.session.SessionType
import com.talkback.core.session.TalkbackSession
import com.talkback.core.signaling.PeerTarget
import com.talkback.core.webrtc.ConferenceAudioBus
import com.talkback.core.webrtc.ProgramRelayMode
import com.talkback.core.webrtc.StubWebRtcAudioEngine
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class ConferenceProgramDownlinkObservabilityTest {

    private val logLines = mutableListOf<String>()
    private var now = 1_000L

    @Before
    fun setUp() {
        logLines.clear()
        now = 1_000L
        ConferenceProgramDownlinkLog.resetForTest { logLines.add(it) }
    }

    @After
    fun tearDown() {
        ConferenceProgramDownlinkLog.resetForTest()
    }

    @Test
    fun inboundMixSender_emitAbcOnceThenThrottle() {
        val m02 = StubWebRtcAudioEngine()
        val m04 = StubWebRtcAudioEngine()
        m02.setProgramRelayMode(ProgramRelayMode.PROGRAM)
        m04.setProgramRelayMode(ProgramRelayMode.PROGRAM)
        val engines = mapOf("M02" to m02, "M04" to m04)
        val bus = ConferenceAudioBus(
            engineLookup = { engines[it] },
            programDownlink = ConferenceProgramDownlinkObservability(
                clock = { now },
                emitMinIntervalMs = 1_000L
            )
        )
        val session = TalkbackSession(
            "conf-p0",
            SessionType.CONFERENCE,
            EndpointAddress(ModuleId("M01"), EndpointId("E01")),
            "CH-01"
        ).apply {
            mediaTopology = GroupMediaTopology.ANCHOR
            anchorModuleId = ModuleId("M01")
            remotePeersByModule["M02"] = PeerTarget("127.0.0.1", 9_002)
            remotePeersByModule["M04"] = PeerTarget("127.0.0.1", 9_004)
        }

        bus.updateParticipants(session, ModuleId("M01"))
        m02.simulateInboundPcm()
        m02.simulateInboundPcm()

        assertEquals(1, logLines.count { it.startsWith("CONFERENCE_PROGRAM_INBOUND ") })
        assertEquals(1, logLines.count { it.startsWith("CONFERENCE_PROGRAM_MIX ") })
        assertTrue(logLines.any { it.startsWith("CONFERENCE_PROGRAM_SENDER ") && it.contains("spoke=M04") })
        val inbound = logLines.first { it.startsWith("CONFERENCE_PROGRAM_INBOUND ") }
        assertTrue(inbound.contains("remoteSpoke=M02"))
        assertTrue(inbound.contains("inboundFrames="))
        assertTrue(inbound.contains("lastInboundTs=1000"))
        val sender = logLines.first { it.contains("spoke=M04") }
        assertTrue(sender.contains("currentTrackId=stub-program"))
        assertTrue(sender.contains("expectedTrackId=stub-program"))
        assertTrue(sender.contains("lastReplaceAt=none"))

        m02.simulateInboundPcm()
        assertEquals(1, logLines.count { it.startsWith("CONFERENCE_PROGRAM_INBOUND ") })

        now = 2_100L
        m02.simulateInboundPcm()
        assertEquals(2, logLines.count { it.startsWith("CONFERENCE_PROGRAM_INBOUND ") })
        assertTrue(logLines.last { it.startsWith("CONFERENCE_PROGRAM_INBOUND ") }.contains("inboundFrames=4"))
    }
}
