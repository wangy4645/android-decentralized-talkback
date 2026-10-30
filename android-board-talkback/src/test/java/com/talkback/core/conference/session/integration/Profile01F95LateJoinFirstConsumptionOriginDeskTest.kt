package com.talkback.core.conference.session.integration

import com.talkback.core.conference.runtime.FrameAdmitDisposition
import com.talkback.core.conference.runtime.MediaJitterConstants
import com.talkback.core.conference.runtime.SlotPullDisposition
import com.talkback.core.conference.session.ConferenceSessionMediaBridge
import com.talkback.core.conference.session.ConferenceSessionMediaWiring
import com.talkback.core.conference.session.MemberBindingFact
import com.talkback.core.conference.session.SessionMediaWiringHarness
import com.talkback.core.conference.transport.LateJoinFirstConsumptionOrigin
import com.talkback.core.conference.transport.LateJoinSourcePlayoutActivationFence
import com.talkback.core.conference.transport.Phase1MediaHarness
import com.talkback.core.conference.transport.SourceMixPlayoutAlignment
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * ADR-0058 F9.5 — Late-Join First Consumption Origin (contract + desk).
 *
 * F13-isomorphic (M02 observes M01, session 7a384ca8…):
 * buffer 20481–20483, nextExpected=20481, offset=18, shared M=20497 → mapped S=20479.
 */
class Profile01F95LateJoinFirstConsumptionOriginDeskTest {

    companion object {
        private const val SESSION_ID = "f95-first-consumption-origin-desk"
        private const val F13_TRUSTED_ORIGIN = 20481L
        private const val F13_SHARED_M = 20497L
        private const val F13_OFFSET = 18L
        private const val F13_MAPPED_S = F13_SHARED_M - F13_OFFSET // 20479
        private const val F13_MIX_REF = F13_TRUSTED_ORIGIN + F13_OFFSET // 20499
    }

    private lateinit var wiring: ConferenceSessionMediaWiring
    private var wallMs: Long = 0L
    private val scenarioCounter = AtomicInteger()

    @Before
    fun setUp() {
        MeetingProductMediaShadow.enabled = true
        wiring = ConferenceSessionMediaWiring.forHarness()
        ConferenceSessionMediaBridge.wiring = wiring
        wallMs = 1_780_000_000_000L + scenarioCounter.incrementAndGet()
        assertTrue(
            ConferenceSessionMediaBridge.startSession(
                SessionMediaWiringHarness.sessionFact(SESSION_ID).copy(startedAtMs = wallMs),
            ),
        )
    }

    @After
    fun tearDown() {
        ConferenceSessionMediaBridge.wiring = null
    }

    /** Invariant A — monotonic advance rejects 20481 → 20480 regression. */
    @Test
    fun f95_invariantA_monotonicNextExpectedRejectsRegression() {
        assertEquals(
            20481L,
            LateJoinFirstConsumptionOrigin.monotonicNextExpected(
                currentNextExpected = 20481L,
                proposedNextExpected = 20480L,
            ),
        )
        assertEquals(
            20482L,
            LateJoinFirstConsumptionOrigin.monotonicNextExpected(
                currentNextExpected = 20481L,
                proposedNextExpected = 20482L,
            ),
        )
    }

    /** Invariant B — activation clamp lifts mapped S to trusted origin. */
    @Test
    fun f95_invariantB_activationClampUsesNextExpectedAuthority() {
        assertEquals(
            F13_TRUSTED_ORIGIN,
            LateJoinFirstConsumptionOrigin.resolveActivationConsumerSourceSlot(
                mappedSourceSlot = F13_MAPPED_S,
                nextExpectedSourceSlot = F13_TRUSTED_ORIGIN,
            ),
        )
    }

    /** Pre-F9.5 direct mapped pull — monotonic advance blocks cursor regression. */
    @Test
    fun legacy_f13_directMappedPullDoesNotRegressNextExpected() {
        val ctx = establishF13TrustedIngress()
        assertEquals(F13_TRUSTED_ORIGIN, ctx.nextBeforePull)

        f95LegacyPullAtMappedSlot(ctx, pullNowMs = ctx.wallMs + MediaJitterConstants.MAX_PLAYOUT_DELAY_MS + 5)

        val nextAfter =
            wiring.orchestrator(SESSION_ID)!!.pipeline.nextExpectedSlot(
                ctx.m01.moduleId,
                ctx.m01.incarnationId,
            )!!
        assertEquals(
            "monotonic advance must not regress below trusted origin (after=$nextAfter)",
            F13_TRUSTED_ORIGIN,
            nextAfter,
        )
    }

