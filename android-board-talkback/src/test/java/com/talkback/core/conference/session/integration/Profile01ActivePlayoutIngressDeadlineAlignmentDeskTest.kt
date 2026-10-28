package com.talkback.core.conference.session.integration

import com.talkback.core.conference.runtime.FrameAdmitDisposition
import com.talkback.core.conference.runtime.MediaJitterConstants
import com.talkback.core.conference.session.ConferenceSessionMediaBridge
import com.talkback.core.conference.session.ConferenceSessionMediaWiring
import com.talkback.core.conference.session.MemberBindingFact
import com.talkback.core.conference.session.SessionMediaWiringHarness
import com.talkback.core.conference.transport.Phase1MediaHarness
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ADR-0058 F9 — Active Playout Ingress Deadline Alignment (Layer B).
 *
 * Field-isomorphic desk for Issue A A3 (session a9d84526…).
 */
class Profile01ActivePlayoutIngressDeadlineAlignmentDeskTest {

    private val sessionId = "f9-active-playout-deadline-desk"
    private lateinit var wiring: ConferenceSessionMediaWiring
    private lateinit var m01: MemberBindingFact
    private var wallMs: Long = 0L

    companion object {
        private const val FIELD_BASE_SEQ = 0x5000 // 20480
        private const val FIELD_SECOND_SEQ = FIELD_BASE_SEQ + 10 // 20490 @ ~527ms
        private const val FIELD_THIRD_SEQ = FIELD_BASE_SEQ + 26 // 20506 @ ~956ms
        private const val FIELD_FIRST_TO_SECOND_MS = 527L
        private const val FIELD_FIRST_TO_THIRD_MS = 956L
    }

    @Before
    fun setUp() {
        MeetingProductMediaShadow.enabled = true
        wiring = ConferenceSessionMediaWiring.forHarness()
        ConferenceSessionMediaBridge.wiring = wiring
        wallMs = 1_700_000_000_000L
        val fact = SessionMediaWiringHarness.sessionFact(sessionId).copy(startedAtMs = wallMs)
        assertTrue(ConferenceSessionMediaBridge.startSession(fact))
        m01 = SessionMediaWiringHarness.memberBinding("M01", incarnationId = 1L)
    }

    @After
    fun tearDown() {
        ConferenceSessionMediaBridge.wiring = null
    }

    /** Field-isomorphic A3 fixture — monotonic RTP under active playout stays eligible post-F9. */
    @Test
    fun reproducesFieldCounterexample() {
        assertTrue(wiring.installMember(sessionId, m01))
        runFieldIsomorphicIngressScenario()
    }

    /** F9 contract — in-order monotonic ingress under active playout. */
    @Test
    fun contract_inOrderPacketsRemainQueued() {
        assertTrue(wiring.installMember(sessionId, m01))
        runFieldIsomorphicIngressScenario()
    }

    private fun runFieldIsomorphicIngressScenario() {
        val first =
            wiring.admitProtectedDatagram(
                sessionId,
                tonePacket(m01, FIELD_BASE_SEQ),
                wallMs,
            )
        assertEquals(FrameAdmitDisposition.QUEUED, first.frameAdmit)

        advanceActivePlayout(FIELD_BASE_SEQ, playoutCycles = 1)

        feedMonotonicSlots(
            fromSeq = FIELD_BASE_SEQ + 1,
            toSeq = FIELD_BASE_SEQ + 2,
            startWallMs = wallMs + 40L,
        )

        advanceActivePlayout(FIELD_BASE_SEQ + 1, playoutCycles = 2)

        feedMonotonicSlots(
            fromSeq = FIELD_BASE_SEQ + 3,
            toSeq = FIELD_SECOND_SEQ - 1,
            startWallMs = wallMs + 100L,
        )

        val second =
            wiring.admitProtectedDatagram(
                sessionId,
                tonePacket(m01, FIELD_SECOND_SEQ),
                wallMs + FIELD_FIRST_TO_SECOND_MS,
            )
        assertEquals(
            "field-isomorphic ~527ms: in-order slot 20490 must remain eligible",
            FrameAdmitDisposition.QUEUED,
            second.frameAdmit,
        )
        assertNotEquals(
            FrameAdmitDisposition.REORDER_DISPLACEMENT_EXCEEDED,
            second.frameAdmit,
        )

        feedMonotonicSlots(
            fromSeq = FIELD_SECOND_SEQ + 1,
            toSeq = FIELD_THIRD_SEQ - 1,
            startWallMs = wallMs + FIELD_FIRST_TO_SECOND_MS + 20L,
        )

        val third =
            wiring.admitProtectedDatagram(
                sessionId,
                tonePacket(m01, FIELD_THIRD_SEQ),
                wallMs + FIELD_FIRST_TO_THIRD_MS,
            )
        assertEquals(
            "field-isomorphic ~956ms: monotonic in-order slot 20506 must stay eligible",
            FrameAdmitDisposition.QUEUED,
            third.frameAdmit,
        )
        assertNotEquals(
            FrameAdmitDisposition.REORDER_DISPLACEMENT_EXCEEDED,
            third.frameAdmit,
        )

        val pipeline = wiring.orchestrator(sessionId)!!.pipeline
        val jitter = pipeline.jitterSlotDomainSnapshot(m01.moduleId, m01.incarnationId)!!
        assertTrue(
            "monotonic ingress should buffer without artificial deadline gap",
            jitter.bySlotSize >= 3,
        )
    }

