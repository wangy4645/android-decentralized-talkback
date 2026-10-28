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
 * M04 desk — B5 [LATE_FOR_PLAYOUT] on empty Top-K jitter must re-anchor and QUEUED (not stay LATE).
 */
class M04BufferEmptyLateRecoveryDeskTest {
    private val sessionId = "m04-late-recovery-desk"
    private val baseSlot = 0xE_000
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
    fun lateForPlayout_onEmptyJitter_recoversToQueued_andResolveSucceeds() {
        val fact =
            SessionMediaWiringHarness.sessionFact(sessionId)
                .copy(startedAtMs = 1_700_002_000_000L)
        assertTrue(ConferenceSessionMediaBridge.startSession(fact))
        val remote = SessionMediaWiringHarness.memberBinding("M01")
        assertTrue(wiring.installMember(sessionId, remote))

        val wall0 = 1_700_002_020_000L
        assertEquals(
            FrameAdmitDisposition.QUEUED,
            wiring.admitProtectedDatagram(
                sessionId,
                tonePacket(remote, baseSlot),
                wall0,
            ).frameAdmit,
        )
        val pipeline = wiring.orchestrator(sessionId)!!.pipeline
        pipeline.pullSlot(
            remote.sourceIdentity,
            remote.incarnationId,
            baseSlot.toLong(),
            wall0,
            wall0,
        )
        assertEquals(0, pipeline.jitterSize(remote.sourceIdentity, remote.incarnationId))

        wiring.orchestrator(sessionId)!!.selection.observeVoice(
            VoiceLevelObservation(
                sourceIdentity = remote.sourceIdentity,
                incarnationId = remote.incarnationId,
                voiceActive = true,
                audioLevel = 90,
            ),
        )
        val tickFar = wall0 + 8_000L
        wiring.recoverEmptyBufferPlayoutCursors(sessionId, tickFar, tickFar)

        val resumeSlot = (baseSlot + 1).toLong()
        val lateWall = tickFar + 20L
        val admitted =
            wiring.admitProtectedDatagram(
                sessionId,
                tonePacket(remote, resumeSlot.toInt()),
                lateWall,
            )
        assertEquals(
            "B5 must re-anchor empty jitter after LATE",
            FrameAdmitDisposition.QUEUED,
            admitted.frameAdmit,
        )
        assertTrue(pipeline.jitterSize(remote.sourceIdentity, remote.incarnationId) > 0)

        val resolved =
            wiring.resolvePlayoutMixSlot(sessionId, tickFar + 40L, lateWall + 10L)
        assertNotNull(resolved)
        assertTrue(resolved!!.perSourceSlots.containsKey(remote.sourceIdentity))
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
