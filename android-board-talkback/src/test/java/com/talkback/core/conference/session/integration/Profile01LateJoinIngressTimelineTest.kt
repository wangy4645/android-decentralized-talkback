package com.talkback.core.conference.session.integration

import com.talkback.core.conference.runtime.FrameAdmitDisposition
import com.talkback.core.conference.runtime.MediaJitterConstants
import com.talkback.core.conference.session.ConferenceSessionMediaBridge
import com.talkback.core.conference.session.ConferenceSessionMediaWiring
import com.talkback.core.conference.session.MemberBindingFact
import com.talkback.core.conference.session.SessionMediaWiringHarness
import com.talkback.core.conference.transport.PerIncarnationIngressTimelineRegistry
import com.talkback.core.conference.transport.Phase1MediaHarness
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** ADR-0058 late-join amendment — per-incarnation ingress mapping fixtures A/B/C. */
class Profile01LateJoinIngressTimelineTest {
    private val sessionId = "late-join-ingress-session"
    private lateinit var wiring: ConferenceSessionMediaWiring
    private var wallMs: Long = 0L

    @Before
    fun setUp() {
        MeetingProductMediaShadow.enabled = true
        wiring = ConferenceSessionMediaWiring.forHarness()
        ConferenceSessionMediaBridge.wiring = wiring
        wallMs = 1_700_000_000_000L
        val fact = SessionMediaWiringHarness.sessionFact(sessionId).copy(startedAtMs = wallMs)
        assertTrue(ConferenceSessionMediaBridge.startSession(fact))
    }

    @After
    fun tearDown() {
        ConferenceSessionMediaBridge.wiring = null
    }

    @Test
    fun fixtureA_simultaneousSources_independentSeqOrigins_bothQueued() {
        val m01 = SessionMediaWiringHarness.memberBinding("M01", incarnationId = 1L)
        val m02 = SessionMediaWiringHarness.memberBinding("M02", incarnationId = 2L)
        assertTrue(wiring.installMember(sessionId, m01))
        assertTrue(wiring.installMember(sessionId, m02))

        val seqM01 = 0x5000
        val seqM02 = 0xA000
        val r1 =
            wiring.admitProtectedDatagram(
                sessionId,
                tonePacket(m01, seqM01),
                wallMs,
            )
        val r2 =
            wiring.admitProtectedDatagram(
                sessionId,
                tonePacket(m02, seqM02),
                wallMs + 50L,
            )
        assertEquals(FrameAdmitDisposition.QUEUED, r1.frameAdmit)
        assertEquals(FrameAdmitDisposition.QUEUED, r2.frameAdmit)
    }