    /** Production — shared pull at mapped M consumes trusted origin without regression. */
    @Test
    fun f95_production_sharedMixPullConsumesTrustedOriginWithoutRegression() {
        val ctx = establishF13TrustedIngress()
        val pipeline = wiring.orchestrator(SESSION_ID)!!.pipeline

        val disposition =
            pipeline.pullSlotForSharedMixPlayout(
                sourceIdentity = ctx.m01.moduleId,
                incarnationId = ctx.m01.incarnationId,
                sharedMixPlayoutSlot = F13_SHARED_M,
                resolvedMixSlotMediaTimeMs = ctx.wallMs + F13_OFFSET * MediaJitterConstants.MEDIA_SLOT_MS,
                nowMs = ctx.wallMs,
            )
        assertTrue(
            "production shared pull must run (got $disposition)",
            disposition != SlotPullDisposition.FENCED_SKIP,
        )

        val nextAfter =
            pipeline.nextExpectedSlot(ctx.m01.moduleId, ctx.m01.incarnationId)!!
        assertTrue(
            "production path must advance past trusted origin (got $nextAfter)",
            nextAfter >= F13_TRUSTED_ORIGIN + 1,
        )
        assertTrue(
            !pipeline.bufferedSlots(ctx.m01.moduleId, ctx.m01.incarnationId)
                .contains(F13_TRUSTED_ORIGIN),
        )
    }

    /** Contract — first post-READY pull consumes trusted origin, no startup PLC below wire. */
    @Test
    fun f95_contract_f13IsomorphicFirstPullConsumesTrustedOrigin() {
        val ctx = establishF13TrustedIngress()
        val pipeline = wiring.orchestrator(SESSION_ID)!!.pipeline

        val resolved =
            LateJoinFirstConsumptionOrigin.resolveConsumerSourceSlot(
                phase = LateJoinFirstConsumptionOrigin.ConsumptionOriginPhase.FIRST_POST_READY_PULL,
                mappedSourceSlot = F13_MAPPED_S,
                nextExpectedSourceSlot = ctx.nextBeforePull,
            )!!
        assertEquals(F13_TRUSTED_ORIGIN, resolved)

        val bufferedBefore =
            pipeline.bufferedSlots(ctx.m01.moduleId, ctx.m01.incarnationId)
        assertTrue(bufferedBefore.contains(F13_TRUSTED_ORIGIN))
        val pull =
            pipeline.pullSlot(
                sourceIdentity = ctx.m01.moduleId,
                incarnationId = ctx.m01.incarnationId,
                slot = resolved,
                slotMediaTimeMs = ctx.wallMs,
                nowMs = ctx.wallMs,
            )
        assertTrue(
            "contract pull must consume buffered origin (got $pull)",
            pull != SlotPullDisposition.FENCED_SKIP,
        )

        val nextAfter =
            pipeline.nextExpectedSlot(ctx.m01.moduleId, ctx.m01.incarnationId)!!
        assertTrue(
            "first contract pull must advance past trusted origin (got $nextAfter)",
            nextAfter >= F13_TRUSTED_ORIGIN + 1,
        )
        assertTrue(
            "contract path must not regress below trusted origin (got $nextAfter)",
            nextAfter >= F13_TRUSTED_ORIGIN,
        )
        assertTrue(
            !pipeline.bufferedSlots(ctx.m01.moduleId, ctx.m01.incarnationId)
                .contains(F13_TRUSTED_ORIGIN),
        )
    }

    /** Boundary — mappedS == nextExpected: normal pull unchanged. */
    @Test
    fun f95_boundary_mappedEqualsNextExpectedUnchanged() {
        val mapped = 20500L
        assertEquals(
            mapped,
            LateJoinFirstConsumptionOrigin.resolveConsumerSourceSlot(
                phase = LateJoinFirstConsumptionOrigin.ConsumptionOriginPhase.FIRST_POST_READY_PULL,
                mappedSourceSlot = mapped,
                nextExpectedSourceSlot = mapped,
            ),
        )
        assertEquals(
            mapped,
            LateJoinFirstConsumptionOrigin.resolveConsumerSourceSlot(
                phase = LateJoinFirstConsumptionOrigin.ConsumptionOriginPhase.STEADY_STATE,
                mappedSourceSlot = mapped,
                nextExpectedSourceSlot = mapped,
            ),
        )
    }

    /** Boundary — mappedS > nextExpected: steady-state retains mapped slot (gap/PLC path). */
    @Test
    fun f95_boundary_mappedAheadOfNextExpectedRetainsGapSemantics() {
        val next = 20481L
        val mapped = 20490L
        assertEquals(
            mapped,
            LateJoinFirstConsumptionOrigin.resolveConsumerSourceSlot(
                phase = LateJoinFirstConsumptionOrigin.ConsumptionOriginPhase.STEADY_STATE,
                mappedSourceSlot = mapped,
                nextExpectedSourceSlot = next,
            ),
        )
        assertEquals(
            mapped,
            LateJoinFirstConsumptionOrigin.resolveConsumerSourceSlot(
                phase = LateJoinFirstConsumptionOrigin.ConsumptionOriginPhase.FIRST_POST_READY_PULL,
                mappedSourceSlot = mapped,
                nextExpectedSourceSlot = next,
            ),
        )
    }

