package com.talkback.core.conference.session

import com.talkback.core.conference.runtime.FrameAdmitDisposition
import com.talkback.core.conference.session.integration.MeetingProductMediaShadow
import com.talkback.core.conference.transport.Phase1MediaHarness
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * RCA5-B4 — when Layer A playout projection lags behind [nextExpected], resolve must still
 * pick the next playable buffered slot (no resolve=null stall).
 */
class PlayoutMixSlotResolverConsumptionTest {
    private val sessionId = "rca5b4-consumption-session"
    private val baseSlot = 0x7_000
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
    fun resolvePlayoutMixSlot_playsAtNextExpected_whenWallProjectionLagsBehindCursor() {
        val fact =
            com.talkback.core.conference.session.SessionMediaWiringHarness.sessionFact(sessionId)
                .copy(startedAtMs = 1_700_000_000_000L)
        assertTrue(ConferenceSessionMediaBridge.startSession(fact))
        val binding = com.talkback.core.conference.session.SessionMediaWiringHarness.memberBinding("M02")
        assertTrue(wiring.installMember(sessionId, binding))

        val wall0 = 1_700_000_010_000L
        for (i in 0 until 8) {
            val admitted =
                wiring.admitProtectedDatagram(
                    sessionId,
                    tonePacket(binding, baseSlot + i),
                    wall0 + i * 20L,
                )
            assertEquals(FrameAdmitDisposition.QUEUED, admitted.frameAdmit)
        }

        val playoutTick = wall0 + 8 * 20L
        val first =
            wiring.resolvePlayoutMixSlot(sessionId, playoutTick)
                ?: error("expected first resolved slot")
        val mix =
            wiring.runMixPlayoutCycle(
                sessionId,
                playoutTick,
                first.slot,
                first.slotMediaTimeMs,
                first.perSourceSlots,
            )
        assertNotNull(mix)

        val pipeline = wiring.orchestrator(sessionId)?.pipeline ?: error("pipeline")
        val nextAfterOnePull =
            pipeline.nextExpectedSlot(binding.sourceIdentity, binding.incarnationId)
        assertNotNull(nextAfterOnePull)
        assertTrue(nextAfterOnePull!! > baseSlot.toLong())

        // Tick maps to an early Layer A target (behind cursor) but buffer still has playable frames.
        val laggingTick = wall0 + 20L
        val resolved =
            wiring.resolvePlayoutMixSlot(sessionId, laggingTick)
                ?: error("must not fail resolve when nextExpected is playable in buffer")
        assertEquals(
            nextAfterOnePull,
            resolved.perSourceSlots[binding.sourceIdentity],
        )
    }

    private fun tonePacket(
        binding: com.talkback.core.conference.session.MemberBindingFact,
        mediaSlot: Int,
    ): ByteArray {
        val fixture =
            Phase1MediaHarness.SourceFixture(
                sourceIdentity = binding.sourceIdentity,
                incarnationId = binding.incarnationId,
                ssrc = binding.ssrc,
                sourceAdmissionKey48 = binding.sourceAdmissionKey48,
                audioLevel = 10,
                frequencyHz = 440.0,
            )
        return Phase1MediaHarness.buildToneProtectedPacket(fixture, mediaSlot)
    }
}
