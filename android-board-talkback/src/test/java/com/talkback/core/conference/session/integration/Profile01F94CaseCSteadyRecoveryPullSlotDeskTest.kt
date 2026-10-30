package com.talkback.core.conference.session.integration

import com.talkback.core.conference.runtime.AdmittedMediaFrame
import com.talkback.core.conference.runtime.FrameAdmitDisposition
import com.talkback.core.conference.runtime.MediaJitterConstants
import com.talkback.core.conference.runtime.VoiceLevelObservation
import com.talkback.core.conference.session.ConferenceSessionMediaBridge
import com.talkback.core.conference.session.ConferenceSessionMediaWiring
import com.talkback.core.conference.session.MemberBindingFact
import com.talkback.core.conference.session.SessionMediaWiringHarness
import com.talkback.core.conference.transport.LateJoinSourcePlayoutActivationFence
import com.talkback.core.conference.transport.Phase1MediaHarness
import com.talkback.core.conference.transport.SourceMixPlayoutAlignment
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * F16 Case C field-isomorphic desk — session 89a2dd75 cycle 2150 (asymmetric mapped ahead).
 *
 * Documents why v1 boolean gate + F9.2 mapped pull cannot recover a genuine STEADY hole, and
 * specifies desk-only [F94CaseCSteadyRecoveryPullSlotDesk.effectivePullSourceSlot] for production.
 *
 * Validates [LateJoinSourcePlayoutActivationFence.resolveMutationProducingPullSourceSlot].
 */
class Profile01F94CaseCSteadyRecoveryPullSlotDeskTest {

    companion object {
        /** Field cycle 2150 — M01 observes M03, mediaKeyEpoch=2. */
        private const val CASE_C2_NEXT = 21610L
        private const val CASE_C2_EARLIEST = 21611L
        private const val CASE_C2_OFFSET = 1066L
        private const val CASE_C2_SHARED_M = 22678L
        private const val CASE_C2_MAPPED_S = 21612L

        private const val SESSION_ANCHOR = 0x5000
        private const val ESTABLISH_CYCLES = 40
    }

    private lateinit var wiring: ConferenceSessionMediaWiring
    private var wallMs: Long = 0L
    private val scenarioCounter = AtomicInteger()

    @Before
    fun setUp() {
        MeetingProductMediaShadow.enabled = true
        wiring = ConferenceSessionMediaWiring.forHarness()
        ConferenceSessionMediaBridge.wiring = wiring
        wallMs = 1_789_000_000_000L + scenarioCounter.incrementAndGet()
    }

    @After
    fun tearDown() {
        ConferenceSessionMediaBridge.wiring = null
    }

    /** Field snapshot — resolver returns consumer cursor for STEADY hole. */
    @Test
    fun f94_caseC2_fieldIsomorphic_resolveReturnsCursorNotMappedS() {
        val snap = caseC2FieldSnapshot()
        assertEquals(
            CASE_C2_NEXT,
            LateJoinSourcePlayoutActivationFence.resolveMutationProducingPullSourceSlot(
                snap,
                CASE_C2_SHARED_M,
            ),
        )
        assertFalse(
            LateJoinSourcePlayoutActivationFence
                .resolveMutationProducingPullSourceSlot(snap, CASE_C2_SHARED_M) == null,
        )
    }

    /** Proposed [next, earliest] range still excludes mappedS=21612 on field numbers. */
    @Test
    fun f94_caseC2_fieldIsomorphic_nextEarliestRangeDoesNotCoverMappedS() {
        assertFalse(
            F94CaseCSteadyRecoveryPullSlotDesk.inNextEarliestRange(
                mappedS = CASE_C2_MAPPED_S,
                next = CASE_C2_NEXT,
                earliest = CASE_C2_EARLIEST,
            ),
        )
    }

    /** Legacy F9.5 STEADY path alone would pull mappedS — superseded by fence resolver. */
    @Test
    fun f94_caseC2_fieldIsomorphic_productionResolverMatchesDeskContract() {
        val snap = caseC2FieldSnapshot()
        assertEquals(
            CASE_C2_NEXT,
            F94CaseCSteadyRecoveryPullSlotDesk.effectivePullSourceSlot(
                snapshot = snap,
                sharedMixPlayoutSlot = CASE_C2_SHARED_M,
            ),
        )
    }

