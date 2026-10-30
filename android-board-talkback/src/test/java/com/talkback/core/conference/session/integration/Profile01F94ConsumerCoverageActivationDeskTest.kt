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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * ADR-0058 F9.4 delta — consumer coverage activation authority (desk).
 *
 * Field-isomorphic to F15 / issue-a-f95-field cycle 450 (M01 observes M03 inc=2).
 */
class Profile01F94ConsumerCoverageActivationDeskTest {

    companion object {
        private const val SESSION_ANCHOR = 0x5000 // 20480
        private const val ESTABLISH_CYCLES = 40

        /** F15 field cycle 450 — M01 observes M03. */
        private const val F15_SHARED_M = 20937L
        private const val F15_MAPPED_S = 20495L
        private const val F15_OFFSET = F15_SHARED_M - F15_MAPPED_S // 442
        private const val F15_MIX_REF = F15_MAPPED_S + F15_OFFSET

        /** F16 Case C field values — M03 post-activation 1-slot hole (session 3808a047 cycle 400). */
        private const val CASE_C_MISSING_SLOT = 20579L
        private const val CASE_C_FIRST_WIRE_SLOT = 20580L
        private const val CASE_C_SHARED_M_AT_MISSING = CASE_C_MISSING_SLOT + F15_OFFSET
        private const val CASE_C_SHARED_M_AT_FIRST_WIRE = CASE_C_FIRST_WIRE_SLOT + F15_OFFSET
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

    /** F15 field-isomorphic — activation + empty coverage blocks mutation at mapped S. */
    @Test
    fun f94_delta_f15FieldIsomorphic_emptyCoverageBlocksMutationAtMappedSlot() {
        val snap =
            LateJoinSourcePlayoutActivationFence.SourceActivationSnapshot(
                alignment =
                    SourceMixPlayoutAlignment(
                        mixReferenceSlot = F15_MIX_REF,
                        sourceReferenceSlot = F15_MAPPED_S,
                    ),
                nextExpectedSourceSlot = F15_MAPPED_S,
                earliestBufferedSourceSlot = null,
                latestBufferedSourceSlot = null,
                playoutExpirationFrontierExclusive = null,
                activationFirstConsumptionPending = true,
            )
        assertEquals(
            LateJoinSourcePlayoutActivationFence.ConsumerPlayoutPhase.ACTIVATION,
            LateJoinSourcePlayoutActivationFence.consumerPlayoutPhase(snap),
        )
        assertFalse(
            LateJoinSourcePlayoutActivationFence.isMutationProducingPullAllowed(
                snap,
                F15_SHARED_M,
            ),
        )
        assertEquals(
            LateJoinSourcePlayoutActivationFence.SourcePlayoutReadiness.NOT_PLAYOUT_READY,
            LateJoinSourcePlayoutActivationFence.readiness(snap),
        )
    }

    /** Scenario A — phase lead span authorizes activation pull (no promotion). */
    @Test
    fun f94_delta_scenarioA_firstQueuedWithCoverageAllowsConsumerOrigin() {
        val ctx = establishLateJoinWithIngress(firstSourceSlot = 20497L, frameCount = 7)
        val pipeline = wiring.orchestrator(ctx.sessionId)!!.pipeline
        val sharedM = ctx.sharedMixAtJoin
        val snap = f94DeltaSnapshot(ctx.sessionId, ctx.m03)

        assertTrue(
            LateJoinSourcePlayoutActivationFence.isMutationProducingPullAllowed(snap, sharedM),
        )

        val nextBefore = pipeline.nextExpectedSlot(ctx.m03.moduleId, ctx.m03.incarnationId)!!
        f94DeltaRunProductionCycle(ctx.sessionId, ctx.tickMediaTimeMs, ctx.pullWallMs)

        val nextAfter = pipeline.nextExpectedSlot(ctx.m03.moduleId, ctx.m03.incarnationId)
        assertTrue(
            "activation pull with coverage must advance consumer origin (before=$nextBefore after=$nextAfter)",
            nextAfter != null && nextAfter > nextBefore,
        )
    }

    /**
     * Scenario B — Top-K promotion window with empty coverage must not PLC-establish cursor.
     *
     * Does not assert promotion deleted specific frames; only that mutation is blocked at pull time.
     */
    @Test
    fun f94_delta_scenarioB_promotionWindowEmptyCoverageBlocksMutation() {
        val ctx = establishLateJoinWithIngress(firstSourceSlot = 20497L, frameCount = 7)
        val orchestrator = wiring.orchestrator(ctx.sessionId)!!
        val pipeline = orchestrator.pipeline

        orchestrator.pipeline.selectTopK(ctx.pullWallMs + MediaJitterConstants.MAX_PLAYOUT_DELAY_MS + 5000L)
        orchestrator.pipeline.applyPromotionLiveEdgeFence(
            ctx.m03.moduleId,
            ctx.m03.incarnationId,
            ctx.pullWallMs + MediaJitterConstants.MAX_PLAYOUT_DELAY_MS + 5000L,
        )

        val bufferAfterPromotion =
            pipeline.bufferedSlots(ctx.m03.moduleId, ctx.m03.incarnationId)
        val snap = f94DeltaSnapshot(ctx.sessionId, ctx.m03)
        val sharedM = ctx.sharedMixAtJoin

        assertTrue(
            "desk models post-promotion empty/incompatible coverage (buffer=$bufferAfterPromotion)",
            bufferAfterPromotion.isEmpty() ||
                !LateJoinSourcePlayoutActivationFence.isMutationProducingPullAllowed(snap, sharedM),
        )

        val nextBefore = pipeline.nextExpectedSlot(ctx.m03.moduleId, ctx.m03.incarnationId)
        val frontierBefore =
            pipeline.playoutExpirationFrontier(ctx.m03.moduleId, ctx.m03.incarnationId)

        f94DeltaRunProductionCycle(
            ctx.sessionId,
            ctx.tickMediaTimeMs,
            ctx.pullWallMs + MediaJitterConstants.MAX_PLAYOUT_DELAY_MS + 5001L,
        )

        assertEquals(
            "empty activation coverage must not advance nextExpected via shared pull",
            nextBefore,
            pipeline.nextExpectedSlot(ctx.m03.moduleId, ctx.m03.incarnationId),
        )
        assertEquals(
            "empty activation coverage must not establish playout frontier",
            frontierBefore,
            pipeline.playoutExpirationFrontier(ctx.m03.moduleId, ctx.m03.incarnationId),
        )
    }

    /** Scenario C — steady playout preserves PLC-on-empty-buffer after consumer origin established. */
    @Test
    fun f94_delta_scenarioC_steadyEmptyBufferAllowsPlcAdvance() {
        val ctx = establishLateJoinWithIngress(firstSourceSlot = 20497L, frameCount = 7)
        val pipeline = wiring.orchestrator(ctx.sessionId)!!.pipeline
        val sharedM = ctx.sharedMixAtJoin

        f94DeltaRunProductionCycle(ctx.sessionId, ctx.tickMediaTimeMs, ctx.pullWallMs)
        assertFalse(
            pipeline.isActivationFirstConsumptionPending(ctx.m03.moduleId, ctx.m03.incarnationId),
        )

        while (pipeline.bufferedSlots(ctx.m03.moduleId, ctx.m03.incarnationId).isNotEmpty()) {
            pipeline.pullSlot(
                sourceIdentity = ctx.m03.moduleId,
                incarnationId = ctx.m03.incarnationId,
                slot =
                    pipeline.bufferedSlots(ctx.m03.moduleId, ctx.m03.incarnationId).first(),
                slotMediaTimeMs = ctx.pullWallMs,
                nowMs = ctx.pullWallMs,
            )
        }

        val snap = f94DeltaSnapshot(ctx.sessionId, ctx.m03)
        assertEquals(
            LateJoinSourcePlayoutActivationFence.ConsumerPlayoutPhase.STEADY,
            LateJoinSourcePlayoutActivationFence.consumerPlayoutPhase(snap),
        )
        assertTrue(
            LateJoinSourcePlayoutActivationFence.isMutationProducingPullAllowed(snap, sharedM),
        )

        val nextBefore = pipeline.nextExpectedSlot(ctx.m03.moduleId, ctx.m03.incarnationId)!!
        val plcWall = ctx.pullWallMs + MediaJitterConstants.MAX_PLAYOUT_DELAY_MS + 50L
        pipeline.pullSlot(
            sourceIdentity = ctx.m03.moduleId,
            incarnationId = ctx.m03.incarnationId,
            slot = nextBefore,
            slotMediaTimeMs = plcWall - MediaJitterConstants.MAX_PLAYOUT_DELAY_MS - 1,
            nowMs = plcWall,
        )
        val nextAfter = pipeline.nextExpectedSlot(ctx.m03.moduleId, ctx.m03.incarnationId)
        assertTrue(
            "steady empty-buffer PLC must remain permitted (before=$nextBefore after=$nextAfter)",
            nextAfter != null && nextAfter > nextBefore,
        )
    }

    /** offset==0 does not bypass activation coverage requirement. */
    @Test
    fun f94_delta_sessionCoincidentActivationStillRequiresCoverage() {
        val snap =
            LateJoinSourcePlayoutActivationFence.SourceActivationSnapshot(
                alignment =
                    SourceMixPlayoutAlignment(
                        mixReferenceSlot = SESSION_ANCHOR.toLong(),
                        sourceReferenceSlot = SESSION_ANCHOR.toLong(),
                    ),
                nextExpectedSourceSlot = SESSION_ANCHOR.toLong(),
                earliestBufferedSourceSlot = null,
                latestBufferedSourceSlot = null,
                playoutExpirationFrontierExclusive = null,
                activationFirstConsumptionPending = true,
            )
        assertFalse(
            LateJoinSourcePlayoutActivationFence.isMutationProducingPullAllowed(
                snap,
                SESSION_ANCHOR.toLong(),
            ),
        )
    }

    /** F16 Case C — symmetric hole only (mappedS == next); see [Profile01F94CaseCSteadyRecoveryPullSlotDeskTest]. */
    @Test
    fun f94_caseC_steadyMissingSlotHoleAllowsPlcAtMappedCursor() {
        val snap =
            LateJoinSourcePlayoutActivationFence.SourceActivationSnapshot(
                alignment =
                    SourceMixPlayoutAlignment(
                        mixReferenceSlot = CASE_C_SHARED_M_AT_MISSING,
                        sourceReferenceSlot = CASE_C_MISSING_SLOT,
                    ),
                nextExpectedSourceSlot = CASE_C_MISSING_SLOT,
                earliestBufferedSourceSlot = CASE_C_FIRST_WIRE_SLOT,
                latestBufferedSourceSlot = CASE_C_FIRST_WIRE_SLOT + 3L,
                playoutExpirationFrontierExclusive = null,
                activationFirstConsumptionPending = false,
            )
        assertEquals(
            LateJoinSourcePlayoutActivationFence.ConsumerPlayoutPhase.STEADY,
            LateJoinSourcePlayoutActivationFence.consumerPlayoutPhase(snap),
        )
        assertEquals(
            CASE_C_MISSING_SLOT,
            LateJoinSourcePlayoutActivationFence.resolveMutationProducingPullSourceSlot(
                snap,
                CASE_C_SHARED_M_AT_MISSING,
            ),
        )
        assertEquals(
            "must not snap to earliest mapped slot; hole recovery pulls cursor",
            CASE_C_MISSING_SLOT,
            LateJoinSourcePlayoutActivationFence.resolveMutationProducingPullSourceSlot(
                snap,
                CASE_C_SHARED_M_AT_FIRST_WIRE,
            ),
        )
        assertEquals(
            LateJoinSourcePlayoutActivationFence.SourcePlayoutReadiness.NOT_PLAYOUT_READY,
            LateJoinSourcePlayoutActivationFence.readiness(snap),
        )
    }

    /** ACTIVATION — next before earliest still blocked (no PLC origin from hole). */
    @Test
    fun f94_boundary_activationNextBeforeEarliestStillBlocksMutation() {
        val snap =
            LateJoinSourcePlayoutActivationFence.SourceActivationSnapshot(
                alignment =
                    SourceMixPlayoutAlignment(
                        mixReferenceSlot = CASE_C_SHARED_M_AT_MISSING,
                        sourceReferenceSlot = CASE_C_MISSING_SLOT,
                    ),
                nextExpectedSourceSlot = CASE_C_MISSING_SLOT,
                earliestBufferedSourceSlot = CASE_C_FIRST_WIRE_SLOT,
                latestBufferedSourceSlot = CASE_C_FIRST_WIRE_SLOT + 3L,
                playoutExpirationFrontierExclusive = null,
                activationFirstConsumptionPending = true,
            )
        assertFalse(
            LateJoinSourcePlayoutActivationFence.isMutationProducingPullAllowed(
                snap,
                CASE_C_SHARED_M_AT_MISSING,
            ),
        )
    }

    /** F16 Case C — production steady hole: PLC 20579 then real frame 20580. */
    @Test
    fun f94_caseC_steadyMissingSlotHoleProductionPullAdvancesThroughRealFrame() {
        val ctx = establishLateJoinWithIngress(firstSourceSlot = 20497L, frameCount = 7)
        val pipeline = wiring.orchestrator(ctx.sessionId)!!.pipeline

        f94DeltaRunProductionCycle(ctx.sessionId, ctx.tickMediaTimeMs, ctx.pullWallMs)
        assertFalse(
            pipeline.isActivationFirstConsumptionPending(ctx.m03.moduleId, ctx.m03.incarnationId),
        )

        f94CaseCDrainBufferedFrames(ctx)
        f94CaseCPlcAdvanceUntil(ctx, CASE_C_MISSING_SLOT)

        val admitWall = ctx.pullWallMs + MediaJitterConstants.MAX_PLAYOUT_DELAY_MS + 200L
        for (i in 0..3) {
            val sourceSlot = CASE_C_FIRST_WIRE_SLOT + i
            assertEquals(
                FrameAdmitDisposition.QUEUED,
                f94DeltaAdmitWithMixRef(
                    sessionId = ctx.sessionId,
                    binding = ctx.m03,
                    mediaSlot = sourceSlot,
                    rxWallMs = admitWall + i,
                    mixReferenceSlot = sourceSlot + F15_OFFSET,
                ),
            )
        }

        val snap = f94DeltaSnapshot(ctx.sessionId, ctx.m03)
        assertEquals(CASE_C_MISSING_SLOT, snap.nextExpectedSourceSlot)
        assertEquals(CASE_C_FIRST_WIRE_SLOT, snap.earliestBufferedSourceSlot)
        assertTrue(
            LateJoinSourcePlayoutActivationFence.isMutationProducingPullAllowed(
                snap,
                CASE_C_SHARED_M_AT_MISSING,
            ),
        )

        val plcWall = admitWall + 10L
        wiring.runMixPlayoutCycle(
            sessionId = ctx.sessionId,
            nowMs = plcWall,
            slot = CASE_C_SHARED_M_AT_MISSING,
            slotMediaTimeMs = plcWall - MediaJitterConstants.MAX_PLAYOUT_DELAY_MS,
        )
        assertEquals(
            CASE_C_FIRST_WIRE_SLOT,
            pipeline.nextExpectedSlot(ctx.m03.moduleId, ctx.m03.incarnationId),
        )

        val frameWall = plcWall + MediaJitterConstants.MEDIA_SLOT_MS
        wiring.runMixPlayoutCycle(
            sessionId = ctx.sessionId,
            nowMs = frameWall,
            slot = CASE_C_SHARED_M_AT_FIRST_WIRE,
            slotMediaTimeMs = frameWall - MediaJitterConstants.MAX_PLAYOUT_DELAY_MS,
        )
        assertEquals(
            CASE_C_FIRST_WIRE_SLOT + 1L,
            pipeline.nextExpectedSlot(ctx.m03.moduleId, ctx.m03.incarnationId),
        )
    }

    private fun f94CaseCDrainBufferedFrames(ctx: LateJoinIngressContext) {
        val pipeline = wiring.orchestrator(ctx.sessionId)!!.pipeline
        var wall = ctx.pullWallMs + MediaJitterConstants.MAX_PLAYOUT_DELAY_MS + 1L
        while (true) {
            val buffered =
                pipeline.bufferedSlots(ctx.m03.moduleId, ctx.m03.incarnationId)
            if (buffered.isEmpty()) break
            pipeline.pullSlot(
                sourceIdentity = ctx.m03.moduleId,
                incarnationId = ctx.m03.incarnationId,
                slot = buffered.first(),
                slotMediaTimeMs = wall - MediaJitterConstants.MAX_PLAYOUT_DELAY_MS,
                nowMs = wall,
            )
            wall += MediaJitterConstants.MEDIA_SLOT_MS
        }
    }

    private fun f94CaseCPlcAdvanceUntil(
        ctx: LateJoinIngressContext,
        targetNext: Long,
    ) {
        val pipeline = wiring.orchestrator(ctx.sessionId)!!.pipeline
        var wall = ctx.pullWallMs + MediaJitterConstants.MAX_PLAYOUT_DELAY_MS + 100L
        while (true) {
            val next =
                pipeline.nextExpectedSlot(ctx.m03.moduleId, ctx.m03.incarnationId)
                    ?: error("expected nextExpected")
            if (next >= targetNext) break
            pipeline.pullSlot(
                sourceIdentity = ctx.m03.moduleId,
                incarnationId = ctx.m03.incarnationId,
                slot = next,
                slotMediaTimeMs = wall - MediaJitterConstants.MAX_PLAYOUT_DELAY_MS - 1,
                nowMs = wall,
            )
            wall += MediaJitterConstants.MEDIA_SLOT_MS
        }
        assertEquals(
            targetNext,
            pipeline.nextExpectedSlot(ctx.m03.moduleId, ctx.m03.incarnationId),
        )
    }

    private data class LateJoinIngressContext(
        val sessionId: String,
        val m03: MemberBindingFact,
        val tickMediaTimeMs: Long,
        val pullWallMs: Long,
        val sharedMixAtJoin: Long,
    )

    private fun establishLateJoinWithIngress(
        firstSourceSlot: Long,
        frameCount: Int,
    ): LateJoinIngressContext {
        val sessionId = "f94-delta-${scenarioCounter.get()}"
        assertTrue(
            ConferenceSessionMediaBridge.startSession(
                SessionMediaWiringHarness.sessionFact(sessionId).copy(startedAtMs = wallMs),
            ),
        )
        val m02 = SessionMediaWiringHarness.memberBinding("M02", incarnationId = 2L)
        val m01 = SessionMediaWiringHarness.memberBinding("M01", incarnationId = 3L)
        assertTrue(wiring.installMember(sessionId, m02))
        assertTrue(wiring.installMember(sessionId, m01))
        f94DeltaObserveVoice(wiring.orchestrator(sessionId)!!, m01, m02)

        var tickWall = wallMs + MediaJitterConstants.MEDIA_SLOT_MS
        for (cycle in 0 until ESTABLISH_CYCLES) {
            val slot = SESSION_ANCHOR + cycle
            wiring.admitProtectedDatagram(sessionId, f94DeltaTonePacket(m02, slot), tickWall)
            wiring.admitProtectedDatagram(sessionId, f94DeltaTonePacket(m01, slot), tickWall + 1)
            val tickMediaTimeMs = wallMs + (cycle + 1) * MediaJitterConstants.MEDIA_SLOT_MS
            f94DeltaRunProductionCycle(sessionId, tickMediaTimeMs, tickWall)
            tickWall += MediaJitterConstants.MEDIA_SLOT_MS
        }

        val m03 = SessionMediaWiringHarness.memberBinding("M03", incarnationId = 2L)
        assertTrue(wiring.installMember(sessionId, m03))
        f94DeltaObserveVoice(wiring.orchestrator(sessionId)!!, m01, m02, m03)
        wiring.orchestrator(sessionId)!!.selectTopK(tickWall)

        val joinWall = tickWall + 20L
        val liveSlot = SESSION_ANCHOR + ESTABLISH_CYCLES
        wiring.admitProtectedDatagram(sessionId, f94DeltaTonePacket(m02, liveSlot), joinWall)
        wiring.admitProtectedDatagram(sessionId, f94DeltaTonePacket(m01, liveSlot), joinWall + 1)
        val tickMediaTimeMs =
            wallMs + (ESTABLISH_CYCLES + 1) * MediaJitterConstants.MEDIA_SLOT_MS
        val sharedMixAtJoin =
            wiring.resolvePlayoutMixSlot(sessionId, tickMediaTimeMs)?.slot
                ?: (liveSlot.toLong() + 1)

        for (i in 0 until frameCount) {
            val sourceSlot = firstSourceSlot + i
            val rxWall = joinWall + i
            val outcome =
                f94DeltaAdmitWithMixRef(
                    sessionId = sessionId,
                    binding = m03,
                    mediaSlot = sourceSlot,
                    rxWallMs = rxWall,
                    mixReferenceSlot = sourceSlot + F15_OFFSET,
                )
            assertEquals(FrameAdmitDisposition.QUEUED, outcome)
        }

        return LateJoinIngressContext(
            sessionId = sessionId,
            m03 = m03,
            tickMediaTimeMs = tickMediaTimeMs,
            pullWallMs = joinWall,
            sharedMixAtJoin = sharedMixAtJoin,
        )
    }

    private fun f94DeltaAdmitWithMixRef(
        sessionId: String,
        binding: MemberBindingFact,
        mediaSlot: Long,
        rxWallMs: Long,
        mixReferenceSlot: Long,
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
            sharedMixReferenceSlot = mixReferenceSlot,
        )

    private fun f94DeltaObserveVoice(
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

    private fun f94DeltaSnapshot(
        sessionId: String,
        binding: MemberBindingFact,
    ): LateJoinSourcePlayoutActivationFence.SourceActivationSnapshot =
        LateJoinSourcePlayoutActivationFence.snapshotFrom(
            wiring.orchestrator(sessionId)!!.pipeline,
            binding.moduleId,
            binding.incarnationId,
        )

    private fun f94DeltaRunProductionCycle(
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

    private fun f94DeltaTonePacket(
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
