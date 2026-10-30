package com.talkback.core.conference.session.integration

import com.talkback.core.conference.runtime.ConferenceMediaExecutionOrchestrator
import com.talkback.core.conference.runtime.FrameAdmitDisposition
import com.talkback.core.conference.runtime.MediaJitterConstants
import com.talkback.core.conference.runtime.SlotPullDisposition
import com.talkback.core.conference.runtime.VoiceLevelObservation
import com.talkback.core.conference.session.ConferenceSessionMediaBridge
import com.talkback.core.conference.session.ConferenceSessionMediaCoordinatorDelegate
import com.talkback.core.conference.session.ConferenceSessionMediaWiring
import com.talkback.core.conference.session.MemberBindingFact
import com.talkback.core.conference.session.SessionMediaWiringHarness
import com.talkback.core.conference.transport.LateJoinSourcePlayoutActivationFence
import com.talkback.core.conference.transport.Phase1MediaHarness
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.abs

/**
 * A3-C — normalized consumer lifecycle on one production-path fixture.
 *
 * M01 session-coincident lead · M03 offset≈294 · M04 offset≈572 · local M02 Top-K excluded.
 * 50 pps ingress + playout ticks. F9.2 normalization KEEP — chase M04 straddle / pull liveness.
 */
class Profile01A3CConsumerLifecycleDeskTest {
    private lateinit var wiring: ConferenceSessionMediaWiring
    private var wallMs: Long = 0L
    private var priorLocalModuleIdProvider: (() -> String)? = null

    @Before
    fun setUp() {
        priorLocalModuleIdProvider = ConferenceSessionMediaCoordinatorDelegate.localModuleIdProvider
        ConferenceSessionMediaCoordinatorDelegate.localModuleIdProvider = { LOCAL }
        MeetingProductMediaShadow.enabled = true
        wiring = ConferenceSessionMediaWiring.forHarness()
        ConferenceSessionMediaBridge.wiring = wiring
        wallMs = 1_793_000_000_000L
    }

    @After
    fun tearDown() {
        ConferenceSessionMediaCoordinatorDelegate.localModuleIdProvider = priorLocalModuleIdProvider
        ConferenceSessionMediaBridge.wiring = null
    }

    @Test
    fun multiSourceFixture_f92NormalizationControl_m04MustStraddleOrConsumeResolvedM() {
        val ctx = establishMultiSourceFixture()
        runLifecycleObservation(ctx, fieldDiff = FieldDiff.NONE)
    }

    /**
     * A3-C-DIFF — field 143138 minimal ordering gap injected; still healthy on desk.
     *
     * Field ordering: M04 install → +~70 resolved slots → TopK with stale raw buffer →
     * +~35 slots → first logged QUEUED @ S=20521 (F9.2). Simulated here as alignment-at-join
     * then [FIELD_M04_IN_TOPK_STALE_SLOTS] playout without M04 ingress.
     *
     * Does not reproduce straddle 0/111 — desk lifecycle model insufficient for field anomaly.
     */
    @Test
    fun fieldDiff143138_staleTopKGap_stillDoesNotReproduceM04StraddleZero() {
        val ctx = establishMultiSourceFixture()
        runLifecycleObservation(ctx, fieldDiff = FieldDiff.M04_TOPK_STALE_SHARED_M)
    }

    private enum class FieldDiff {
        NONE,
        M04_TOPK_STALE_SHARED_M,
    }