    /** Desk contract — STEADY hole recovery must pull cursor, not shared timeline demand. */
    @Test
    fun f94_caseC2_fieldIsomorphic_effectivePullSlotSpecification() {
        val snap = caseC2FieldSnapshot()
        assertEquals(
            CASE_C2_NEXT,
            F94CaseCSteadyRecoveryPullSlotDesk.effectivePullSourceSlot(
                snapshot = snap,
                sharedMixPlayoutSlot = CASE_C2_SHARED_M,
            ),
        )
        assertEquals(
            CASE_C2_NEXT,
            F94CaseCSteadyRecoveryPullSlotDesk.effectivePullSourceSlot(
                snapshot = snap,
                sharedMixPlayoutSlot = CASE_C2_SHARED_M,
                mappedSourceSlotOverride = CASE_C2_NEXT - 1L,
            ),
        )
        val caughtUpSnap =
            snap.copy(
                nextExpectedSourceSlot = CASE_C2_EARLIEST,
                earliestBufferedSourceSlot = CASE_C2_EARLIEST,
            )
        assertEquals(
            CASE_C2_MAPPED_S,
            F94CaseCSteadyRecoveryPullSlotDesk.effectivePullSourceSlot(
                snapshot = caughtUpSnap,
                sharedMixPlayoutSlot = CASE_C2_SHARED_M,
            ),
        )
        assertNull(
            F94CaseCSteadyRecoveryPullSlotDesk.effectivePullSourceSlot(
                snapshot = snap.copy(activationFirstConsumptionPending = true),
                sharedMixPlayoutSlot = CASE_C2_SHARED_M,
            ),
        )
    }

    /**
     * Harness — production shared-M pull recovers hole via effectivePullS; then converges.
     */
    @Test
    fun f94_caseC2_fieldIsomorphic_effectivePullSlotHarnessRecoversHole() {
        val ctx = establishCaseC2FieldHarness()
        val pipeline = wiring.orchestrator(ctx.sessionId)!!.pipeline
        val snap = caseC2SnapshotFromPipeline(ctx)

        assertEquals(CASE_C2_NEXT, snap.nextExpectedSourceSlot)
        assertEquals(CASE_C2_EARLIEST, snap.earliestBufferedSourceSlot)
        assertEquals(
            CASE_C2_NEXT,
            LateJoinSourcePlayoutActivationFence.resolveMutationProducingPullSourceSlot(
                snap,
                CASE_C2_SHARED_M,
            ),
        )

        val plcWall = ctx.pullWallMs + MediaJitterConstants.MAX_PLAYOUT_DELAY_MS + 300L
        wiring.runMixPlayoutCycle(
            sessionId = ctx.sessionId,
            nowMs = plcWall,
            slot = CASE_C2_SHARED_M,
            slotMediaTimeMs = plcWall - MediaJitterConstants.MAX_PLAYOUT_DELAY_MS,
        )
        assertEquals(
            CASE_C2_EARLIEST,
            pipeline.nextExpectedSlot(ctx.m03.moduleId, ctx.m03.incarnationId),
        )

        val realWall = plcWall + MediaJitterConstants.MEDIA_SLOT_MS
        pipeline.pullSlot(
            sourceIdentity = ctx.m03.moduleId,
            incarnationId = ctx.m03.incarnationId,
            slot = CASE_C2_EARLIEST,
            slotMediaTimeMs = realWall - MediaJitterConstants.MAX_PLAYOUT_DELAY_MS,
            nowMs = realWall,
        )
        assertEquals(
            CASE_C2_MAPPED_S,
            pipeline.nextExpectedSlot(ctx.m03.moduleId, ctx.m03.incarnationId),
        )

        val convergeWall = realWall + MediaJitterConstants.MEDIA_SLOT_MS
        val effectiveConverge =
            LateJoinSourcePlayoutActivationFence.resolveMutationProducingPullSourceSlot(
                caseC2SnapshotFromPipeline(ctx),
                CASE_C2_SHARED_M,
            )
        assertEquals(CASE_C2_MAPPED_S, effectiveConverge)
        pipeline.pullSlotForSharedMixPlayout(
            sourceIdentity = ctx.m03.moduleId,
            incarnationId = ctx.m03.incarnationId,
            sharedMixPlayoutSlot = CASE_C2_SHARED_M,
            resolvedMixSlotMediaTimeMs = convergeWall - MediaJitterConstants.MAX_PLAYOUT_DELAY_MS,
            nowMs = convergeWall,
            effectivePullSourceSlot = effectiveConverge,
        )
        assertEquals(
            CASE_C2_MAPPED_S + 1L,
            pipeline.nextExpectedSlot(ctx.m03.moduleId, ctx.m03.incarnationId),
        )
    }

