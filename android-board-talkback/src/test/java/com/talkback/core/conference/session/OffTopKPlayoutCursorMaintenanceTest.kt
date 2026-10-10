package com.talkback.core.conference.session

import com.talkback.core.conference.runtime.FrameAdmitDisposition
import com.talkback.core.conference.runtime.MediaJitterConstants
import com.talkback.core.conference.runtime.VoiceLevelObservation
import com.talkback.core.conference.session.integration.MeetingProductMediaShadow
import com.talkback.core.conference.transport.Phase1MediaHarness
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * RCA5-B7 — off-Top-K maintenance must not consume still-playable frames; only late-drop
 * expired frames at the playout cursor so Top-K playback is not starved of valid audio.
 */
class OffTopKPlayoutCursorMaintenanceTest {
    private val sessionId = "rca5b7-off-topk-session"
    private val baseSlot = 0x8_000
    private lateinit var wiring: ConferenceSessionMediaWiring

    @Before
    fun setUp() {
        MeetingProductMediaShadow.enabled = true
        wiring = ConferenceSessionMediaWiring.forHarness()
        ConferenceSessionMediaBridge.wiring = wiring
    }

    @After
    fun tearDown() {
        ConferenceSessionMediaBridge.wiring = null
    }

    @Test
    fun maintainOffTopKPlayoutCursors_doesNotAdvance_whenBufferedFramesStillPlayable() {
        val fact =
            SessionMediaWiringHarness.sessionFact(sessionId)
                .copy(startedAtMs = 1_700_000_000_000L)
        assertTrue(ConferenceSessionMediaBridge.startSession(fact))
        val quiet = SessionMediaWiringHarness.memberBinding("M02")
        val loud = SessionMediaWiringHarness.memberBinding("M03")
        assertTrue(wiring.installMember(sessionId, quiet))
        assertTrue(wiring.installMember(sessionId, loud))

        val wall0 = 1_700_000_020_000L
        for (i in 0 until 6) {
            val admitted =
                wiring.admitProtectedDatagram(
                    sessionId,
                    tonePacket(quiet, baseSlot + i, audioLevel = 1),
                    wall0 + i * 20L,
                )
            assertEquals(FrameAdmitDisposition.QUEUED, admitted.frameAdmit)
        }
        observeTopKQuietVsLoud(quiet, loud)

        val pipeline = wiring.orchestrator(sessionId)?.pipeline ?: error("pipeline")
        val before =
            pipeline.nextExpectedSlot(quiet.sourceIdentity, quiet.incarnationId)
        assertEquals(baseSlot.toLong(), before)

        val stillPlayableNow = wall0 + 50L
        wiring.maintainOffTopKPlayoutCursors(
            sessionId,
            tickMediaTimeMs = stillPlayableNow,
            nowMs = stillPlayableNow,
        )

        val after =
            pipeline.nextExpectedSlot(quiet.sourceIdentity, quiet.incarnationId)
        assertEquals(before, after)
    }

    @Test
    fun maintainOffTopKPlayoutCursors_discardsExpiredOnly_whenSourceNotInTopK() {
        val fact =
            SessionMediaWiringHarness.sessionFact(sessionId)
                .copy(startedAtMs = 1_700_000_000_000L)
        assertTrue(ConferenceSessionMediaBridge.startSession(fact))
        val quiet = SessionMediaWiringHarness.memberBinding("M02")
        val loud = SessionMediaWiringHarness.memberBinding("M03")
        assertTrue(wiring.installMember(sessionId, quiet))
        assertTrue(wiring.installMember(sessionId, loud))

        val wall0 = 1_700_000_020_000L
        for (i in 0 until 6) {
            val admitted =
                wiring.admitProtectedDatagram(
                    sessionId,
                    tonePacket(quiet, baseSlot + i, audioLevel = 1),
                    wall0 + i * 20L,
                )
            assertEquals(FrameAdmitDisposition.QUEUED, admitted.frameAdmit)
        }
        observeTopKQuietVsLoud(quiet, loud)

        val pipeline = wiring.orchestrator(sessionId)?.pipeline ?: error("pipeline")
        val lateWall =
            wall0 +
                MediaJitterConstants.MAX_PLAYOUT_DELAY_MS +
                6 * MediaJitterConstants.MEDIA_SLOT_MS +
                50L

        wiring.maintainOffTopKPlayoutCursors(
            sessionId,
            tickMediaTimeMs = lateWall,
            nowMs = lateWall,
            maxSlotsPerSource = MediaJitterConstants.MAX_REORDER_PACKETS,
        )

        val after =
            pipeline.nextExpectedSlot(quiet.sourceIdentity, quiet.incarnationId)
        assertTrue(
            "expired prefix should be discarded off-Top-K",
            after != null && after!! > baseSlot.toLong(),
        )
    }

    private fun observeTopKQuietVsLoud(
        quiet: MemberBindingFact,
        loud: MemberBindingFact,
    ) {
        val orchestrator = wiring.orchestrator(sessionId) ?: error("orchestrator")
        orchestrator.selection.observeVoice(
            VoiceLevelObservation(
                sourceIdentity = quiet.sourceIdentity,
                incarnationId = quiet.incarnationId,
                voiceActive = false,
                audioLevel = 1,
            ),
        )
        orchestrator.selection.observeVoice(
            VoiceLevelObservation(
                sourceIdentity = loud.sourceIdentity,
                incarnationId = loud.incarnationId,
                voiceActive = true,
                audioLevel = 90,
            ),
        )
    }

    private fun tonePacket(
        binding: MemberBindingFact,
        mediaSlot: Int,
        audioLevel: Int,
    ): ByteArray {
        val fixture =
            Phase1MediaHarness.SourceFixture(
                sourceIdentity = binding.sourceIdentity,
                incarnationId = binding.incarnationId,
                ssrc = binding.ssrc,
                sourceAdmissionKey48 = binding.sourceAdmissionKey48,
                audioLevel = audioLevel,
                frequencyHz = 440.0,
            )
        return Phase1MediaHarness.buildToneProtectedPacket(fixture, mediaSlot)
    }
}