    private fun feedMonotonicSlots(
        fromSeq: Int,
        toSeq: Int,
        startWallMs: Long,
    ) {
        if (fromSeq > toSeq) return
        var tickWall = startWallMs
        for (seq in fromSeq..toSeq) {
            val result =
                wiring.admitProtectedDatagram(
                    sessionId,
                    tonePacket(m01, seq),
                    tickWall,
                )
            assertEquals(
                "monotonic slot $seq must QUEUED",
                FrameAdmitDisposition.QUEUED,
                result.frameAdmit,
            )
            tickWall += MediaJitterConstants.MEDIA_SLOT_MS
        }
    }

    /** Genuinely stale packet — MUST stay LATE once consumer frontier has passed it. */
    @Test
    fun boundary_genuinelyStalePacketRemainsLate() {
        assertTrue(wiring.installMember(sessionId, m01))
        wiring.admitProtectedDatagram(sessionId, tonePacket(m01, FIELD_BASE_SEQ), wallMs)
        val pipeline = wiring.orchestrator(sessionId)!!.pipeline
        val staleWall = wallMs + 5_000L
        val pullNow = staleWall + MediaJitterConstants.MAX_PLAYOUT_DELAY_MS + 1
        for (slot in FIELD_BASE_SEQ..FIELD_BASE_SEQ + 80) {
            pipeline.pullSlot(
                m01.moduleId,
                m01.incarnationId,
                slot.toLong(),
                staleWall,
                pullNow,
            )
        }

        val stale =
            wiring.admitProtectedDatagram(
                sessionId,
                tonePacket(m01, FIELD_BASE_SEQ + 2),
                staleWall,
            )
        assertEquals(
            FrameAdmitDisposition.LATE_FOR_PLAYOUT,
            stale.frameAdmit,
        )
    }

    /** New incarnation — fresh timeline after active playout; must not inherit gen1 anchor. */
    @Test
    fun boundary_incarnationReplacementReceivesFreshAlignment() {
        assertTrue(wiring.installMember(sessionId, m01))
        val gen1Seq = 100
        wiring.admitProtectedDatagram(sessionId, tonePacket(m01, gen1Seq), wallMs)
        advanceActivePlayout(gen1Seq, playoutCycles = 50)

        val gen2 =
            SessionMediaWiringHarness.memberBinding(
                "M01",
                incarnationId = 2L,
                ssrc = m01.ssrc + 1,
            )
        assertTrue(wiring.replaceMember(sessionId, m01, gen2))

        val tReplace = wallMs + 2_000L
        val gen2Base = 0x6000
        val firstGen2 =
            wiring.admitProtectedDatagram(
                sessionId,
                tonePacket(gen2, gen2Base),
                tReplace,
            )
        assertEquals(
            "new incarnation first packet must align to session slot domain",
            FrameAdmitDisposition.QUEUED,
            firstGen2.frameAdmit,
        )
        assertNotEquals(
            FrameAdmitDisposition.LATE_FOR_PLAYOUT,
            firstGen2.frameAdmit,
        )
    }

    /** F9.1 — session global playout tick must not expire remote before consumer pull. */
    @Test
    fun f91_globalSessionTickDoesNotExpireRemoteWhileFrontierNull() {
        val m02 = SessionMediaWiringHarness.memberBinding("M02", incarnationId = 2L)
        assertTrue(wiring.installMember(sessionId, m02))
        advanceSessionLocalPlayout(m02, baseSeq = 0x3000, cycles = 50)

        assertTrue(wiring.installMember(sessionId, m01))
        val remoteBase = 0x5000
        val first =
            wiring.admitProtectedDatagram(
                sessionId,
                tonePacket(m01, remoteBase),
                wallMs + 50_000L,
            )
        assertEquals(FrameAdmitDisposition.QUEUED, first.frameAdmit)

        advanceSessionLocalPlayout(m02, baseSeq = 0x3000 + 50, cycles = 50)

        val pipeline = wiring.orchestrator(sessionId)!!.pipeline
        assertEquals(
            null,
            pipeline.playoutExpirationFrontier(m01.moduleId, m01.incarnationId),
        )

        val second =
            wiring.admitProtectedDatagram(
                sessionId,
                tonePacket(m01, remoteBase + 1),
                wallMs + 51_000L,
            )
        assertEquals(FrameAdmitDisposition.QUEUED, second.frameAdmit)
    }

