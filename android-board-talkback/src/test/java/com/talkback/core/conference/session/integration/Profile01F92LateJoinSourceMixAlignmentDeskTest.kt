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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ADR-0058 F9.2 — Late-Join Source-to-Mix Playout Alignment (contract + desk).
 *
 * Field-isomorphic to issue-a-f91-field cycle 750 (session 8740916e…).
 *
 * Invariant: shared mix slot M is session playout position; for a source incarnation
 * not yet aligned to M, `pullSlot(source, M)` without domain transform is invalid.
 *
 * Legacy desk reproduces ~680-slot nextExpected jump (GREEN on pre-F9.2).
 * F9.2 contract tests RED until source→mix alignment is implemented.
 */
class Profile01F92LateJoinSourceMixAlignmentDeskTest {

    private val sessionId = "f92-latejoin-source-mix-alignment-desk"
    private lateinit var wiring: ConferenceSessionMediaWiring
    private var wallMs: Long = 0L

    companion object {
        /** Session timeline anchor — first established-source packet (field M02 @ 20480). */
        private const val SESSION_ANCHOR_SLOT = 0x5000 // 20480

        /** Established M01/M02 consumer edge after ~700 playout cycles (field ≈21176). */
        private const val ESTABLISHED_EDGE_SLOT = 21176L

        /** M03 late-join live edge (field first QUEUED ≈20496–20513). */
        private const val LATE_JOIN_FIRST_SLOT = 20493
        private const val LATE_JOIN_THIRD_SLOT = 20513
        private const val LATE_JOIN_LATE_TEST_SLOT = 20539 // field first false LATE

        /** Field cycle 750 resolved mix slot. */
        private const val SHARED_MIX_SLOT_FIELD = 21186L

        /** ~680-slot jump observed in field (21192 - 20539 ≈ 653 playout-delay slots). */
        private const val LEGACY_JUMP_MIN_SLOTS = 600L

        /** F9.2 — alignment must keep nextExpected within live-edge tolerance. */
        private const val F92_MAX_NEXT_EXPECTED_JUMP = 50L

        /** Cycles to advance session playout to established edge (field cycle ≈700). */
        private const val ESTABLISH_PLAYOUT_CYCLES = 700
    }

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

    /**
     * Field cycle-750 repro — post-F9.2 the ~667-slot jump must not recur.
     */
    @Test
    fun legacy_cycle750_sharedMixSlotNoLongerJumpsLateJoinRemoteNextExpected() {
        val ctx = runCycle750Transition()
        assertTrue(
            "F9.2 must prevent field-scale jump (got ${ctx.nextExpectedJump})",
            ctx.nextExpectedJump <= F92_MAX_NEXT_EXPECTED_JUMP,
        )
        assertTrue(
            "shared mix must stay on session main timeline (got ${ctx.sharedMixSlot})",
            ctx.sharedMixSlot >= SHARED_MIX_SLOT_FIELD - 30,
        )
    }

    /** F9.2 core — shared mix advance must not giant-jump late-join remote nextExpected. */
    @Test
    fun f92_contract_sharedMixSlotDoesNotJumpLateJoinRemoteNextExpected() {
        val ctx = runCycle750Transition()
        assertTrue(
            "F9.2: nextExpected jump ${ctx.nextExpectedJump} must stay <= $F92_MAX_NEXT_EXPECTED_JUMP",
            ctx.nextExpectedJump <= F92_MAX_NEXT_EXPECTED_JUMP,
        )
    }

    /** F9.2 + F9.3 — next in-order source slot after shared mix advance remains eligible. */
    @Test
    fun f92_contract_lateJoinInOrderPacketRemainsEligibleAfterSharedMixAdvance() {
        val ctx = runCycle750Transition()
        val lateJoin = ctx.m03
        val pipeline = wiring.orchestrator(sessionId)!!.pipeline
        val nextExpected =
            pipeline.nextExpectedSlot(lateJoin.moduleId, lateJoin.incarnationId)
                ?: error("M03 nextExpected missing after cycle-750 transition")
        // Layer A mediaTime is anchored at first QUEUED wall; admit with co-temporal wall.
        val admitWall =
            ctx.m03FirstQueuedWallMs +
                (nextExpected - LATE_JOIN_FIRST_SLOT) * MediaJitterConstants.MEDIA_SLOT_MS +
                10L
        val result =
            wiring.admitProtectedDatagram(
                sessionId,
                tonePacket(lateJoin, nextExpected.toInt()),
                admitWall,
            )
        assertEquals(
            "F9.2/F9.3: in-order slot $nextExpected must remain QUEUED after shared mix advance",
            FrameAdmitDisposition.QUEUED,
            result.frameAdmit,
        )
    }