    private fun runLifecycleObservation(
        ctx: FixtureCtx,
        fieldDiff: FieldDiff,
    ) {
        val pipeline = wiring.orchestrator(ctx.sessionId)!!.pipeline
        val observations = mutableListOf<CycleObservation>()

        if (fieldDiff == FieldDiff.M04_TOPK_STALE_SHARED_M) {
            repeat(FIELD_M04_IN_TOPK_STALE_SLOTS) {
                wallMs += MediaJitterConstants.MEDIA_SLOT_MS
                ctx.runSlot += 1
                admitTone(ctx.sessionId, ctx.m01, ctx.runSlot, wallMs)
                admitTone(ctx.sessionId, ctx.m03, ctx.runSlot, wallMs + 1)
                runResolvedCycle(ctx.sessionId, wallMs, wallMs)
            }
        }

        repeat(RUN_CYCLES) {
            wallMs += MediaJitterConstants.MEDIA_SLOT_MS
            ctx.runSlot += 1
            admitTone(ctx.sessionId, ctx.m01, ctx.runSlot, wallMs)
            admitTone(ctx.sessionId, ctx.m03, ctx.runSlot, wallMs + 1)
            admitTone(ctx.sessionId, ctx.m04, ctx.runSlot, wallMs + 2)

            val tickMediaTimeMs = wallMs
            val targetM = wiring.sessionPlayoutTargetSlot(ctx.sessionId, tickMediaTimeMs)
            val resolved =
                wiring.resolvePlayoutMixSlot(ctx.sessionId, tickMediaTimeMs)
                    ?: error("expected resolved mix slot")
            observations +=
                observeCycle(
                    pipeline = pipeline,
                    ctx = ctx,
                    targetM = targetM!!,
                    resolvedM = resolved.slot,
                    tickMediaTimeMs = tickMediaTimeMs,
                )
            wiring.runMixPlayoutCycle(
                ctx.sessionId,
                wallMs,
                resolved.slot,
                resolved.slotMediaTimeMs,
                tickMediaTimeMs,
            )
        }

        val topK =
            wiring.orchestrator(ctx.sessionId)!!.selection.currentTopK().members
                .map { it.sourceIdentity }
                .toSet()
        assertEquals(setOf("M01", "M03", "M04"), topK)
        assertFalse(topK.contains(LOCAL))

        assertF92CorrectnessControls(ctx, pipeline, observations)

        val steadyWindow = observations.drop(WARMUP_CYCLES)
        val m04 = steadyWindow.map { it.m04 }
        val m04Straddle = m04.count { it.straddlesResolved }
        val m04DecodePulls = m04.count { it.pullOutcome == SlotPullDisposition.DECODE_FRAME }
        val m04BlockedWithRealFrame =
            m04.count {
                it.phase == LateJoinSourcePlayoutActivationFence.ConsumerPlayoutPhase.STEADY &&
                    it.effectivePullS != null &&
                    it.realFrameAtEffectivePull &&
                    it.pullOutcome == SlotPullDisposition.EMPTY &&
                    !it.steadyHoldActive
            }

        assertTrue(
            "M04 must straddle shared resolved M or decode-pull in steady ingress " +
                "(straddle=$m04Straddle/${steadyWindow.size} decodePulls=$m04DecodePulls " +
                "blockedWithFrame=$m04BlockedWithRealFrame)",
            m04Straddle > 0 || m04DecodePulls >= MIN_M04_DECODE_PULLS,
        )
        assertTrue(
            "M04 STEADY with real frame at effectivePull must not persist EMPTY without steady hold " +
                "(blocked=$m04BlockedWithRealFrame/${steadyWindow.size})",
            m04BlockedWithRealFrame <= MAX_BLOCKED_REAL_FRAME_CYCLES,
        )
    }

