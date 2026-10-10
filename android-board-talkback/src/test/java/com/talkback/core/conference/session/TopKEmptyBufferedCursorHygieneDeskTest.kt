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
 * B8-A desk — Top-K empty / off-Top-K buffered hunger (ADR-0058 eligibility amendment).
 */
class TopKEmptyBufferedCursorHygieneDeskTest {
    private val sessionId = "b8a-topk-empty-buffered"
    private val baseSlot = 0x9_000
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
    fun topKEmpty_offTopKBuffered_releasesOnlyAfterExpiry_notIntoMix() {
        val fact =
            SessionMediaWiringHarness.sessionFact(sessionId)
                .copy(startedAtMs = 1_700_000_100_000L)
        assertTrue(ConferenceSessionMediaBridge.startSession(fact))
        val quiet = SessionMediaWiringHarness.memberBinding("M02")
        assertTrue(wiring.installMember(sessionId, quiet))

        val wall0 = 1_700_000_120_000L
        for (i in 0 until 8) {
            assertEquals(
                FrameAdmitDisposition.QUEUED,
                wiring.admitProtectedDatagram(
                    sessionId,
                    tonePacket(quiet, baseSlot + i, 1),
                    wall0 + i * 20L,
                ).frameAdmit,
            )
        }
        setVoice(quiet, voiceActive = false, level = 1)

        val orchestrator = wiring.orchestrator(sessionId) ?: error("orchestrator")
        orchestrator.selectTopK(wall0)
        assertTrue(orchestrator.selection.currentTopK().members.isEmpty())

        val pipeline = orchestrator.pipeline
        val before =
            pipeline.nextExpectedSlot(quiet.sourceIdentity, quiet.incarnationId)
        assertEquals(baseSlot.toLong(), before)

        wiring.maintainOffTopKPlayoutCursors(sessionId, wall0 + 40L, wall0 + 40L)
        assertEquals(before, pipeline.nextExpectedSlot(quiet.sourceIdentity, quiet.incarnationId))

        val late = wall0 + MediaJitterConstants.MAX_PLAYOUT_DELAY_MS + 200L
        wiring.maintainOffTopKPlayoutCursors(sessionId, late, late)
        val after =
            pipeline.nextExpectedSlot(quiet.sourceIdentity, quiet.incarnationId)
        assertNotNull(after)
        assertTrue(after!! > baseSlot.toLong())

        setVoice(quiet, voiceActive = true, level = 90)
        orchestrator.selectTopK(late)
        assertTrue(
            orchestrator.selection.currentTopK().members.any {
                it.sourceIdentity == quiet.sourceIdentity
            },
        )
    }

    @Test
    fun playheadCapBehindNext_releasesWithoutMix_whenStillInsideDeadline() {
        val fact =
            SessionMediaWiringHarness.sessionFact(sessionId)
                .copy(startedAtMs = 1_700_000_100_000L)
        assertTrue(ConferenceSessionMediaBridge.startSession(fact))
        val quiet = SessionMediaWiringHarness.memberBinding("M02")
        assertTrue(wiring.installMember(sessionId, quiet))

        val wall0 = 1_700_000_120_000L
        for (i in 0 until 4) {
            assertEquals(
                FrameAdmitDisposition.QUEUED,
                wiring.admitProtectedDatagram(
                    sessionId,
                    tonePacket(quiet, baseSlot + i, 1),
                    wall0 + i * 20L,
                ).frameAdmit,
            )
        }
        val pipeline = wiring.orchestrator(sessionId)?.pipeline ?: error("pipeline")
        val nowMs = wall0 + 30L
        val released =
            pipeline.releaseOffTopKNonMixPrefixBounded(
                quiet.sourceIdentity,
                quiet.incarnationId,
                nowMs = nowMs,
                playheadSlotCap = baseSlot - 1L,
                maxSlots = MediaJitterConstants.MAX_REORDER_PACKETS,
            )
        assertTrue(released >= 1)
        assertEquals(
            baseSlot + released.toLong(),
            pipeline.nextExpectedSlot(quiet.sourceIdentity, quiet.incarnationId),
        )
    }

    private fun setVoice(
        binding: MemberBindingFact,
        voiceActive: Boolean,
        level: Int,
    ) {
        val orchestrator = wiring.orchestrator(sessionId) ?: error("orchestrator")
        orchestrator.selection.observeVoice(
            VoiceLevelObservation(
                sourceIdentity = binding.sourceIdentity,
                incarnationId = binding.incarnationId,
                voiceActive = voiceActive,
                audioLevel = level,
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