    /** F9.2 boundary — session-coincident source: S ≈ M (identity mapping). */
    @Test
    fun f92_boundary_sessionCoincidentSourceMappingIsIdentity() {
        val m02 = SessionMediaWiringHarness.memberBinding("M02", incarnationId = 2L)
        assertTrue(wiring.installMember(sessionId, m02))
        val pipeline = wiring.orchestrator(sessionId)!!.pipeline

        val baseSlot = SESSION_ANCHOR_SLOT
        wiring.admitProtectedDatagram(sessionId, tonePacket(m02, baseSlot), wallMs)
        val tickMs = wallMs + MediaJitterConstants.MEDIA_SLOT_MS
        val resolved =
            wiring.resolvePlayoutMixSlot(sessionId, tickMs)
                ?: error("expected resolved mix slot")
        val before = pipeline.nextExpectedSlot(m02.moduleId, m02.incarnationId)
        wiring.runMixPlayoutCycle(
            sessionId,
            tickMs + MediaJitterConstants.MEDIA_SLOT_MS,
            resolved.slot,
            resolved.slotMediaTimeMs,
        )
        val after = pipeline.nextExpectedSlot(m02.moduleId, m02.incarnationId)
        assertNotNull(before)
        assertNotNull(after)
        assertTrue(
            "coincident source nextExpected must track resolved mix (+1), got before=$before after=$after resolved=${resolved.slot}",
            after!! <= resolved.slot + 2,
        )
    }

    /** F9.2 boundary — new incarnation discards prior alignment; first packet QUEUED. */
    @Test
    fun f92_boundary_newIncarnationDiscardsAlignment() {
        val ctx = runCycle750Transition()
        val gen2 =
            SessionMediaWiringHarness.memberBinding(
                "M03",
                incarnationId = 3L,
                ssrc = ctx.m03.ssrc + 1,
            )
        assertTrue(wiring.replaceMember(sessionId, ctx.m03, gen2))
        val pipeline = wiring.orchestrator(sessionId)!!.pipeline
        assertEquals(null, pipeline.playoutExpirationFrontier(gen2.moduleId, gen2.incarnationId))

        val freshBase = 0x7000
        val fresh =
            wiring.admitProtectedDatagram(
                sessionId,
                tonePacket(gen2, freshBase),
                ctx.wallAfterTransition + 100L,
            )
        assertEquals(FrameAdmitDisposition.QUEUED, fresh.frameAdmit)
    }

    /** F9.2 boundary — after alignment, genuinely missing slot still PLC-advances by one. */
    @Test
    fun f92_boundary_genuinelyMissingSourceSlotStillPlcAdvances() {
        val ctx = runCycle750Transition()
        val pipeline = wiring.orchestrator(sessionId)!!.pipeline
        val pullNow = ctx.wallAfterTransition + MediaJitterConstants.MAX_PLAYOUT_DELAY_MS + 1
        val before = pipeline.nextExpectedSlot(ctx.m03.moduleId, ctx.m03.incarnationId)
        assertNotNull(before)

        val disposition =
            pipeline.pullSlot(
                ctx.m03.moduleId,
                ctx.m03.incarnationId,
                before!!,
                pullNow,
                pullNow + MediaJitterConstants.MAX_PLAYOUT_DELAY_MS + 1,
            )
        val after = pipeline.nextExpectedSlot(ctx.m03.moduleId, ctx.m03.incarnationId)
        assertNotNull(after)
        assertTrue(
            "PLC must advance nextExpected by one (before=$before after=$after disposition=$disposition)",
            after == before + 1,
        )
    }

    private data class Cycle750Context(
        val m03: MemberBindingFact,
        val sharedMixSlot: Long,
        val nextExpectedBefore: Long,
        val nextExpectedAfter: Long,
        val nextExpectedJump: Long,
        val wallAfterTransition: Long,
        val m03FirstQueuedWallMs: Long,
    )