    /** Boundary — steady state must not apply activation clamp when mapped lags next. */
    @Test
    fun f95_boundary_steadyStateDoesNotClampBelowMappedSlot() {
        val mapped = 20520L
        val next = 20525L
        assertEquals(
            mapped,
            LateJoinFirstConsumptionOrigin.resolveConsumerSourceSlot(
                phase = LateJoinFirstConsumptionOrigin.ConsumptionOriginPhase.STEADY_STATE,
                mappedSourceSlot = mapped,
                nextExpectedSourceSlot = next,
            ),
        )
    }

    private data class F13TrustedIngressContext(
        val m01: MemberBindingFact,
        val readiness: LateJoinSourcePlayoutActivationFence.SourcePlayoutReadiness,
        val nextBeforePull: Long,
        val wallMs: Long,
        val alignment: SourceMixPlayoutAlignment,
    )

    private fun establishF13TrustedIngress(): F13TrustedIngressContext {
        val m02 = SessionMediaWiringHarness.memberBinding("M02", incarnationId = 2L)
        assertTrue(wiring.installMember(SESSION_ID, m02))
        var tickWall = wallMs + MediaJitterConstants.MEDIA_SLOT_MS
        for (cycle in 0 until 30) {
            val slot = 20480 + cycle
            wiring.admitProtectedDatagram(SESSION_ID, f95TonePacket(m02, slot), tickWall)
            val tickMediaTimeMs = wallMs + (cycle + 1) * MediaJitterConstants.MEDIA_SLOT_MS
            f95RunProductionPlayoutCycle(wiring, SESSION_ID, tickMediaTimeMs, tickWall)
            tickWall += MediaJitterConstants.MEDIA_SLOT_MS
        }

        val m01 = SessionMediaWiringHarness.memberBinding("M01", incarnationId = 3L)
        assertTrue(wiring.installMember(SESSION_ID, m01))

        val joinWall = tickWall + 20L
        val mixRef = F13_MIX_REF
        listOf(F13_TRUSTED_ORIGIN, F13_TRUSTED_ORIGIN + 1, F13_TRUSTED_ORIGIN + 2).forEachIndexed { i, slot ->
            val outcome =
                f95AdmitWithMixRef(
                    wiring = wiring,
                    sessionId = SESSION_ID,
                    binding = m01,
                    mediaSlot = slot.toInt(),
                    rxWallMs = joinWall + i,
                    mixReferenceSlot = mixRef,
                )
            assertEquals(FrameAdmitDisposition.QUEUED, outcome)
        }

        val pipeline = wiring.orchestrator(SESSION_ID)!!.pipeline
        val alignment =
            pipeline.mixPlayoutAlignment(m01.moduleId, m01.incarnationId)
                ?: error("alignment missing after first QUEUED")
        assertEquals(F13_OFFSET, alignment.offset)

        val snap =
            LateJoinSourcePlayoutActivationFence.snapshotFrom(
                pipeline,
                m01.moduleId,
                m01.incarnationId,
            )
        val readiness = LateJoinSourcePlayoutActivationFence.readiness(snap)
        val next =
            pipeline.nextExpectedSlot(m01.moduleId, m01.incarnationId)
                ?: error("nextExpected missing after trusted ingress")

        return F13TrustedIngressContext(
            m01 = m01,
            readiness = readiness,
            nextBeforePull = next,
            wallMs = joinWall,
            alignment = alignment,
        )
    }

    private fun f95LegacyPullAtMappedSlot(
        ctx: F13TrustedIngressContext,
        pullNowMs: Long,
    ) {
        val pipeline = wiring.orchestrator(SESSION_ID)!!.pipeline
        val slotMediaTimeMs = pullNowMs - MediaJitterConstants.MAX_PLAYOUT_DELAY_MS - 1
        pipeline.pullSlot(
            sourceIdentity = ctx.m01.moduleId,
            incarnationId = ctx.m01.incarnationId,
            slot = F13_MAPPED_S,
            slotMediaTimeMs = slotMediaTimeMs,
            nowMs = pullNowMs,
        )
    }

    private fun f95RunProductionPlayoutCycle(
        wiring: ConferenceSessionMediaWiring,
        sessionId: String,
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

    private fun f95AdmitWithMixRef(
        wiring: ConferenceSessionMediaWiring,
        sessionId: String,
        binding: MemberBindingFact,
        mediaSlot: Int,
        rxWallMs: Long,
        mixReferenceSlot: Long,
    ): FrameAdmitDisposition =
        wiring.orchestrator(sessionId)!!.admitFrame(
            com.talkback.core.conference.runtime.AdmittedMediaFrame(
                sourceIdentity = binding.moduleId,
                incarnationId = binding.incarnationId,
                mediaSlot = mediaSlot.toLong(),
                mediaTimeMs = rxWallMs,
                arrivalMs = rxWallMs,
            ),
            nowMs = rxWallMs,
            sharedMixReferenceSlot = mixReferenceSlot,
        )

    private fun f95TonePacket(
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
