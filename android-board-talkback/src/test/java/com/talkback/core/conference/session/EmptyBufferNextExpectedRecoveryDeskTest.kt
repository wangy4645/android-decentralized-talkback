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

/** B8-B desk — empty buffer at [nextExpected], playhead advances, ingress resumes (M04 shape). */
class EmptyBufferNextExpectedRecoveryDeskTest {
    private val sessionId = "b8b-empty-buffer-recovery"
    private val baseSlot = 0xA_000
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
    fun afterBufferDrainsToEmpty_nextQueuedFrameIsPlayableWhenSourceInTopK() {
        val fact =
            SessionMediaWiringHarness.sessionFact(sessionId)
                .copy(startedAtMs = 1_700_000_200_000L)
        assertTrue(ConferenceSessionMediaBridge.startSession(fact))
        val remote = SessionMediaWiringHarness.memberBinding("M02")
        assertTrue(wiring.installMember(sessionId, remote))

        val wall0 = 1_700_000_220_000L
        for (i in 0 until 3) {
            assertEquals(
                FrameAdmitDisposition.QUEUED,
                wiring.admitProtectedDatagram(
                    sessionId,
                    tonePacket(remote, baseSlot + i),
                    wall0 + i * 20L,
                ).frameAdmit,
            )
        }

        val orchestrator = wiring.orchestrator(sessionId) ?: error("orchestrator")
        orchestrator.selection.observeVoice(
            VoiceLevelObservation(
                sourceIdentity = remote.sourceIdentity,
                incarnationId = remote.incarnationId,
                voiceActive = true,
                audioLevel = 80,
            ),
        )
        val pipeline = orchestrator.pipeline
        val slot0 = baseSlot.toLong()
        val frame0 =
            pipeline.peekBufferedFrame(remote.sourceIdentity, remote.incarnationId, slot0)
        assertNotNull(frame0)

        pipeline.pullSlot(
            remote.sourceIdentity,
            remote.incarnationId,
            slot0,
            frame0!!.mediaTimeMs,
            wall0,
        )
        pipeline.pullSlot(
            remote.sourceIdentity,
            remote.incarnationId,
            baseSlot + 1L,
            wall0 + 20L,
            wall0 + 20L,
        )
        pipeline.pullSlot(
            remote.sourceIdentity,
            remote.incarnationId,
            baseSlot + 2L,
            wall0 + 40L,
            wall0 + 40L,
        )

        val nextSlot = baseSlot + 3L
        assertEquals(
            nextSlot,
            pipeline.nextExpectedSlot(remote.sourceIdentity, remote.incarnationId),
        )
        assertEquals(
            0,
            pipeline.bufferedSlots(remote.sourceIdentity, remote.incarnationId).size,
        )

        orchestrator.selectTopK(wall0)
        val tickAfterDrain = wall0 + 8_000L
        wiring.recoverEmptyBufferPlayoutCursors(sessionId, tickAfterDrain, tickAfterDrain)
        val nextAfter =
            pipeline.nextExpectedSlot(remote.sourceIdentity, remote.incarnationId)!!
        assertTrue(
            "B8-D1: Top-K empty jitter must not soak-resync to playhead",
            nextAfter <= nextSlot + MediaJitterConstants.MAX_REORDER_PACKETS,
        )

        val gapWall = wall0 + MediaJitterConstants.MAX_PLAYOUT_DELAY_MS + 100L
        val resumeSlot = nextAfter.toInt()
        assertEquals(
            FrameAdmitDisposition.QUEUED,
            wiring.admitProtectedDatagram(
                sessionId,
                tonePacket(remote, resumeSlot),
                gapWall,
            ).frameAdmit,
        )

        val resolved =
            wiring.resolvePlayoutMixSlot(sessionId, gapWall + 20L)
        assertNotNull("expected resolve after empty-buffer resume", resolved)
        assertTrue(
            resolved!!.perSourceSlots[remote.sourceIdentity] == resumeSlot.toLong() ||
                pipeline.peekBufferedFrame(
                    remote.sourceIdentity,
                    remote.incarnationId,
                    resumeSlot.toLong(),
                ) != null,
        )
    }

    /** B8-B + B8-D1 — off-Top-K empty jitter still soaks to Layer-A playhead on recover. */
    @Test
    fun afterBufferDrainsToEmpty_offTopKMember_stillSoakResyncsToPlayhead() {
        val fact =
            SessionMediaWiringHarness.sessionFact(sessionId)
                .copy(startedAtMs = 1_700_000_200_000L)
        assertTrue(ConferenceSessionMediaBridge.startSession(fact))
        val quiet = SessionMediaWiringHarness.memberBinding("M04")
        val speaker = SessionMediaWiringHarness.memberBinding("M02")
        assertTrue(wiring.installMember(sessionId, quiet))
        assertTrue(wiring.installMember(sessionId, speaker))

        val wall0 = 1_700_000_220_000L
        for (i in 0 until 2) {
            assertEquals(
                FrameAdmitDisposition.QUEUED,
                wiring.admitProtectedDatagram(
                    sessionId,
                    tonePacket(quiet, baseSlot + i),
                    wall0 + i * 20L,
                ).frameAdmit,
            )
        }
        wiring.orchestrator(sessionId)!!.selection.observeVoice(
            VoiceLevelObservation(
                sourceIdentity = speaker.sourceIdentity,
                incarnationId = speaker.incarnationId,
                voiceActive = true,
                audioLevel = 90,
            ),
        )
        wiring.orchestrator(sessionId)!!.selection.observeVoice(
            VoiceLevelObservation(
                sourceIdentity = quiet.sourceIdentity,
                incarnationId = quiet.incarnationId,
                voiceActive = false,
                audioLevel = 1,
            ),
        )
        val pipeline = wiring.orchestrator(sessionId)!!.pipeline
        pipeline.pullSlot(quiet.sourceIdentity, quiet.incarnationId, baseSlot.toLong(), wall0, wall0)
        pipeline.pullSlot(
            quiet.sourceIdentity,
            quiet.incarnationId,
            baseSlot + 1L,
            wall0 + 20L,
            wall0 + 20L,
        )
        val nextSlot = baseSlot + 2L
        assertEquals(nextSlot, pipeline.nextExpectedSlot(quiet.sourceIdentity, quiet.incarnationId))
        assertEquals(0, pipeline.jitterSize(quiet.sourceIdentity, quiet.incarnationId))

        val tickFar = wall0 + 8_000L
        wiring.orchestrator(sessionId)!!.selectTopK(tickFar)
        wiring.recoverEmptyBufferPlayoutCursors(sessionId, tickFar, tickFar)
        val nextAfter = pipeline.nextExpectedSlot(quiet.sourceIdentity, quiet.incarnationId)!!
        assertTrue(
            "off-Top-K must still move cursor off stuck hole via soak",
            nextAfter > nextSlot + MediaJitterConstants.MAX_REORDER_PACKETS,
        )
    }

    private fun tonePacket(
        binding: MemberBindingFact,
        mediaSlot: Int,
    ): ByteArray {
        val fixture =
            Phase1MediaHarness.SourceFixture(
                sourceIdentity = binding.sourceIdentity,
                incarnationId = binding.incarnationId,
                ssrc = binding.ssrc,
                sourceAdmissionKey48 = binding.sourceAdmissionKey48,
                audioLevel = 80,
                frequencyHz = 440.0,
            )
        return Phase1MediaHarness.buildToneProtectedPacket(fixture, mediaSlot)
    }
}
