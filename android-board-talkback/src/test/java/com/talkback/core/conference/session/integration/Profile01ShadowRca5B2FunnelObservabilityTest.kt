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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** RCA5-B2 — narrow funnel snapshot is behavior-neutral and exposes slot-domain state. */
class Profile01ShadowRca5B2FunnelObservabilityTest {
    private val sessionId = "rca5b2-session"
    private val anchorMs = 1_700_000_000_000L
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
    fun capturePlayoutFunnelSnapshot_reportsIngressJitterSlotDomain() {
        val fact = sessionFactWithAnchor()
        assertTrue(ConferenceSessionMediaBridge.startSession(fact))
        val binding = SessionMediaWiringHarness.memberBinding("M01")
        assertTrue(wiring.installMember(sessionId, binding))

        val baseMediaSlot = 0x2001
        val firstMediaWallMs = anchorMs + 40L
        val packet = tonePacket(binding, baseMediaSlot)
        val admit = wiring.admitProtectedDatagram(sessionId, packet, firstMediaWallMs)
        assertTrue(admit.ingress is WireIngressResult.Accepted)
        assertEquals(FrameAdmitDisposition.QUEUED, admit.frameAdmit)

        val pipeline = wiring.orchestrator(sessionId)!!.pipeline
        val jitter =
            pipeline.jitterSlotDomainSnapshot(binding.moduleId, binding.incarnationId)
        assertNotNull(jitter)
        assertEquals(1, jitter!!.bySlotSize)
        assertEquals(0x2001L, jitter.earliestBufferedSlot)
        assertEquals(0x2001L, jitter.latestBufferedSlot)
        assertEquals(0x2001L, jitter.nextExpectedSlot)

        val funnel =
            wiring.capturePlayoutFunnelSnapshot(
                sessionId = sessionId,
                tickMediaTimeMs = anchorMs + 60L,
            )
        assertNotNull(funnel)
        assertEquals(1, funnel!!.perSource.size)
        assertEquals("M01", funnel.perSource.single().sourceIdentity)
        assertEquals((baseMediaSlot + 1).toLong(), funnel.playoutTargetSlot)
    }

    private fun sessionFactWithAnchor(): ConferenceSessionMediaFact {
        val base = SessionMediaWiringHarness.sessionFact(sessionId)
        return base.copy(startedAtMs = anchorMs)
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
