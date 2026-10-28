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

/** Morphology A — Top-K empty jitter + recent QUEUED ingress may soak to Layer-A playhead. */
class MorphologyATopKSparseEmptyBufferSoakDeskTest {
    private val sessionId = "morph-a-topk-sparse-soak-recent"
    private val baseSlot = 0xB_000
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
    fun topK_emptyBuffer_recentIngress_allowsSoakResyncTowardPlayhead() {
        val fact =
            SessionMediaWiringHarness.sessionFact(sessionId)
                .copy(startedAtMs = 1_700_000_300_000L)
        assertTrue(ConferenceSessionMediaBridge.startSession(fact))
        val remote = SessionMediaWiringHarness.memberBinding("M02")
        assertTrue(wiring.installMember(sessionId, remote))

        val wall0 = 1_700_000_320_000L
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
        for (i in 0 until 3) {
            val slot = baseSlot + i.toLong()
            val frame =
                pipeline.peekBufferedFrame(remote.sourceIdentity, remote.incarnationId, slot)
                    ?: error("missing frame $slot")
            pipeline.pullSlot(
                remote.sourceIdentity,
                remote.incarnationId,
                slot,
                frame.mediaTimeMs,
                wall0 + i * 20L,
            )
        }

        val stuckNext = baseSlot + 3L
        assertEquals(
            stuckNext,
            pipeline.nextExpectedSlot(remote.sourceIdentity, remote.incarnationId),
        )
        assertEquals(0, pipeline.jitterSize(remote.sourceIdentity, remote.incarnationId))

        orchestrator.selectTopK(wall0)
        val tickFar = wall0 + 8_000L
        val ingressWall = tickFar - 50L
        val liveSlot =
            baseSlot + ((tickFar - wall0) / MediaJitterConstants.MEDIA_SLOT_MS) - 2L
        assertEquals(
            FrameAdmitDisposition.QUEUED,
            wiring.admitProtectedDatagram(
                sessionId,
                tonePacket(remote, liveSlot.toInt()),
                ingressWall,
            ).frameAdmit,
        )
        assertEquals(
            ingressWall,
            wiring.lastQueuedIngressWallMsForHarness(sessionId, remote.sourceIdentity),
        )
        val liveFrame =
            pipeline.peekBufferedFrame(remote.sourceIdentity, remote.incarnationId, liveSlot)
        if (liveFrame != null) {
            pipeline.pullSlot(
                remote.sourceIdentity,
                remote.incarnationId,
                liveSlot,
                liveFrame.mediaTimeMs,
                ingressWall,
            )
        }
        assertEquals(0, pipeline.jitterSize(remote.sourceIdentity, remote.incarnationId))

        wiring.recoverEmptyBufferPlayoutCursors(sessionId, tickFar, tickFar)
        val nextAfter =
            pipeline.nextExpectedSlot(remote.sourceIdentity, remote.incarnationId)!!
        assertTrue(
            "Top-K with recent ingress should soak past B8-D1 hole cap (next=$nextAfter stuck=$stuckNext)",
            nextAfter > stuckNext + MediaJitterConstants.MAX_REORDER_PACKETS,
        )
    }

    @Test
    fun topK_emptyBuffer_withoutRecentIngress_stillBlocksBlindSoak() {
        val sessionId = "morph-a-topk-sparse-soak-no-recent"
        val fact =
            SessionMediaWiringHarness.sessionFact(sessionId)
                .copy(startedAtMs = 1_700_000_400_000L)
        assertTrue(ConferenceSessionMediaBridge.startSession(fact))
        val remote = SessionMediaWiringHarness.memberBinding("M02")
        assertTrue(wiring.installMember(sessionId, remote))

        val wall0 = 1_700_000_420_000L
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
        for (i in 0 until 3) {
            val slot = baseSlot + i.toLong()
            val frame =
                pipeline.peekBufferedFrame(remote.sourceIdentity, remote.incarnationId, slot)
                    ?: error("missing frame")
            pipeline.pullSlot(
                remote.sourceIdentity,
                remote.incarnationId,
                slot,
                frame.mediaTimeMs,
                wall0 + i * 20L,
            )
        }
        val stuckNext = baseSlot + 3L
        orchestrator.selectTopK(wall0)
        val tickFar = wall0 + 8_000L
        wiring.recoverEmptyBufferPlayoutCursors(sessionId, tickFar, tickFar)
        val nextAfter =
            pipeline.nextExpectedSlot(remote.sourceIdentity, remote.incarnationId)!!
        assertTrue(
            "B8-D1 preserved without recent ingress",
            nextAfter <= stuckNext + MediaJitterConstants.MAX_REORDER_PACKETS,
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