    private fun assertF92CorrectnessControls(
        ctx: FixtureCtx,
        pipeline: com.talkback.core.conference.runtime.MediaExecutionPipeline,
        observations: List<CycleObservation>,
    ) {
        val m03Alignment =
            pipeline.mixPlayoutAlignment(ctx.m03.moduleId, ctx.m03.incarnationId)
                ?: error("M03 alignment missing")
        val m04Alignment =
            pipeline.mixPlayoutAlignment(ctx.m04.moduleId, ctx.m04.incarnationId)
                ?: error("M04 alignment missing")

        assertTrue(
            "M03 offset≈$M03_OFFSET (got ${m03Alignment.offset})",
            abs(m03Alignment.offset - M03_OFFSET) <= 2L,
        )
        assertTrue(
            "M04 offset≈$M04_OFFSET (got ${m04Alignment.offset})",
            abs(m04Alignment.offset - M04_OFFSET) <= 2L,
        )

        val tail = observations.takeLast(20)
        for (obs in tail) {
            val m03Lag = obs.m03.normalizedLatest?.let { obs.resolvedM - it }
            assertTrue(
                "M03 normalized lag should track offset≈$M03_OFFSET (resolved=${obs.resolvedM} latestM=${obs.m03.normalizedLatest})",
                m03Lag == null || abs(m03Lag) <= M03_OFFSET + NORMALIZED_LAG_TOLERANCE,
            )
        }

        assertNotNull("M04 alignment must exist without 500-slot failure", m04Alignment)
        assertTrue(
            "M04 offset normalization must not collapse to fake 500-slot misalignment",
            abs(m04Alignment.offset - M04_OFFSET) <= 2L,
        )
    }

    private fun observeCycle(
        pipeline: com.talkback.core.conference.runtime.MediaExecutionPipeline,
        ctx: FixtureCtx,
        targetM: Long,
        resolvedM: Long,
        tickMediaTimeMs: Long,
    ): CycleObservation {
        val m01 = sourceObservation(pipeline, ctx.m01, resolvedM, tickMediaTimeMs)
        val m03 = sourceObservation(pipeline, ctx.m03, resolvedM, tickMediaTimeMs)
        val m04 = sourceObservation(pipeline, ctx.m04, resolvedM, tickMediaTimeMs)
        return CycleObservation(
            targetM = targetM,
            resolvedM = resolvedM,
            m01 = m01,
            m03 = m03,
            m04 = m04,
        )
    }

    private fun sourceObservation(
        pipeline: com.talkback.core.conference.runtime.MediaExecutionPipeline,
        binding: MemberBindingFact,
        resolvedM: Long,
        tickMediaTimeMs: Long,
    ): SourceObservation {
        val snap =
            LateJoinSourcePlayoutActivationFence.snapshotFrom(
                pipeline,
                binding.moduleId,
                binding.incarnationId,
            )
        val alignment = snap.alignment
        val mappedS = alignment?.sourceSlotForMix(resolvedM)
        val normalizedEarliest = snap.earliestBufferedSourceSlot?.let { alignment?.mixSlotForSource(it) }
        val normalizedLatest = snap.latestBufferedSourceSlot?.let { alignment?.mixSlotForSource(it) }
        val straddles =
            normalizedEarliest != null &&
                normalizedLatest != null &&
                normalizedEarliest <= resolvedM &&
                resolvedM <= normalizedLatest
        val effectivePullS =
            LateJoinSourcePlayoutActivationFence.resolveMutationProducingPullSourceSlot(
                snap,
                resolvedM,
            )
        val realFrameAtEffectivePull =
            effectivePullS != null &&
                pipeline.peekBufferedFrame(binding.moduleId, binding.incarnationId, effectivePullS) != null
        val steadyHold =
            if (snap.activationFirstConsumptionPending ||
                LateJoinSourcePlayoutActivationFence.consumerPlayoutPhase(snap) !=
                    LateJoinSourcePlayoutActivationFence.ConsumerPlayoutPhase.STEADY
            ) {
                false
            } else {
                val next = snap.nextExpectedSourceSlot
                val expectedReal =
                    next != null &&
                        next == snap.earliestBufferedSourceSlot &&
                        pipeline.peekBufferedFrame(binding.moduleId, binding.incarnationId, next) != null
                pipeline.steadyCoverageMarginPolicyForHarness().shouldHoldSteadyConsumption(
                    snapshot = snap,
                    sourceIdentity = binding.moduleId,
                    incarnationId = binding.incarnationId,
                    expectedRealFrameAvailable = expectedReal,
                )
            }
        val pullOutcome =
            predictPullDisposition(
                pipeline = pipeline,
                binding = binding,
                effectivePullS = effectivePullS,
                steadyHold = steadyHold,
                slotMediaTimeMs = tickMediaTimeMs,
                nowMs = tickMediaTimeMs,
            )
        return SourceObservation(
            phase = LateJoinSourcePlayoutActivationFence.consumerPlayoutPhase(snap),
            mappedS = mappedS,
            next = snap.nextExpectedSourceSlot,
            earliest = snap.earliestBufferedSourceSlot,
            latest = snap.latestBufferedSourceSlot,
            normalizedEarliest = normalizedEarliest,
            normalizedLatest = normalizedLatest,
            straddlesResolved = straddles,
            effectivePullS = effectivePullS,
            realFrameAtEffectivePull = realFrameAtEffectivePull,
            steadyHoldActive = steadyHold,
            pullOutcome = pullOutcome,
        )
    }