    private fun caseC2FieldSnapshot(): LateJoinSourcePlayoutActivationFence.SourceActivationSnapshot =
        LateJoinSourcePlayoutActivationFence.SourceActivationSnapshot(
            alignment =
                SourceMixPlayoutAlignment(
                    mixReferenceSlot = CASE_C2_NEXT + CASE_C2_OFFSET,
                    sourceReferenceSlot = CASE_C2_NEXT,
                ),
            nextExpectedSourceSlot = CASE_C2_NEXT,
            earliestBufferedSourceSlot = CASE_C2_EARLIEST,
            latestBufferedSourceSlot = CASE_C2_EARLIEST + 3L,
            playoutExpirationFrontierExclusive = null,
            activationFirstConsumptionPending = false,
        )

    private fun caseC2SnapshotFromPipeline(
        ctx: CaseC2HarnessContext,
    ): LateJoinSourcePlayoutActivationFence.SourceActivationSnapshot =
        LateJoinSourcePlayoutActivationFence.snapshotFrom(
            wiring.orchestrator(ctx.sessionId)!!.pipeline,
            ctx.m03.moduleId,
            ctx.m03.incarnationId,
        )

    private data class CaseC2HarnessContext(
        val sessionId: String,
        val m03: MemberBindingFact,
        val pullWallMs: Long,
    )

    private fun establishCaseC2FieldHarness(): CaseC2HarnessContext {
        val sessionId = "f94-casec2-${scenarioCounter.get()}"
        assertTrue(
            ConferenceSessionMediaBridge.startSession(
                SessionMediaWiringHarness.sessionFact(sessionId).copy(startedAtMs = wallMs),
            ),
        )
        val m02 = SessionMediaWiringHarness.memberBinding("M02", incarnationId = 2L)
        val m01 = SessionMediaWiringHarness.memberBinding("M01", incarnationId = 3L)
        assertTrue(wiring.installMember(sessionId, m02))
        assertTrue(wiring.installMember(sessionId, m01))
        caseC2ObserveVoice(wiring.orchestrator(sessionId)!!, m01, m02)

        var tickWall = wallMs + MediaJitterConstants.MEDIA_SLOT_MS
        for (cycle in 0 until ESTABLISH_CYCLES) {
            val slot = SESSION_ANCHOR + cycle
            wiring.admitProtectedDatagram(sessionId, caseC2TonePacket(m02, slot), tickWall)
            wiring.admitProtectedDatagram(sessionId, caseC2TonePacket(m01, slot), tickWall + 1)
            val tickMediaTimeMs = wallMs + (cycle + 1) * MediaJitterConstants.MEDIA_SLOT_MS
            caseC2RunProductionCycle(sessionId, tickMediaTimeMs, tickWall)
            tickWall += MediaJitterConstants.MEDIA_SLOT_MS
        }

        val m03 = SessionMediaWiringHarness.memberBinding("M03", incarnationId = 2L)
        assertTrue(wiring.installMember(sessionId, m03))
        caseC2ObserveVoice(wiring.orchestrator(sessionId)!!, m01, m02, m03)
        wiring.orchestrator(sessionId)!!.selectTopK(tickWall)

        val joinWall = tickWall + 20L
        val liveSlot = SESSION_ANCHOR + ESTABLISH_CYCLES
        wiring.admitProtectedDatagram(sessionId, caseC2TonePacket(m02, liveSlot), joinWall)
        wiring.admitProtectedDatagram(sessionId, caseC2TonePacket(m01, liveSlot), joinWall + 1)

        val activationFirst = CASE_C2_NEXT - 7L
        for (i in 0..6) {
            val sourceSlot = activationFirst + i
            assertEquals(
                FrameAdmitDisposition.QUEUED,
                caseC2AdmitWithOffset(
                    sessionId = sessionId,
                    binding = m03,
                    mediaSlot = sourceSlot,
                    rxWallMs = joinWall + i,
                ),
            )
        }

        val tickMediaTimeMs =
            wallMs + (ESTABLISH_CYCLES + 1) * MediaJitterConstants.MEDIA_SLOT_MS
        caseC2RunProductionCycle(sessionId, tickMediaTimeMs, joinWall)

        val pipeline = wiring.orchestrator(sessionId)!!.pipeline
        assertFalse(
            pipeline.isActivationFirstConsumptionPending(m03.moduleId, m03.incarnationId),
        )

        caseC2DrainBufferedFrames(sessionId, m03, joinWall)
        assertEquals(
            CASE_C2_NEXT,
            pipeline.nextExpectedSlot(m03.moduleId, m03.incarnationId),
        )

        val admitWall = joinWall + MediaJitterConstants.MAX_PLAYOUT_DELAY_MS + 200L
        for (i in 0..3) {
            val sourceSlot = CASE_C2_EARLIEST + i
            assertEquals(
                FrameAdmitDisposition.QUEUED,
                caseC2AdmitWithOffset(
                    sessionId = sessionId,
                    binding = m03,
                    mediaSlot = sourceSlot,
                    rxWallMs = admitWall + i,
                ),
            )
        }

        assertEquals(
            CASE_C2_NEXT,
            pipeline.nextExpectedSlot(m03.moduleId, m03.incarnationId),
        )

        val alignment =
            pipeline.mixPlayoutAlignment(m03.moduleId, m03.incarnationId)
        assertEquals(CASE_C2_OFFSET, alignment?.offset)

        return CaseC2HarnessContext(
            sessionId = sessionId,
            m03 = m03,
            pullWallMs = joinWall,
        )
    }

