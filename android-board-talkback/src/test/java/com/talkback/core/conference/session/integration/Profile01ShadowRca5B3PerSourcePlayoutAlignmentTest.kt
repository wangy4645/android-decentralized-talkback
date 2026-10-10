package com.talkback.core.conference.session.integration

import com.talkback.core.conference.runtime.FrameAdmitDisposition
import com.talkback.core.conference.session.ConferenceSessionMediaBridge
import com.talkback.core.conference.session.ConferenceSessionMediaWiring
import com.talkback.core.conference.session.SessionMediaWiringHarness
import com.talkback.core.conference.transport.Phase1MediaHarness
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * RCA5-B3 — foreign sources use per-incarnation RTP slot domain at playout resolve/pull,
 * not the session anchor slot as a universal mix index.
 */
class Profile01ShadowRca5B3PerSourcePlayoutAlignmentTest {
    private val sessionId = "rca5b3-per-source-session"
    private var firstMediaWallMs: Long = 0L
    private lateinit var wiring: ConferenceSessionMediaWiring

    @Before
    fun setUp() {
        MeetingProductMediaShadow.enabled = true
        wiring = ConferenceSessionMediaWiring.forHarness()
        ConferenceSessionMediaBridge.wiring = wiring
        firstMediaWallMs = System.currentTimeMillis()
    }

    @After
    fun tearDown() {
        ConferenceSessionMediaBridge.wiring = null
    }

    @Test
    fun resolvePlayoutMixSlot_mapsEachSourceToItsOwnRtpDomain() {
        startSession()
        val highBase = 0x9000
        val lowBase = 0x1000
        val bindingHigh = installMember("M-HIGH")
        val bindingLow = installMember("M-LOW")

        val wall = firstMediaWallMs
        assertEquals(
            FrameAdmitDisposition.QUEUED,
            wiring.admitProtectedDatagram(sessionId, tonePacket(bindingHigh, highBase), wall).frameAdmit,
        )
        assertEquals(
            FrameAdmitDisposition.QUEUED,
            wiring.admitProtectedDatagram(sessionId, tonePacket(bindingLow, lowBase), wall).frameAdmit,
        )
        assertEquals(
            FrameAdmitDisposition.QUEUED,
            wiring.admitProtectedDatagram(sessionId, tonePacket(bindingHigh, highBase + 1), wall + 20L).frameAdmit,
        )
        assertEquals(
            FrameAdmitDisposition.QUEUED,
            wiring.admitProtectedDatagram(sessionId, tonePacket(bindingLow, lowBase + 1), wall + 20L).frameAdmit,
        )

        val resolved = wiring.resolvePlayoutMixSlot(sessionId, wall + 20L)
        assertNotNull(resolved)
        assertEquals(highBase + 1L, resolved!!.slot)
        assertEquals(lowBase.toLong(), resolved.perSourceSlots[bindingLow.sourceIdentity])
        assertEquals(highBase.toLong(), resolved.perSourceSlots[bindingHigh.sourceIdentity])

        val playout =
            wiring.runMixPlayoutCycle(
                sessionId,
                wall + 40L,
                resolved.slot,
                resolved.slotMediaTimeMs,
                resolved.perSourceSlots,
            )
        assertNotNull(playout)
        assertTrue(playout!!.mixCycle.mixParticipantIdentities.size >= 2)
    }

    private fun startSession() {
        val base = SessionMediaWiringHarness.sessionFact(sessionId)
        val fact = base.copy(startedAtMs = 1_700_000_000_000L)
        assertTrue(ConferenceSessionMediaBridge.startSession(fact))
    }

    private fun installMember(label: String): com.talkback.core.conference.session.MemberBindingFact {
        val binding = SessionMediaWiringHarness.memberBinding(label)
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