    /**
     * Field-isomorphic cycle-750 transition:
     * M01/M02 @ session 211xx, M03 late join @ 205xx, consume M03 buffer, shared mix → 21186.
     */
    private fun runCycle750Transition(): Cycle750Context {
        val m02 = SessionMediaWiringHarness.memberBinding("M02", incarnationId = 2L)
        val m01 = SessionMediaWiringHarness.memberBinding("M01", incarnationId = 3L)
        assertTrue(wiring.installMember(sessionId, m02))
        assertTrue(wiring.installMember(sessionId, m01))

        var tickWall = wallMs + MediaJitterConstants.MEDIA_SLOT_MS
        for (cycle in 0 until ESTABLISH_PLAYOUT_CYCLES) {
            val slot = SESSION_ANCHOR_SLOT + cycle
            wiring.admitProtectedDatagram(sessionId, tonePacket(m02, slot), tickWall)
            wiring.admitProtectedDatagram(sessionId, tonePacket(m01, slot), tickWall + 1)
            val tickMediaTimeMs = wallMs + (cycle + 1) * MediaJitterConstants.MEDIA_SLOT_MS
            runResolvedPlayoutCycle(tickMediaTimeMs, tickWall + MediaJitterConstants.MEDIA_SLOT_MS)
            tickWall += MediaJitterConstants.MEDIA_SLOT_MS
        }

        val m03 = SessionMediaWiringHarness.memberBinding("M03", incarnationId = 2L)
        assertTrue(wiring.installMember(sessionId, m03))
        val pipeline = wiring.orchestrator(sessionId)!!.pipeline

        var lateJoinWall = tickWall + 50L
        val m03FirstQueuedWallMs = lateJoinWall
        for (slot in LATE_JOIN_FIRST_SLOT..LATE_JOIN_THIRD_SLOT) {
            val admit =
                wiring.admitProtectedDatagram(
                    sessionId,
                    tonePacket(m03, slot),
                    lateJoinWall,
                )
            assertEquals(
                "monotonic late-join slot $slot must QUEUED (got ${admit.frameAdmit})",
                FrameAdmitDisposition.QUEUED,
                admit.frameAdmit,
            )
            lateJoinWall += MediaJitterConstants.MEDIA_SLOT_MS
        }

        var transitionTickMediaMs = wallMs + (ESTABLISH_PLAYOUT_CYCLES + 1) * MediaJitterConstants.MEDIA_SLOT_MS
        var transitionWall = lateJoinWall
        var establishedSlot = SESSION_ANCHOR_SLOT + ESTABLISH_PLAYOUT_CYCLES
        run {
            repeat(80) { _ ->
                wiring.admitProtectedDatagram(
                    sessionId,
                    tonePacket(m02, establishedSlot),
                    transitionWall,
                )
                wiring.admitProtectedDatagram(
                    sessionId,
                    tonePacket(m01, establishedSlot),
                    transitionWall + 1,
                )
                establishedSlot++
                val resolved =
                    wiring.resolvePlayoutMixSlot(sessionId, transitionTickMediaMs)
                        ?: error("expected resolved mix slot during M03 buffer drain")
                if (resolved.slot >= ESTABLISHED_EDGE_SLOT - 20) {
                    return@run
                }
                runResolvedPlayoutCycle(transitionTickMediaMs, transitionWall)
                transitionTickMediaMs += MediaJitterConstants.MEDIA_SLOT_MS
                transitionWall += MediaJitterConstants.MEDIA_SLOT_MS
            }
        }

        val m03NextBefore =
            pipeline.nextExpectedSlot(m03.moduleId, m03.incarnationId)
                ?: error("M03 nextExpected missing before cycle-750 transition")

        val cycle750TickMediaMs =
            wallMs + (ESTABLISH_PLAYOUT_CYCLES + 25) * MediaJitterConstants.MEDIA_SLOT_MS
        val sharedResolved =
            wiring.resolvePlayoutMixSlot(sessionId, cycle750TickMediaMs)
                ?: error("expected shared resolved mix slot at cycle-750 tick")
        assertTrue(
            "precondition: shared mix on session timeline (got ${sharedResolved.slot})",
            sharedResolved.slot >= SHARED_MIX_SLOT_FIELD - 30,
        )

        runResolvedPlayoutCycle(
            cycle750TickMediaMs,
            transitionWall + MediaJitterConstants.MEDIA_SLOT_MS,
        )

        val m03NextAfter =
            pipeline.nextExpectedSlot(m03.moduleId, m03.incarnationId)
                ?: error("M03 nextExpected missing after cycle-750 transition")

        return Cycle750Context(
            m03 = m03,
            sharedMixSlot = sharedResolved.slot,
            nextExpectedBefore = m03NextBefore,
            nextExpectedAfter = m03NextAfter,
            nextExpectedJump = m03NextAfter - m03NextBefore,
            wallAfterTransition = transitionWall + MediaJitterConstants.MEDIA_SLOT_MS,
            m03FirstQueuedWallMs = m03FirstQueuedWallMs,
        )
    }

    private fun runResolvedPlayoutCycle(
        tickMediaTimeMs: Long,
        nowMs: Long,
    ) {
        val resolved =
            wiring.resolvePlayoutMixSlot(sessionId, tickMediaTimeMs)
                ?: return
        wiring.runMixPlayoutCycle(
            sessionId,
            nowMs,
            resolved.slot,
            resolved.slotMediaTimeMs,
            tickMediaTimeMs,
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
                audioLevel = 10,
                frequencyHz = 440.0,
            )
        return Phase1MediaHarness.buildToneProtectedPacket(fixture, mediaSlot)
    }
}
