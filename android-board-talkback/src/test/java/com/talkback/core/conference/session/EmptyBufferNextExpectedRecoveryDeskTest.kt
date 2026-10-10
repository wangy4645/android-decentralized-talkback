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
 * B8-B desk skeleton — empty buffer at [nextExpected] then resume ingress (M04 field shape).
 * Documents desired recovery; full B8-B implementation may extend aligner / EMPTY path.
 */
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

        val gapWall = wall0 + MediaJitterConstants.MAX_PLAYOUT_DELAY_MS + 100L
        assertEquals(
            FrameAdmitDisposition.QUEUED,
            wiring.admitProtectedDatagram(
                sessionId,
                tonePacket(remote, nextSlot.toInt()),
                gapWall,
            ).frameAdmit,
        )

        val resolved =
            wiring.resolvePlayoutMixSlot(sessionId, gapWall + 20L)
        assertNotNull("expected resolve after empty-buffer resume", resolved)
        assertTrue(
            resolved!!.perSourceSlots[remote.sourceIdentity] == nextSlot ||
                pipeline.peekBufferedFrame(
                    remote.sourceIdentity,
                    remote.incarnationId,
                    nextSlot,
                ) != null,
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