    private fun caseC2AdmitWithOffset(
        sessionId: String,
        binding: MemberBindingFact,
        mediaSlot: Long,
        rxWallMs: Long,
    ): FrameAdmitDisposition =
        wiring.orchestrator(sessionId)!!.admitFrame(
            AdmittedMediaFrame(
                sourceIdentity = binding.moduleId,
                incarnationId = binding.incarnationId,
                mediaSlot = mediaSlot,
                mediaTimeMs = rxWallMs,
                arrivalMs = rxWallMs,
            ),
            nowMs = rxWallMs,
            sharedMixReferenceSlot = mediaSlot + CASE_C2_OFFSET,
        )

    private fun caseC2DrainBufferedFrames(
        sessionId: String,
        binding: MemberBindingFact,
        pullWallMs: Long,
    ) {
        val pipeline = wiring.orchestrator(sessionId)!!.pipeline
        var wall = pullWallMs + MediaJitterConstants.MAX_PLAYOUT_DELAY_MS + 1L
        while (true) {
            val buffered = pipeline.bufferedSlots(binding.moduleId, binding.incarnationId)
            if (buffered.isEmpty()) break
            pipeline.pullSlot(
                sourceIdentity = binding.moduleId,
                incarnationId = binding.incarnationId,
                slot = buffered.first(),
                slotMediaTimeMs = wall - MediaJitterConstants.MAX_PLAYOUT_DELAY_MS,
                nowMs = wall,
            )
            wall += MediaJitterConstants.MEDIA_SLOT_MS
        }
    }

    private fun caseC2RunProductionCycle(
        sessionId: String,
        tickMediaTimeMs: Long,
        nowMs: Long,
    ) {
        val resolved =
            wiring.resolvePlayoutMixSlot(sessionId, tickMediaTimeMs)
                ?: error("expected resolved mix slot")
        wiring.runMixPlayoutCycle(
            sessionId,
            nowMs,
            resolved.slot,
            resolved.slotMediaTimeMs,
            tickMediaTimeMs,
        )
    }

    private fun caseC2ObserveVoice(
        orchestrator: com.talkback.core.conference.runtime.ConferenceMediaExecutionOrchestrator,
        vararg bindings: MemberBindingFact,
    ) {
        for (binding in bindings) {
            orchestrator.observeVoice(
                VoiceLevelObservation(
                    sourceIdentity = binding.moduleId,
                    incarnationId = binding.incarnationId,
                    voiceActive = true,
                    audioLevel = 90,
                ),
            )
        }
    }

    private fun caseC2TonePacket(
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

/**
 * Desk helper — delegates to production [LateJoinSourcePlayoutActivationFence] resolver.
 */
internal object F94CaseCSteadyRecoveryPullSlotDesk {
    fun inNextEarliestRange(
        mappedS: Long,
        next: Long,
        earliest: Long,
    ): Boolean = mappedS in next..earliest

    fun effectivePullSourceSlot(
        snapshot: LateJoinSourcePlayoutActivationFence.SourceActivationSnapshot,
        sharedMixPlayoutSlot: Long,
        mappedSourceSlotOverride: Long? = null,
    ): Long? {
        if (mappedSourceSlotOverride != null) {
            val alignment = snapshot.alignment ?: return null
            val mappedS = mappedSourceSlotOverride
            val next = snapshot.nextExpectedSourceSlot ?: return null
            val earliest = snapshot.earliestBufferedSourceSlot
            if (mappedS < next) {
                if (earliest == null) return next
                if (next < earliest) return next
                return earliest
            }
            if (earliest == null) return mappedS
            if (next < earliest) {
                return if (mappedS >= next) next else null
            }
            return mappedS
        }
        return LateJoinSourcePlayoutActivationFence.resolveMutationProducingPullSourceSlot(
            snapshot,
            sharedMixPlayoutSlot,
        )
    }
}
