package com.talkback.core.conference.session

import com.talkback.core.conference.runtime.FrameAdmitDisposition
import com.talkback.core.conference.runtime.MediaJitterConstants
import com.talkback.core.conference.runtime.VoiceLevelObservation
import com.talkback.core.conference.session.integration.MeetingProductMediaShadow
import com.talkback.core.conference.transport.Phase1MediaHarness
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * RCA5-B7 — non-Top-K admitted sources must still advance playout cursor when buffered
 * at [nextExpected], otherwise nextExpected freezes until the source re-enters Top-K.
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
    fun maintainOffTopKPlayoutCursors_advancesNextExpected_whenSourceNotInTopK() {
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
        for (i in 0 until 2) {
            val admitted =
                wiring.admitProtectedDatagram(
                    sessionId,
                    tonePacket(loud, baseSlot + 100 + i, audioLevel = 90),
                    wall0 + i * 20L,
                )
            assertEquals(FrameAdmitDisposition.QUEUED, admitted.frameAdmit)
        }

        val orchestrator = wiring.orchestrator(sessionId) ?: error("orchestrator")
        val pipeline = orchestrator.pipeline
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

        val quietNextBefore =
            pipeline.nextExpectedSlot(quiet.sourceIdentity, quiet.incarnationId)
        assertEquals(baseSlot.toLong(), quietNextBefore)

        wiring.maintainOffTopKPlayoutCursors(
            sessionId,
            nowMs = wall0 + 200L,
            maxSlotsPerSource = MediaJitterConstants.MAX_REORDER_PACKETS,
        )

        val quietNextAfter =
            pipeline.nextExpectedSlot(quiet.sourceIdentity, quiet.incarnationId)
        assertNotNull(quietNextAfter)
        assertTrue(
            "quiet source should advance off-Top-K cursor",
            quietNextAfter!! >= baseSlot + MediaJitterConstants.MAX_REORDER_PACKETS.toLong(),
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
