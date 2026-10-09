package com.talkback.core.webrtc

import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.EndpointId
import com.talkback.core.model.ModuleId
import com.talkback.core.session.GroupMediaTopology
import com.talkback.core.session.SessionType
import com.talkback.core.session.TalkbackSession
import com.talkback.core.signaling.PeerTarget
import com.talkback.core.webrtc.conferenceaudio.ConferenceAudioRoutingView
import com.talkback.core.webrtc.conferenceaudio.ConferenceLocalMicFeed
import com.talkback.core.webrtc.conferenceaudio.StubLocalMicFrameSource
import com.talkback.core.webrtc.conferenceaudio.ConferenceAudioPathObservability
import com.talkback.core.webrtc.conferenceaudio.ParticipantMediaMode
import com.talkback.core.webrtc.conferenceaudio.PcmFrame
import com.talkback.core.webrtc.conferenceaudio.PcmInjectionFailure
import com.talkback.core.webrtc.conferenceaudio.RecordingPcmInjectionPort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class ConferenceAudioBusIntegrationTest {

    @Test
    fun localMode_anchorMicReachesAllTargets() {
        val h = harness(remotes = listOf("M02", "M03"))
        h.bus.updateRouting(h.view)
        assertEquals(ParticipantMediaMode.LOCAL_AND_REMOTE, h.view.anchorLocalMode)

        repeat(3) {
            h.bus.pushLocalMicrophoneFrame(SESSION_ID, PcmFrame.constantLevel(6_000))
        }
        assertTrue(h.port("M02").writtenFrames.isNotEmpty())
        assertTrue(h.port("M03").writtenFrames.isNotEmpty())
        assertTrue(h.peakInjected("M02") > 0)
        assertTrue(h.peakInjected("M03") > 0)
    }

    @Test
    fun remoteRelay_inboundMixedToOtherTargets() {
        val h = harness(remotes = listOf("M02", "M03"))
        h.bus.updateRouting(h.view)

        h.engine("M02").simulateInboundPcm(fill = 5_000)
        assertEquals(0, h.engine("M02").injectedProgramFrames.size)
        assertTrue(h.peakInjected("M03") > 0)
    }

    @Test
    fun localAndRemote_bothPathsContribute() {
        val h = harness(remotes = listOf("M02", "M03"))
        h.bus.updateRouting(h.view)

        h.bus.pushLocalMicrophoneFrame(SESSION_ID, PcmFrame.constantLevel(4_000))
        val micOnlyPeak = h.peakInjected("M02")

        h.engine("M03").simulateInboundPcm(fill = 4_000)
        val combinedPeak = h.peakInjected("M02")
        assertTrue(combinedPeak > 0)
        assertTrue(combinedPeak >= micOnlyPeak)
    }

    @Test
    fun anchorMode_localMicNotDisabledByProgramRelay() {
        val h = harness(remotes = listOf("M02"))
        h.bus.updateRouting(h.view)
        assertEquals(ParticipantMediaMode.LOCAL_AND_REMOTE, h.view.mediaModeFor("M01"))
        assertEquals(ProgramRelayMode.PROGRAM, h.engine("M02").programRelayMode)

        repeat(4) {
            h.bus.pushLocalMicrophoneFrame(SESSION_ID, PcmFrame.constantLevel(7_000))
            h.engine("M02").simulateInboundPcm(fill = 3_000)
        }
        assertTrue(h.peakInjected("M02") > 0)
    }

    @Test
    fun sourceJoin_noPopOnExistingTargets() {
        val h = harness(remotes = listOf("M02", "M03"))
        h.bus.updateRouting(h.view)
        repeat(4) {
            h.engine("M02").simulateInboundPcm(fill = 5_000)
        }
        val baseline = h.peakInjected("M03")

        val joined = h.view.copy(remoteParticipantIds = listOf("M02", "M03", "M04").sorted())
        h.ensureEngine("M04")
        h.bus.updateRouting(joined)
        repeat(3) {
            h.engine("M02").simulateInboundPcm(fill = 5_000)
        }
        h.engine("M04").simulateInboundPcm(fill = 6_000)
        val afterJoin = h.peakInjected("M03")
        assertTrue(afterJoin <= 32_767)
        assertTrue(afterJoin <= baseline * 4 + 12_000)
    }

    @Test
    fun sourceLeave_noPopOnRemainingTargets() {
        val h = harness(remotes = listOf("M02", "M03", "M04"))
        h.bus.updateRouting(h.view)
        repeat(4) {
            h.engine("M02").simulateInboundPcm(fill = 5_000)
            h.engine("M04").simulateInboundPcm(fill = 5_000)
        }
        val beforeLeave = h.peakInjected("M03")

        val shrunk = h.view.copy(remoteParticipantIds = listOf("M02", "M03").sorted())
        h.bus.updateRouting(shrunk)
        repeat(3) {
            h.engine("M02").simulateInboundPcm(fill = 5_000)
        }
        val afterLeave = h.peakInjected("M03")
        assertTrue(afterLeave <= 32_767)
        assertTrue(afterLeave <= beforeLeave * 2 + 8_000)
    }

    @Test
    fun sourceStarvation_doesNotMutateRoutingView() {
        val session = anchorSession(remotes = listOf("M02", "M03"))
        val expectedView = ConferenceAudioRoutingView.fromSession(session, ModuleId("M01"))!!
        val h = harness(remotes = listOf("M02", "M03"))
        h.bus.updateParticipants(session, ModuleId("M01"))

        h.engine("M02").simulateInboundPcm(fill = 4_000)
        assertEquals(expectedView, h.bus.routingView(SESSION_ID))

        h.engine("M03").simulateInboundPcm(fill = 3_000)
        assertEquals(expectedView, h.bus.routingView(SESSION_ID))
        assertTrue((h.bus.mixerStats(SESSION_ID, "M02")?.underruns ?: 0) >= 0)
    }

    @Test
    fun injectionFailure_observable() {
        val failures = mutableListOf<PcmInjectionFailure>()
        val h = harness(
            remotes = listOf("M02", "M03"),
            onInjectionFailure = { _, _, failure -> failures.add(failure) }
        )
        h.bus.updateRouting(h.view)
        h.port("M03").injectNextFailure(PcmInjectionFailure.INJECT_FAILED)
        h.engine("M02").simulateInboundPcm(fill = 5_000)
        assertTrue(failures.contains(PcmInjectionFailure.INJECT_FAILED))
    }

    @Test
    fun tenSources_mixerIntegrationStable() {
        val remotes = (2..11).map { "M%02d".format(it) }
        val h = harness(remotes = remotes)
        h.bus.updateRouting(h.view)
        remotes.forEach { id ->
            repeat(2) {
                h.engine(id).simulateInboundPcm(fill = 1_200)
            }
        }
        remotes.filter { it != "M11" }.forEach { target ->
            assertTrue("target $target", h.peakInjected(target) > 0)
            assertTrue(h.peakInjected(target) < 32_767)
        }
    }

    @Test
    fun localMicFeed_throughBus_reachesMixerTargets() {
        val h = harness(remotes = listOf("M02", "M03"))
        val micSource = StubLocalMicFrameSource()
        val observability = ConferenceAudioPathObservability()
        val feed = ConferenceLocalMicFeed(
            frameSource = micSource,
            pushFrame = { sessionId, frame -> h.bus.pushLocalMicrophoneFrame(sessionId, frame) },
            busDiagnostics = { sessionId -> h.bus.diagnostics(sessionId) },
            observability = observability
        )
        h.bus.updateRouting(h.view)
        val session = anchorSession(remotes = listOf("M02", "M03"))
        feed.syncSession(session, ModuleId("M01"), busActive = true)
        micSource.emitConstant(6_000)
        assertTrue(h.port("M02").writtenFrames.isNotEmpty())
        assertTrue(h.port("M03").writtenFrames.isNotEmpty())
    }

    @Test
    fun noTopologyWrite_sessionUnchangedAfterBusOps() {
        val session = anchorSession(remotes = listOf("M02", "M03"))
        val anchorBefore = session.anchorModuleId
        val topologyBefore = session.mediaTopology
        val peersBefore = session.remotePeersByModule.toMap()

        val h = harness(remotes = listOf("M02", "M03"))
        h.bus.updateParticipants(session, ModuleId("M01"))
        h.bus.pushLocalMicrophoneFrame(SESSION_ID, PcmFrame.constantLevel(5_000))
        h.engine("M02").simulateInboundPcm(fill = 4_000)
        h.bus.clear(SESSION_ID)

        assertEquals(anchorBefore, session.anchorModuleId)
        assertEquals(topologyBefore, session.mediaTopology)
        assertEquals(peersBefore, session.remotePeersByModule)
    }

    private fun harness(
        remotes: List<String>,
        onInjectionFailure: ((String, String, PcmInjectionFailure) -> Unit)? = null
    ): Harness {
        val engines = linkedMapOf<String, StubWebRtcAudioEngine>()
        val ports = linkedMapOf<String, RecordingPcmInjectionPort>()
        remotes.forEach { engines[it] = StubWebRtcAudioEngine() }
        val bus = ConferenceAudioBus(
            engineLookup = { engines[it] },
            injectionPortFactory = { engine ->
                val id = engines.entries.first { it.value === engine }.key
                ports.getOrPut(id) { RecordingPcmInjectionPort() }
            },
            onInjectionFailure = onInjectionFailure
        )
        return Harness(
            remotes = remotes,
            engines = engines,
            ports = ports,
            bus = bus,
            view = ConferenceAudioRoutingView(
                sessionId = SESSION_ID,
                localModuleId = "M01",
                anchorModuleId = "M01",
                remoteParticipantIds = remotes.sorted(),
                topologyMode = GroupMediaTopology.ANCHOR
            )
        )
    }

    private class Harness(
        val remotes: List<String>,
        val engines: MutableMap<String, StubWebRtcAudioEngine>,
        private val ports: MutableMap<String, RecordingPcmInjectionPort>,
        val bus: ConferenceAudioBus,
        val view: ConferenceAudioRoutingView
    ) {
        fun engine(id: String): StubWebRtcAudioEngine =
            engines.getOrPut(id) { StubWebRtcAudioEngine() }

        fun port(id: String): RecordingPcmInjectionPort = ports.getValue(id)

        fun ensureEngine(id: String) {
            engines.putIfAbsent(id, StubWebRtcAudioEngine())
        }

        fun peakInjected(targetId: String): Int {
            ports[targetId]?.writtenFrames?.lastOrNull()?.let { frame ->
                return frame.peakAbs()
            }
            val bytes = engines[targetId]?.injectedProgramFrames?.lastOrNull() ?: return 0
            return ConferenceAudioBusIntegrationTest.peakFromBytes(bytes)
        }
    }

    private companion object {
        private const val SESSION_ID = "conf-integration"

        fun peakFromBytes(bytes: ByteArray): Int {
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            var peak = 0
            while (buffer.hasRemaining()) {
                peak = maxOf(peak, kotlin.math.abs(buffer.short.toInt()))
            }
            return peak
        }
    }

    private fun anchorSession(remotes: List<String>): TalkbackSession {
        val local = EndpointAddress(ModuleId("M01"), EndpointId("E01"))
        return TalkbackSession(SESSION_ID, SessionType.CONFERENCE, local, "CH-01").apply {
            accepted = true
            mediaTopology = GroupMediaTopology.ANCHOR
            anchorModuleId = ModuleId("M01")
            remotes.forEach { remoteId ->
                remotePeersByModule[remoteId] = PeerTarget(host = "127.0.0.1", port = 9_002)
            }
        }
    }
}
