package com.talkback.core.conference.session.integration

import com.talkback.core.conference.runtime.FrameAdmitDisposition
import com.talkback.core.conference.session.ConferenceSessionMediaBridge
import com.talkback.core.conference.session.ConferenceSessionMediaCoordinatorDelegate
import com.talkback.core.conference.session.ConferenceSessionMediaWiring
import com.talkback.core.conference.session.MemberBindingFact
import com.talkback.core.conference.session.SessionMediaWiringHarness
import com.talkback.core.conference.transport.Phase1MediaHarness
import com.talkback.core.conference.wire.WireIngressResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * GURGLE-DESK (1) — local SSRC on production [ConferenceSessionMediaWiring] path.
 *
 * Without [ConferenceSessionMediaCoordinatorDelegate.localModuleIdProvider], local moduleId
 * can reach jitter + mix (fail-open). With provider bound at [ConferenceSessionMediaWiring.startSession],
 * local is excluded from Top-K / decode / mix (A3-P).
 */
class Profile01GurgleLocalSelfMixDeskTest {
    private val localModuleId = "M02"
    private lateinit var wiring: ConferenceSessionMediaWiring
    private var wallMs: Long = 0L
    private var priorLocalModuleIdProvider: (() -> String)? = null

    @Before
    fun setUp() {
        priorLocalModuleIdProvider = ConferenceSessionMediaCoordinatorDelegate.localModuleIdProvider
        MeetingProductMediaShadow.enabled = true
        wiring = ConferenceSessionMediaWiring.forHarness()
        ConferenceSessionMediaBridge.wiring = wiring
        wallMs = 1_700_000_100_000L
    }

    @After
    fun tearDown() {
        ConferenceSessionMediaCoordinatorDelegate.localModuleIdProvider = priorLocalModuleIdProvider
        ConferenceSessionMediaBridge.wiring = null
    }

    @Test
    fun productionPath_withoutLocalProvider_localEligibleForMix() {
        ConferenceSessionMediaCoordinatorDelegate.localModuleIdProvider = null
        val sessionId = "gurgle-local-self-mix-desk-open"
        startSession(sessionId)

        val local = SessionMediaWiringHarness.memberBinding(localModuleId, ssrc = 0x22A00001)
        val remote = SessionMediaWiringHarness.memberBinding("M01", ssrc = 0x22A00002)
        assertTrue(wiring.installMember(sessionId, local))
        assertTrue(wiring.installMember(sessionId, remote))

        val mediaSlot = 5_000
        val localAdmit =
            wiring.admitProtectedDatagram(
                sessionId,
                tonePacket(local, mediaSlot),
                wallMs,
            )
        assertTrue(localAdmit.ingress is WireIngressResult.Accepted)
        assertEquals(FrameAdmitDisposition.QUEUED, localAdmit.frameAdmit)

        val pipeline = wiring.orchestrator(sessionId)!!.pipeline
        assertNotNull(
            pipeline.peekBufferedFrame(local.sourceIdentity, local.incarnationId, mediaSlot.toLong()),
        )

        wiring.admitProtectedDatagram(
            sessionId,
            tonePacket(remote, mediaSlot),
            wallMs + 1,
        )

        val buffered =
            wiring.resolvePlayoutMixSlot(sessionId, wallMs + 40)
                ?: error("expected buffered mix slot")
        val playout =
            wiring.runMixPlayoutCycle(
                sessionId,
                wallMs + 50,
                buffered.slot,
                buffered.slotMediaTimeMs,
                tickMediaTimeMs = wallMs + 50,
            )
                ?: error("expected playout result")

        assertTrue(
            "fail-open: local must remain in mix participants",
            localModuleId in playout.mixCycle.mixParticipantIdentities,
        )
        assertTrue(
            localModuleId in playout.mixCycle.topKIdentities,
        )
    }

    @Test
    fun productionPath_withLocalProviderBound_excludesSelfFromMix() {
        ConferenceSessionMediaCoordinatorDelegate.localModuleIdProvider = { localModuleId }
        val sessionId = "gurgle-local-self-mix-desk-excluded"
        startSession(sessionId)

        val local = SessionMediaWiringHarness.memberBinding(localModuleId, ssrc = 0x22A00011)
        val remote = SessionMediaWiringHarness.memberBinding("M03", ssrc = 0x22A00012)
        assertTrue(wiring.installMember(sessionId, local))
        assertTrue(wiring.installMember(sessionId, remote))

        val mediaSlot = 5_100
        wiring.admitProtectedDatagram(sessionId, tonePacket(local, mediaSlot), wallMs)
        wiring.admitProtectedDatagram(sessionId, tonePacket(remote, mediaSlot), wallMs + 1)

        val buffered =
            wiring.resolvePlayoutMixSlot(sessionId, wallMs + 60)
                ?: error("expected buffered mix slot")
        val playout =
            wiring.runMixPlayoutCycle(
                sessionId,
                wallMs + 70,
                buffered.slot,
                buffered.slotMediaTimeMs,
            )
                ?: error("expected playout result")

        assertTrue("remote must remain audible", "M03" in playout.mixCycle.mixParticipantIdentities)
        assertTrue(localModuleId !in playout.mixCycle.topKIdentities)
        assertTrue(localModuleId !in playout.mixCycle.mixParticipantIdentities)
    }

    private fun startSession(sessionId: String) {
        val fact = SessionMediaWiringHarness.sessionFact(sessionId).copy(startedAtMs = wallMs)
        assertTrue(ConferenceSessionMediaBridge.startSession(fact))
    }

    private fun tonePacket(
        binding: MemberBindingFact,
        mediaSlot: Int,
    ): ByteArray {
        val fixture = fixtureFrom(binding)
        return Phase1MediaHarness.buildToneProtectedPacket(fixture, mediaSlot)
    }

    private fun fixtureFrom(binding: MemberBindingFact): Phase1MediaHarness.SourceFixture =
        Phase1MediaHarness.SourceFixture(
            sourceIdentity = binding.sourceIdentity,
            incarnationId = binding.incarnationId,
            ssrc = binding.ssrc,
            sourceAdmissionKey48 = binding.sourceAdmissionKey48,
            audioLevel = 10,
            frequencyHz = 440.0,
        )
}