    /** F9.1 core — late join after session playout already advanced. */
    @Test
    fun f91_lateJoinAfterSessionPlayoutTickAdvanced() {
        val m02 = SessionMediaWiringHarness.memberBinding("M02", incarnationId = 2L)
        assertTrue(wiring.installMember(sessionId, m02))
        advanceSessionLocalPlayout(m02, baseSeq = 0x4000, cycles = 50)

        assertTrue(wiring.installMember(sessionId, m01))
        val remoteBase = 0x5000
        val s0 =
            wiring.admitProtectedDatagram(
                sessionId,
                tonePacket(m01, remoteBase),
                wallMs + 60_000L,
            )
        assertEquals(FrameAdmitDisposition.QUEUED, s0.frameAdmit)

        val pipeline = wiring.orchestrator(sessionId)!!.pipeline
        assertEquals(null, pipeline.playoutExpirationFrontier(m01.moduleId, m01.incarnationId))

        val s1 =
            wiring.admitProtectedDatagram(
                sessionId,
                tonePacket(m01, remoteBase + 1),
                wallMs + 60_020L,
            )
        assertEquals(FrameAdmitDisposition.QUEUED, s1.frameAdmit)

        val pullWall = wallMs + 60_040L
        pipeline.pullSlot(
            m01.moduleId,
            m01.incarnationId,
            remoteBase.toLong(),
            pullWall,
            pullWall,
        )
        assertEquals(
            remoteBase + 1L,
            pipeline.playoutExpirationFrontier(m01.moduleId, m01.incarnationId),
        )

        val s2 =
            wiring.admitProtectedDatagram(
                sessionId,
                tonePacket(m01, remoteBase + 2),
                wallMs + 60_060L,
            )
        assertEquals(FrameAdmitDisposition.QUEUED, s2.frameAdmit)

        val staleSlot = remoteBase - 10
        val staleWall = wallMs + 70_000L
        val pullNow = staleWall + MediaJitterConstants.MAX_PLAYOUT_DELAY_MS + 1
        for (slot in remoteBase + 3..remoteBase + 30) {
            pipeline.pullSlot(
                m01.moduleId,
                m01.incarnationId,
                slot.toLong(),
                staleWall,
                pullNow,
            )
        }
        val stale =
            wiring.admitProtectedDatagram(
                sessionId,
                tonePacket(m01, staleSlot),
                staleWall,
            )
        assertEquals(FrameAdmitDisposition.LATE_FOR_PLAYOUT, stale.frameAdmit)

        val gen2 =
            SessionMediaWiringHarness.memberBinding(
                "M01",
                incarnationId = 2L,
                ssrc = m01.ssrc + 1,
            )
        assertTrue(wiring.replaceMember(sessionId, m01, gen2))
        assertEquals(null, pipeline.playoutExpirationFrontier("M01", 2L))

        val freshBase = 0x7000
        val fresh =
            wiring.admitProtectedDatagram(
                sessionId,
                tonePacket(gen2, freshBase),
                staleWall + 100L,
            )
        assertEquals(FrameAdmitDisposition.QUEUED, fresh.frameAdmit)
    }

    private fun advanceSessionLocalPlayout(
        local: MemberBindingFact,
        baseSeq: Int,
        cycles: Int,
    ) {
        var tickWall = wallMs + MediaJitterConstants.MEDIA_SLOT_MS
        for (i in 0 until cycles) {
            val slot = baseSeq + i
            wiring.admitProtectedDatagram(
                sessionId,
                tonePacket(local, slot),
                tickWall,
            )
            val tickMediaTimeMs = wallMs + (i + 1) * MediaJitterConstants.MEDIA_SLOT_MS
            wiring.runMixPlayoutCycle(
                sessionId,
                tickWall + MediaJitterConstants.MEDIA_SLOT_MS,
                slot.toLong(),
                wallMs + (baseSeq - 0x4000 + i) * MediaJitterConstants.MEDIA_SLOT_MS + 100_000L,
                tickMediaTimeMs,
            )
            tickWall += MediaJitterConstants.MEDIA_SLOT_MS
        }
    }

    private fun advanceActivePlayout(
        baseSeq: Int,
        playoutCycles: Int,
    ) {
        var tickWall = wallMs + MediaJitterConstants.MEDIA_SLOT_MS
        for (i in 0 until playoutCycles) {
            val slot = baseSeq + i
            val tickMediaTimeMs = wallMs + (i + 1) * MediaJitterConstants.MEDIA_SLOT_MS
            wiring.runMixPlayoutCycle(
                sessionId,
                tickWall,
                slot.toLong(),
                wallMs + i * MediaJitterConstants.MEDIA_SLOT_MS,
                tickMediaTimeMs,
            )
            tickWall += MediaJitterConstants.MEDIA_SLOT_MS
        }
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
                audioLevel = 10,
                frequencyHz = 440.0,
            )
        return Phase1MediaHarness.buildToneProtectedPacket(fixture, mediaSlot)
    }
}