    private fun predictPullDisposition(
        pipeline: com.talkback.core.conference.runtime.MediaExecutionPipeline,
        binding: MemberBindingFact,
        effectivePullS: Long?,
        steadyHold: Boolean,
        slotMediaTimeMs: Long,
        nowMs: Long,
    ): SlotPullDisposition? {
        if (effectivePullS == null) return null
        if (steadyHold) return SlotPullDisposition.EMPTY
        if (!pipeline.isJitterExecutable(binding.moduleId, binding.incarnationId)) {
            return SlotPullDisposition.FENCED_SKIP
        }
        val eligible =
            pipeline.selection.isDecodeEligible(binding.moduleId, binding.incarnationId)
        val frame =
            pipeline.peekBufferedFrame(binding.moduleId, binding.incarnationId, effectivePullS)
        if (frame != null) {
            return if (eligible) SlotPullDisposition.DECODE_FRAME else SlotPullDisposition.EMPTY
        }
        return if (nowMs <= slotMediaTimeMs + MediaJitterConstants.MAX_PLAYOUT_DELAY_MS) {
            SlotPullDisposition.EMPTY
        } else {
            SlotPullDisposition.PLC_SYNTHESIS
        }
    }

    private fun establishMultiSourceFixture(): FixtureCtx {
        val sessionId = "a3c-consumer-lifecycle"
        val sessionStartMs = wallMs
        assertTrue(
            ConferenceSessionMediaBridge.startSession(
                SessionMediaWiringHarness.sessionFact(sessionId).copy(startedAtMs = wallMs),
            ),
        )
        val m01 = SessionMediaWiringHarness.memberBinding("M01", incarnationId = 1L)
        val local = SessionMediaWiringHarness.memberBinding(LOCAL, incarnationId = 2L)
        assertTrue(wiring.installMember(sessionId, m01))
        assertTrue(wiring.installMember(sessionId, local))

        val orchestrator = wiring.orchestrator(sessionId)!!
        orchestrator.pipeline.selection.localModuleIdForTopKExclusion = LOCAL
        observeVoice(orchestrator, m01, local)

        var slot = SESSION_ANCHOR
        repeat(ESTABLISH_CYCLES) {
            wallMs += MediaJitterConstants.MEDIA_SLOT_MS
            admitTone(sessionId, m01, slot, wallMs)
            admitTone(sessionId, local, slot, wallMs + 1)
            slot += 1
            val tickMediaTimeMs = wallMs - wallMs % MediaJitterConstants.MEDIA_SLOT_MS + MediaJitterConstants.MEDIA_SLOT_MS
            runResolvedCycle(sessionId, tickMediaTimeMs, wallMs)
        }
        orchestrator.selectTopK(wallMs)

        val mixRefAtJoin = slot.toLong()
        val m03 = SessionMediaWiringHarness.memberBinding("M03", incarnationId = 3L)
        val m04 = SessionMediaWiringHarness.memberBinding("M04", incarnationId = 4L)
        assertTrue(wiring.installMember(sessionId, m03))
        assertTrue(wiring.installMember(sessionId, m04))
        observeVoice(orchestrator, m03, m04)
        orchestrator.selectTopK(wallMs)

        val m03FirstSlot = (mixRefAtJoin - M03_OFFSET).toInt()
        val m04FirstSlot = (mixRefAtJoin - M04_OFFSET).toInt()
        for (s in m03FirstSlot..(m03FirstSlot + 2)) {
            admitTone(sessionId, m03, s, wallMs + s - m03FirstSlot)
        }
        for (s in m04FirstSlot..(m04FirstSlot + 2)) {
            admitTone(sessionId, m04, s, wallMs + s - m04FirstSlot + 4)
        }
        orchestrator.selectTopK(wallMs)

        return FixtureCtx(
            sessionId = sessionId,
            sessionStartMs = sessionStartMs,
            runSlot = slot,
            m01 = m01,
            m03 = m03,
            m04 = m04,
            local = local,
        )
    }

