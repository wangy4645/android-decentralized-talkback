package com.talkback.core.conference.session

import com.talkback.core.conference.runtime.FrameAdmitDisposition
import com.talkback.core.conference.runtime.MediaJitterConstants
import com.talkback.core.conference.runtime.SlotPullDisposition
import com.talkback.core.conference.runtime.VoiceLevelObservation
import com.talkback.core.conference.session.integration.MeetingProductMediaShadow
import com.talkback.core.conference.transport.Phase1MediaHarness
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** B-V continuity desk — stale Top-K backlog must not false-resolve; tick recovery re-opens pull. */
class ContinuityDeskBvStaleBacklogTest {
    private val sessionId = "bv-continuity-stale-backlog"
    private val baseSlot = 0xD_000
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
    fun staleBufferedFrames_resolveDoesNotPickSlot_pastDeadline() {
        val fact =
            SessionMediaWiringHarness.sessionFact(sessionId)
                .copy(startedAtMs = 1_700_001_000_000L)
        assertTrue(ConferenceSessionMediaBridge.startSession(fact))
        val remote = SessionMediaWiringHarness.memberBinding("M04")
        assertTrue(wiring.installMember(sessionId, remote))

        val wall0 = 1_700_001_020_000L
        for (i in 0 until 6) {
            assertEquals(
                FrameAdmitDisposition.QUEUED,
                wiring.admitProtectedDatagram(
                    sessionId,
                    tonePacket(remote, baseSlot + i),
                    wall0 + i * 20L,
                ).frameAdmit,
            )
        }
        setVoice(remote, true, 90)
        wiring.orchestrator(sessionId)!!.selectTopK(wall0)

        val staleNow =
            wall0 + MediaJitterConstants.MAX_PLAYOUT_DELAY_MS + 2_000L
        val tick = staleNow + 40L
        assertNull(
            "must not resolve slots that pullSlot would drop as past usefulDeadline",
            wiring.resolvePlayoutMixSlot(sessionId, tick, staleNow),
        )
    }

    @Test
    fun afterStaleDiscardAndFreshIngress_topKMixProducesDecodeFrame() {
        val fact =
            SessionMediaWiringHarness.sessionFact(sessionId)
                .copy(startedAtMs = 1_700_001_100_000L)
        assertTrue(ConferenceSessionMediaBridge.startSession(fact))
        val remote = SessionMediaWiringHarness.memberBinding("M02")
        assertTrue(wiring.installMember(sessionId, remote))

        val wall0 = 1_700_001_120_000L
        for (i in 0 until 8) {
            assertEquals(
                FrameAdmitDisposition.QUEUED,
                wiring.admitProtectedDatagram(
                    sessionId,
                    tonePacket(remote, baseSlot + i),
                    wall0 + i * 20L,
                ).frameAdmit,
            )
        }
        setVoice(remote, true, 90)
        val pipeline = wiring.orchestrator(sessionId)!!.pipeline
        val staleNow =
            wall0 + MediaJitterConstants.MAX_PLAYOUT_DELAY_MS + 2_000L
        val tick = staleNow + 40L
        wiring.recoverEmptyBufferPlayoutCursors(sessionId, tick, staleNow)

        val nextSlot = pipeline.nextExpectedSlot(remote.sourceIdentity, remote.incarnationId)!!
        assertEquals(
            FrameAdmitDisposition.QUEUED,
            wiring.admitProtectedDatagram(
                sessionId,
                tonePacket(remote, nextSlot.toInt()),
                staleNow + 20L,
            ).frameAdmit,
        )
        for (i in 1 until 6) {
            assertEquals(
                FrameAdmitDisposition.QUEUED,
                wiring.admitProtectedDatagram(
                    sessionId,
                    tonePacket(remote, (nextSlot + i).toInt()),
                    staleNow + 20L + i * 20L,
                ).frameAdmit,
            )
        }

        val resolved =
            wiring.resolvePlayoutMixSlot(sessionId, tick + 80L, staleNow + 100L)
                ?: error("expected resolve after fresh ingress")
        val mixNow = staleNow + 100L
        val mix =
            wiring.runMixPlayoutCycle(
                sessionId,
                mixNow,
                resolved.slot,
                resolved.slotMediaTimeMs,
                resolved.perSourceSlots,
            ) ?: error("mix cycle")
        assertEquals(
            SlotPullDisposition.DECODE_FRAME,
            mix.mixCycle.sourcePullDispositions[remote.sourceIdentity],
        )
        assertTrue(mix.mixCycle.mixedBlock.samples.isNotEmpty())
    }

    private fun setVoice(
        binding: MemberBindingFact,
        voiceActive: Boolean,
        level: Int,
    ) {
        wiring.orchestrator(sessionId)?.selection?.observeVoice(
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