    @Test
    fun fixtureB_lateJoin_independentSeqOrigin_firstFramesQueuedNotLate() {
        val early = SessionMediaWiringHarness.memberBinding("M01", incarnationId = 1L)
        assertTrue(wiring.installMember(sessionId, early))

        val earlyBaseSeq = 0x1001
        val first =
            wiring.admitProtectedDatagram(
                sessionId,
                tonePacket(early, earlyBaseSeq),
                wallMs,
            )
        assertEquals(FrameAdmitDisposition.QUEUED, first.frameAdmit)

        val playoutAdvanceMs = 5_000L
        var tickWall = wallMs
        for (i in 1..25) {
            tickWall += 200L
            wiring.runMixPlayoutCycle(
                sessionId,
                tickWall,
                (earlyBaseSeq + i).toLong(),
                wallMs + i * MediaJitterConstants.MEDIA_SLOT_MS,
            )
        }

        val lateWall = wallMs + playoutAdvanceMs
        val late = SessionMediaWiringHarness.memberBinding("M02", incarnationId = 2L)
        assertTrue(wiring.installMember(sessionId, late))

        val lateBaseSeq = 0x5000
        val lateFirst =
            wiring.admitProtectedDatagram(
                sessionId,
                tonePacket(late, lateBaseSeq),
                lateWall,
            )
        assertEquals(FrameAdmitDisposition.QUEUED, lateFirst.frameAdmit)
        assertNotEquals(
            FrameAdmitDisposition.LATE_FOR_PLAYOUT,
            lateFirst.frameAdmit,
        )

        val lateSecond =
            wiring.admitProtectedDatagram(
                sessionId,
                tonePacket(late, lateBaseSeq + 1),
                lateWall + MediaJitterConstants.MEDIA_SLOT_MS,
            )
        assertEquals(FrameAdmitDisposition.QUEUED, lateSecond.frameAdmit)

        val writesBefore = wiring.playoutSuccessfulWrites(sessionId) ?: 0L
        val earlyLiveSeq =
            earlyBaseSeq + (playoutAdvanceMs / MediaJitterConstants.MEDIA_SLOT_MS).toInt()
        val earlyLive =
            wiring.admitProtectedDatagram(
                sessionId,
                tonePacket(early, earlyLiveSeq),
                lateWall,
            )
        assertEquals(FrameAdmitDisposition.QUEUED, earlyLive.frameAdmit)

        val orch = wiring.orchestrator(sessionId)!!
        assertTrue(orch.pipeline.jitterSize("M02", 2L) > 0)
        val topK =
            orch.selection.currentTopK().members.map { it.sourceIdentity }.toSet()
        assertTrue(topK.contains("M01"))
        assertTrue(topK.contains("M02"))

        val resolved = wiring.resolvePlayoutMixSlot(sessionId, lateWall)
        assertNotNull(resolved)
        val mix =
            wiring.runMixPlayoutCycle(
                sessionId,
                lateWall + MediaJitterConstants.MEDIA_SLOT_MS,
                resolved!!.slot,
                resolved.slotMediaTimeMs,
            )
        assertNotNull(mix)
        assertTrue(mix!!.mixCycle.topKIdentities.contains("M02"))
        val writesAfter = wiring.playoutSuccessfulWrites(sessionId) ?: 0L
        assertTrue(writesAfter >= writesBefore)

        val lateExpected = orch.pipeline.nextExpectedSlot("M02", 2L)
        assertNotNull(lateExpected)
        assertTrue(lateExpected!! >= lateBaseSeq.toLong())
        assertTrue(lateExpected < lateBaseSeq + 16L)
        val earlyExpected = orch.pipeline.nextExpectedSlot("M01", 1L)
        if (earlyExpected != null) {
            assertTrue(earlyExpected < lateBaseSeq.toLong())
        }
        assertTrue(orch.pipeline.jitterSize("M02", 2L) > 0)
    }

    @Test
    fun fixtureC_incarnationReplacement_newIngressOriginQueued() {
        val gen1 =
            SessionMediaWiringHarness.memberBinding(
                "M01",
                incarnationId = 1L,
            )
        assertTrue(wiring.installMember(sessionId, gen1))
        val firstGen =
            wiring.admitProtectedDatagram(
                sessionId,
                tonePacket(gen1, 0x2000),
                wallMs,
            )
        assertEquals(FrameAdmitDisposition.QUEUED, firstGen.frameAdmit)

        val gen2 =
            SessionMediaWiringHarness.memberBinding(
                "M01",
                incarnationId = 2L,
                ssrc = gen1.ssrc + 1,
            )
        assertTrue(wiring.replaceMember(sessionId, gen1, gen2))

        val replacementFirst =
            wiring.admitProtectedDatagram(
                sessionId,
                tonePacket(gen2, 0x6000),
                wallMs + 500L,
            )
        assertEquals(FrameAdmitDisposition.QUEUED, replacementFirst.frameAdmit)
    }

    @Test
    fun perIncarnationRegistry_isolatesMediaTimeAcrossSources() {
        val registry = PerIncarnationIngressTimelineRegistry()
        val t0 = 1_000_000L
        val m01First = registry.mediaTimeMs("M01", 1L, 0x5000, t0)
        val m02First = registry.mediaTimeMs("M02", 2L, 0xA000, t0 + 5_000L)
        assertEquals(t0, m01First)
        assertEquals(t0 + 5_000L, m02First)
        val m01Second = registry.mediaTimeMs("M01", 1L, 0x5001, t0 + 20L)
        assertEquals(t0 + 20L, m01Second)
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
