package com.talkback.core.conference.session.integration

import com.talkback.core.conference.runtime.FrameAdmitDisposition
import com.talkback.core.conference.session.ConferenceSessionMediaBridge
import com.talkback.core.conference.session.ConferenceSessionMediaFact
import com.talkback.core.conference.session.ConferenceSessionMediaWiring
import com.talkback.core.conference.session.SessionMediaWiringHarness
import com.talkback.core.conference.transport.Phase1MediaHarness
import com.talkback.core.conference.wire.WireIngressResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** RCA5-B2 — playout tick maps onto anchored RTP media-slot domain; sustained playout under burst ingress. */
class Profile01ShadowRca5B2SlotDomainAlignmentTest {
    private val sessionId = "rca5b2-align-session"
    private val baseMediaSlot = 0x1001
    private var sessionStartMs: Long = 0L
    private var firstMediaWallMs: Long = 0L
    private lateinit var wiring: ConferenceSessionMediaWiring

    @Before
    fun setUp() {
        MeetingProductMediaShadow.enabled = true
        wiring = ConferenceSessionMediaWiring.forHarness()
        ConferenceSessionMediaBridge.wiring = wiring
        firstMediaWallMs = System.currentTimeMillis()
        sessionStartMs = firstMediaWallMs - 840L
    }

    @After
    fun tearDown() {
        ConferenceSessionMediaBridge.wiring = null
    }

    @Test
    fun resolvePlayoutMixSlot_usesAnchoredMediaSlotNotSessionTickOffset() {
        startSession()
        val binding = installMember()
        val first =
            wiring.admitProtectedDatagram(
                sessionId,
                tonePacket(binding, baseMediaSlot),
                firstMediaWallMs,
            )
        assertEquals(FrameAdmitDisposition.QUEUED, first.frameAdmit)

        val target =
            wiring.resolvePlayoutMixSlot(sessionId, firstMediaWallMs)
        assertEquals(baseMediaSlot.toLong(), target?.slot)

        val nextTickTarget =
            wiring.resolvePlayoutMixSlot(sessionId, firstMediaWallMs + 40L)
        assertEquals(baseMediaSlot.toLong(), nextTickTarget?.slot)
    }

    @Test
    fun liveEdgeAligner_resyncsEmptyGapBeyondReorderWindow() {
        startSession()
        val binding = installMember()
        val first =
            wiring.admitProtectedDatagram(
                sessionId,
                tonePacket(binding, baseMediaSlot),
                firstMediaWallMs,
            )
        assertEquals(FrameAdmitDisposition.QUEUED, first.frameAdmit)
        wiring.runMixPlayoutCycle(
            sessionId,
            firstMediaWallMs + 20L,
            baseMediaSlot.toLong(),
            firstMediaWallMs,
        )

        val liveSlot = baseMediaSlot + 10
        val gapWallMs = firstMediaWallMs + (liveSlot - baseMediaSlot) * 20L
        val gapPacket =
            wiring.admitProtectedDatagram(
                sessionId,
                tonePacket(binding, liveSlot),
                gapWallMs,
            )
        assertEquals(FrameAdmitDisposition.QUEUED, gapPacket.frameAdmit)
    }

    private fun startSession() {
        val base = SessionMediaWiringHarness.sessionFact(sessionId)
        val fact = base.copy(startedAtMs = 1_700_000_000_000L)
        assertTrue(ConferenceSessionMediaBridge.startSession(fact))
    }

    private fun installMember(): com.talkback.core.conference.session.MemberBindingFact {
        val binding = SessionMediaWiringHarness.memberBinding("M01")
        assertTrue(wiring.installMember(sessionId, binding))
        return binding
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