    private fun runResolvedCycle(
        sessionId: String,
        tickMediaTimeMs: Long,
        nowMs: Long,
    ) {
        val resolved = wiring.resolvePlayoutMixSlot(sessionId, tickMediaTimeMs) ?: return
        wiring.runMixPlayoutCycle(
            sessionId,
            nowMs,
            resolved.slot,
            resolved.slotMediaTimeMs,
            tickMediaTimeMs,
        )
    }

    private fun admitTone(
        sessionId: String,
        binding: MemberBindingFact,
        mediaSlot: Int,
        rxWallMs: Long,
    ): FrameAdmitDisposition {
        val result =
            wiring.admitProtectedDatagram(
                sessionId = sessionId,
                datagram = tonePacket(binding, mediaSlot),
                rxWallMs = rxWallMs,
            )
        return result.frameAdmit ?: FrameAdmitDisposition.NOT_ADMITTED_INCARNATION
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

    private fun observeVoice(
        orchestrator: ConferenceMediaExecutionOrchestrator,
        vararg bindings: MemberBindingFact,
    ) {
        bindings.forEachIndexed { index, binding ->
            orchestrator.observeVoice(
                VoiceLevelObservation(
                    sourceIdentity = binding.moduleId,
                    incarnationId = binding.incarnationId,
                    voiceActive = true,
                    audioLevel = 90 - index,
                ),
            )
        }
    }

    private data class FixtureCtx(
        val sessionId: String,
        val sessionStartMs: Long,
        var runSlot: Int,
        val m01: MemberBindingFact,
        val m03: MemberBindingFact,
        val m04: MemberBindingFact,
        val local: MemberBindingFact,
    )

    private data class SourceObservation(
        val phase: LateJoinSourcePlayoutActivationFence.ConsumerPlayoutPhase,
        val mappedS: Long?,
        val next: Long?,
        val earliest: Long?,
        val latest: Long?,
        val normalizedEarliest: Long?,
        val normalizedLatest: Long?,
        val straddlesResolved: Boolean,
        val effectivePullS: Long?,
        val realFrameAtEffectivePull: Boolean,
        val steadyHoldActive: Boolean,
        val pullOutcome: SlotPullDisposition?,
    )

    private data class CycleObservation(
        val targetM: Long,
        val resolvedM: Long,
        val m01: SourceObservation,
        val m03: SourceObservation,
        val m04: SourceObservation,
    )

    companion object {
        private const val LOCAL = "M02"
        private const val SESSION_ANCHOR = 0x5000
        private const val M03_OFFSET = 294L
        private const val M04_OFFSET = 572L
        private const val ESTABLISH_CYCLES = 400
        private const val RUN_CYCLES = 111
        private const val WARMUP_CYCLES = 25
        private const val NORMALIZED_LAG_TOLERANCE = 12L
        private const val MIN_M04_DECODE_PULLS = 10
        private const val MAX_BLOCKED_REAL_FRAME_CYCLES = 0

        /** Field 143138: resolved M advance while M04 in TopK without fresh ingress (~70 slots). */
        private const val FIELD_M04_IN_TOPK_STALE_SLOTS = 70
    }
}
