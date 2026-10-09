package com.talkback.core.webrtc.conferenceaudio

import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.EndpointId
import com.talkback.core.model.ModuleId
import com.talkback.core.session.GroupMediaTopology
import com.talkback.core.session.SessionType
import com.talkback.core.session.TalkbackSession
import com.talkback.core.signaling.PeerTarget
import com.talkback.core.webrtc.conferenceaudio.StubLocalMicFrameSource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ConferenceLocalMicFeedTest {
    private lateinit var observability: ConferenceAudioPathObservability
    private lateinit var micSource: StubLocalMicFrameSource
    private val pushedFrames = mutableListOf<String>()
    private var updateRoutingCalls = 0
    private lateinit var bus: TrackingConferenceAudioBus
    private lateinit var feed: ConferenceLocalMicFeed

    @Before
    fun setUp() {
        ConferenceAudioPathLog.resetForTest { }
        observability = ConferenceAudioPathObservability()
        micSource = StubLocalMicFrameSource()
        pushedFrames.clear()
        updateRoutingCalls = 0
        bus = TrackingConferenceAudioBus(
            onUpdateRouting = { updateRoutingCalls++ },
            onPush = { sessionId, _ -> pushedFrames.add(sessionId) }
        )
        feed = ConferenceLocalMicFeed(
            frameSource = micSource,
            pushFrame = { sessionId, frame -> bus.pushLocalMicrophoneFrame(sessionId, frame) },
            busDiagnostics = { sessionId -> bus.diagnostics(sessionId) },
            observability = observability
        )
    }

    @After
    fun tearDown() {
        ConferenceAudioPathLog.resetForTest()
    }

    @Test
    fun anchorLocalAndRemote_micReachesMixer() {
        val session = anchorSession(anchorId = "M01", localId = "M01")
        bus.activate(session)

        feed.syncSession(session, ModuleId("M01"), busActive = true)
        micSource.emitConstant()

        assertEquals(listOf(session.id), pushedFrames)
        assertTrue(feed.isFeeding(session.id))
    }

    @Test
    fun nonAnchor_doesNotFeed() {
        val session = anchorSession(anchorId = "M02", localId = "M01")
        bus.activate(session)

        feed.syncSession(session, ModuleId("M01"), busActive = true)
        micSource.emitConstant()

        assertTrue(pushedFrames.isEmpty())
        assertFalse(feed.isFeeding(session.id))
    }

    @Test
    fun hostNotAnchor_micGatingFollowsAnchorModuleId() {
        val session = anchorSession(anchorId = "M02", localId = "M01").apply {
            initiatorModuleId = ModuleId("M01")
        }

        feed.syncSession(session, ModuleId("M01"), busActive = true)
        micSource.emitConstant()

        assertTrue(pushedFrames.isEmpty())
        val fact = observability.recordedFacts().last()
        assertFalse(fact.localMicActive)
        assertEquals(ParticipantMediaMode.LOCAL, fact.participantMediaMode)
    }

    @Test
    fun muted_stopsPushWithoutBusRebuild() {
        val session = anchorSession(anchorId = "M01", localId = "M01")
        bus.activate(session)
        feed.syncSession(session, ModuleId("M01"), busActive = true)
        micSource.emitConstant()
        assertEquals(1, pushedFrames.size)

        session.muted = true
        feed.syncSession(session, ModuleId("M01"), busActive = true)
        pushedFrames.clear()
        micSource.emitConstant()

        assertTrue(pushedFrames.isEmpty())
        assertFalse(feed.isFeeding(session.id))
        assertEquals(1, updateRoutingCalls)
    }

    @Test
    fun unmuted_resumesPushWithoutExtraBusRebuild() {
        val session = anchorSession(anchorId = "M01", localId = "M01")
        bus.activate(session)
        feed.syncSession(session, ModuleId("M01"), busActive = true)

        session.muted = true
        feed.syncSession(session, ModuleId("M01"), busActive = true)
        session.muted = false
        feed.syncSession(session, ModuleId("M01"), busActive = true)

        pushedFrames.clear()
        micSource.emitConstant()
        assertEquals(1, pushedFrames.size)
        assertEquals(1, updateRoutingCalls)
    }

    @Test
    fun injectionFailure_emitsStructuredFact() {
        val session = anchorSession(anchorId = "M01", localId = "M01")
        bus.activate(session)
        feed.syncSession(session, ModuleId("M01"), busActive = true)

        feed.publishInjectionFailure(session, "M03", PcmInjectionFailure.INJECT_FAILED)

        val fact = observability.recordedFacts().last()
        assertTrue(fact.injectionFailure)
        assertEquals(PcmInjectionFailure.INJECT_FAILED, fact.failureReason)
        assertEquals("M03", fact.targetModuleId)
        assertEquals(session.id, fact.conferenceId)
        assertEquals("E01", fact.endpointId)
    }

    private fun anchorSession(anchorId: String, localId: String): TalkbackSession {
        val local = EndpointAddress(ModuleId(localId), EndpointId("E01"))
        return TalkbackSession("conf-feed", SessionType.CONFERENCE, local, "CH-01").apply {
            accepted = true
            mediaTopology = GroupMediaTopology.ANCHOR
            anchorModuleId = ModuleId(anchorId)
            remotePeersByModule["M02"] = PeerTarget("127.0.0.1", 9_002)
            remotePeersByModule["M03"] = PeerTarget("127.0.0.1", 9_003)
        }
    }

    private class TrackingConferenceAudioBus(
        private val onUpdateRouting: () -> Unit,
        private val onPush: (String, PcmFrame) -> Unit
    ) {
        private var diagnostics: ConferenceAudioBusDiagnostics? = null

        fun activate(session: TalkbackSession) {
            onUpdateRouting()
            diagnostics = ConferenceAudioBusDiagnostics(
                participantMediaMode = ParticipantMediaMode.LOCAL_AND_REMOTE,
                mixerSourceCount = 3,
                injectionPortOpen = true,
                activeTargetCount = 2
            )
        }

        fun pushLocalMicrophoneFrame(sessionId: String, frame: PcmFrame) {
            onPush(sessionId, frame)
        }

        fun diagnostics(sessionId: String): ConferenceAudioBusDiagnostics? = diagnostics
    }
}
