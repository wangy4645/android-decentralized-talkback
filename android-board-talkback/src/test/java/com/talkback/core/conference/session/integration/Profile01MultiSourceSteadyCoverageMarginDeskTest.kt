package com.talkback.core.conference.session.integration

import com.talkback.core.conference.runtime.ConferenceMediaExecutionOrchestrator
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
import kotlin.math.max

/**
 * Issue A — Profile01 multi-source steady playout coverage-margin desk (GREEN target).
 *
 * Field-isomorphic to apm-fix-retest DIRTY:
 * dual Top-K → establish usable coverage → bounded disturb → recover 50pps.
 *
 * Correctness KEEP: cursor monotonic, shared M continuous, Case-C hole policy.
 * RED target: Phase C fails to reconverge coverage after disturbance ends.
 */
class Profile01MultiSourceSteadyCoverageMarginDeskTest {

    companion object {
        private const val SESSION_ANCHOR = 0x5000 // 20480
        /** Prefill toward Profile03 120ms / 6-slot budget (establish only; not a RED assert). */
        private const val PREFILL_SLOTS = 6
        private const val PHASE_A_CYCLES = 40
        private const val PHASE_C_CYCLES = 80
        private const val MIX_BURST_CYCLES = 40
        private const val REORDER_JUMP_SLOTS = 8
        private const val INGRESS_GAP_SLOTS = 4

        private const val CASE_C_MISSING = 20579L
        private const val CASE_C_EARLIEST = 20580L
        private const val CASE_C_OFFSET = 442L
    }

    private lateinit var wiring: ConferenceSessionMediaWiring
    private var wallMs: Long = 0L
    private val scenarioCounter = AtomicInteger()

    @Before
    fun setUp() {
        MeetingProductMediaShadow.enabled = true
        wiring = ConferenceSessionMediaWiring.forHarness()
        ConferenceSessionMediaBridge.wiring = wiring
        wallMs = 1_791_000_000_000L + scenarioCounter.incrementAndGet()
    }

    @After
    fun tearDown() {
        ConferenceSessionMediaBridge.wiring = null
    }

    @Test
    fun multiSource_steadyDisturbRecover_coverageMarginShouldReconverge() {
        assertCaseCSteadyHolePolicyIntact()

        val ctx = establishDualSourceSession()
        val pipeline = wiring.orchestrator(ctx.sessionId)!!.pipeline
        val samples = mutableListOf<CycleSample>()
        val monotonic = MonotonicNextTracker()

        var tickWall = ctx.startWallMs
        var m02Slot = SESSION_ANCHOR
        var m03Slot = SESSION_ANCHOR
        var cycleOrdinal = 0

        // Prefill both sources without pulling — create initial coverage headroom.
        for (i in 0 until PREFILL_SLOTS) {
            tickWall += MediaJitterConstants.MEDIA_SLOT_MS
            assertEquals(
                FrameAdmitDisposition.QUEUED,
                admitTone(ctx.sessionId, ctx.m02, m02Slot, tickWall),
            )
            assertEquals(
                FrameAdmitDisposition.QUEUED,
                admitTone(ctx.sessionId, ctx.m03, m03Slot, tickWall + 1),
            )
            m02Slot += 1
            m03Slot += 1
        }
        val depthAfterPrefill = pipeline.jitterSize(ctx.m02.moduleId, ctx.m02.incarnationId)
        assertTrue(
            "Prefill should leave M02 buffered (got depth=$depthAfterPrefill)",
            depthAfterPrefill >= PREFILL_SLOTS - 1,
        )

        // ── Phase A: steady dual-source 50pps (admit 1 + pull 1) ──
        for (cycle in 0 until PHASE_A_CYCLES) {
            tickWall += MediaJitterConstants.MEDIA_SLOT_MS
            cycleOrdinal += 1
            val tickMediaTimeMs = wallMs + cycleOrdinal * MediaJitterConstants.MEDIA_SLOT_MS
            admitTone(ctx.sessionId, ctx.m02, m02Slot, tickWall)
            admitTone(ctx.sessionId, ctx.m03, m03Slot, tickWall + 1)
            val depthBeforePull =
                pipeline.jitterSize(ctx.m02.moduleId, ctx.m02.incarnationId)
            m02Slot += 1
            m03Slot += 1
            val resolvedSlot = runProductionCycle(ctx.sessionId, tickMediaTimeMs, tickWall)
            samples +=
                sampleCycle(
                    ctx,
                    tickMediaTimeMs,
                    pipeline,
                    monotonic,
                    resolvedSlot,
                    depthBeforePull,
                )
        }

        val phaseA = samples.take(PHASE_A_CYCLES)
        val phaseADepthMean = phaseA.map { it.m02DepthBeforePull }.average()
        assertTrue(
            "Phase A: healthy baseline should keep M02 depth before pull (mean=$phaseADepthMean)",
            phaseADepthMean >= 2.0,
        )
        assertTrue("Phase A: shared M advances", sharedMixAdvanced(phaseA))
        assertTrue("Phase A: cursor monotonic", monotonic.violations.isEmpty())

        val phaseAEmptyDuty =
            phaseA.count { it.m02DepthBeforePull == 0 }.toDouble() / phaseA.size
        val phaseAPlc = pipeline.plcCount

        // ── Phase B: bounded disturbance ──
        val reorderJumpSlot = m02Slot + REORDER_JUMP_SLOTS
        admitTone(ctx.sessionId, ctx.m02, reorderJumpSlot, tickWall + 2)
        for (backfill in 0 until REORDER_JUMP_SLOTS) {
            wiring.admitProtectedDatagram(
                sessionId = ctx.sessionId,
                datagram = tonePacket(ctx.m02, m02Slot + backfill),
                rxWallMs = tickWall + 3 + backfill,
            )
        }
        m02Slot = reorderJumpSlot + 1
        m02Slot += INGRESS_GAP_SLOTS

        val phaseBStart = samples.size
        var disturbEmptyStreak = 0
        var disturbMaxEmptyStreak = 0
        repeat(MIX_BURST_CYCLES) {
            tickWall += MediaJitterConstants.MEDIA_SLOT_MS
            cycleOrdinal += 1
            val tickMediaTimeMs = wallMs + cycleOrdinal * MediaJitterConstants.MEDIA_SLOT_MS
            // Dual-source load: M03 continues; M02 starved this window.
            admitTone(ctx.sessionId, ctx.m03, m03Slot, tickWall + 1)
            m03Slot += 1
            val depthBeforePull =
                pipeline.jitterSize(ctx.m02.moduleId, ctx.m02.incarnationId)
            val resolvedSlot = runProductionCycle(ctx.sessionId, tickMediaTimeMs, tickWall)
            val s =
                sampleCycle(
                    ctx,
                    tickMediaTimeMs,
                    pipeline,
                    monotonic,
                    resolvedSlot,
                    depthBeforePull,
                )
            samples += s
            disturbEmptyStreak =
                if (s.m02DepthBeforePull == 0) {
                    disturbEmptyStreak + 1
                } else {
                    disturbMaxEmptyStreak = max(disturbMaxEmptyStreak, disturbEmptyStreak)
                    0
                }
        }
        disturbMaxEmptyStreak = max(disturbMaxEmptyStreak, disturbEmptyStreak)

        val phaseB = samples.drop(phaseBStart)
        val phaseBEmptyDuty =
            phaseB.count { it.m02DepthBeforePull == 0 }.toDouble() / phaseB.size
        assertTrue(
            "Phase B: coverage erosion observed " +
                "(A emptyDuty=$phaseAEmptyDuty B emptyDuty=$phaseBEmptyDuty " +
                "maxEmptyStreak=$disturbMaxEmptyStreak)",
            phaseBEmptyDuty > phaseAEmptyDuty || disturbMaxEmptyStreak >= 10,
        )
        assertTrue("Phase B: cursor monotonic", monotonic.violations.isEmpty())

        // ── Phase C: recover — stable 50pps both, no further disturbance ──
        val phaseCStart = samples.size
        for (cycle in 0 until PHASE_C_CYCLES) {
            tickWall += MediaJitterConstants.MEDIA_SLOT_MS
            cycleOrdinal += 1
            val tickMediaTimeMs = wallMs + cycleOrdinal * MediaJitterConstants.MEDIA_SLOT_MS
            admitTone(ctx.sessionId, ctx.m02, m02Slot, tickWall)
            admitTone(ctx.sessionId, ctx.m03, m03Slot, tickWall + 1)
            val depthBeforePull =
                pipeline.jitterSize(ctx.m02.moduleId, ctx.m02.incarnationId)
            m02Slot += 1
            m03Slot += 1
            val resolvedSlot = runProductionCycle(ctx.sessionId, tickMediaTimeMs, tickWall)
            samples +=
                sampleCycle(
                    ctx,
                    tickMediaTimeMs,
                    pipeline,
                    monotonic,
                    resolvedSlot,
                    depthBeforePull,
                )
        }

        val phaseC = samples.drop(phaseCStart)
        assertTrue("Phase C: shared M continues", sharedMixAdvanced(phaseC))
        assertTrue(
            "Phase C: cursor monotonic ${monotonic.violations}",
            monotonic.violations.isEmpty(),
        )

        val recoveryTail = phaseC.takeLast(20)
        val emptyDutyTail =
            recoveryTail.count { it.m02DepthBeforePull == 0 }.toDouble() / recoveryTail.size
        val depthMeanTail = recoveryTail.map { it.m02DepthBeforePull }.average()
        val sustainedEmptyTail = recoveryTail.takeLast(5).all { it.m02DepthBeforePull == 0 }
        val lagRunaway = targetResolvedLagRunaway(recoveryTail)
        val plcDelta = pipeline.plcCount - phaseAPlc
        // Usable margin = comparable to Phase A headroom after prefill+steady.
        // Do NOT require absolute depth>=6 here — that is the future GREEN target budget.
        val marginNotRecovered = depthMeanTail < phaseADepthMean * 0.5
        val coverageFailedToRecover =
            marginNotRecovered ||
                sustainedEmptyTail ||
                emptyDutyTail >= 0.5 ||
                lagRunaway

        assertFalse(
            "Phase C: coverage should reconverge after stable ingress " +
                "(A depthMean=$phaseADepthMean C depthMean=$depthMeanTail " +
                "emptyDuty=$emptyDutyTail sustainedEmpty=$sustainedEmptyTail " +
                "lagRunaway=$lagRunaway plcDelta=$plcDelta)",
            coverageFailedToRecover,
        )
    }

    /** Case-C KEEP — snapshot-only; does not mutate the dual-source run. */
    private fun assertCaseCSteadyHolePolicyIntact() {
        val snap =
            LateJoinSourcePlayoutActivationFence.SourceActivationSnapshot(
                alignment =
                    SourceMixPlayoutAlignment(
                        mixReferenceSlot = CASE_C_MISSING + CASE_C_OFFSET,
                        sourceReferenceSlot = CASE_C_MISSING,
                    ),
                nextExpectedSourceSlot = CASE_C_MISSING,
                earliestBufferedSourceSlot = CASE_C_EARLIEST,
                latestBufferedSourceSlot = CASE_C_EARLIEST + 3L,
                playoutExpirationFrontierExclusive = null,
                activationFirstConsumptionPending = false,
            )
        assertEquals(
            LateJoinSourcePlayoutActivationFence.ConsumerPlayoutPhase.STEADY,
            LateJoinSourcePlayoutActivationFence.consumerPlayoutPhase(snap),
        )
        assertEquals(
            CASE_C_MISSING,
            LateJoinSourcePlayoutActivationFence.resolveMutationProducingPullSourceSlot(
                snap,
                CASE_C_MISSING + CASE_C_OFFSET,
            ),
        )
        assertEquals(
            CASE_C_MISSING,
            LateJoinSourcePlayoutActivationFence.resolveMutationProducingPullSourceSlot(
                snap,
                CASE_C_EARLIEST + CASE_C_OFFSET,
            ),
        )
    }

    private fun establishDualSourceSession(): DualSourceCtx {
        val sessionId = "multi-src-margin-${scenarioCounter.get()}"
        assertTrue(
            ConferenceSessionMediaBridge.startSession(
                SessionMediaWiringHarness.sessionFact(sessionId).copy(startedAtMs = wallMs),
            ),
        )
        val m02 = SessionMediaWiringHarness.memberBinding("M02", incarnationId = 2L)
        val m03 = SessionMediaWiringHarness.memberBinding("M03", incarnationId = 3L)
        assertTrue(wiring.installMember(sessionId, m02))
        assertTrue(wiring.installMember(sessionId, m03))
        observeVoice(wiring.orchestrator(sessionId)!!, m02, m03)
        wiring.orchestrator(sessionId)!!.selectTopK(wallMs)
        assertEquals(
            setOf("M02", "M03"),
            wiring.orchestrator(sessionId)!!.selection.currentTopK().members
                .map { it.sourceIdentity }
                .toSet(),
        )
        return DualSourceCtx(
            sessionId = sessionId,
            m02 = m02,
            m03 = m03,
            startWallMs = wallMs + MediaJitterConstants.MEDIA_SLOT_MS,
        )
    }

    private fun sampleCycle(
        ctx: DualSourceCtx,
        tickMediaTimeMs: Long,
        pipeline: com.talkback.core.conference.runtime.MediaExecutionPipeline,
        monotonic: MonotonicNextTracker,
        resolvedMixSlot: Long?,
        depthBeforePull: Int,
    ): CycleSample {
        val target = wiring.sessionPlayoutTargetSlot(ctx.sessionId, tickMediaTimeMs)
        monotonic.record("M02", pipeline.nextExpectedSlot(ctx.m02.moduleId, ctx.m02.incarnationId))
        monotonic.record("M03", pipeline.nextExpectedSlot(ctx.m03.moduleId, ctx.m03.incarnationId))
        return CycleSample(
            m02DepthBeforePull = depthBeforePull,
            m02DepthAfterPull = pipeline.jitterSize(ctx.m02.moduleId, ctx.m02.incarnationId),
            targetMixSlot = target,
            resolvedMixSlot = resolvedMixSlot,
            plcCount = pipeline.plcCount,
        )
    }

    private fun sharedMixAdvanced(samples: List<CycleSample>): Boolean {
        val resolved = samples.mapNotNull { it.resolvedMixSlot }
        if (resolved.size < 2) return false
        return resolved.last() > resolved.first()
    }

    private fun targetResolvedLagRunaway(tail: List<CycleSample>): Boolean {
        if (tail.size < 5) return false
        val gaps =
            tail.mapNotNull { sample ->
                val target = sample.targetMixSlot ?: return@mapNotNull null
                val resolved = sample.resolvedMixSlot ?: return@mapNotNull null
                target - resolved
            }
        if (gaps.size < 5) return false
        val lastFive = gaps.takeLast(5)
        return lastFive.zipWithNext().all { (a, b) -> b >= a } &&
            lastFive.last() > lastFive.first() + 10
    }

    private fun runProductionCycle(
        sessionId: String,
        tickMediaTimeMs: Long,
        nowMs: Long,
    ): Long? {
        val resolved = wiring.resolvePlayoutMixSlot(sessionId, tickMediaTimeMs) ?: return null
        wiring.runMixPlayoutCycle(
            sessionId,
            nowMs,
            resolved.slot,
            resolved.slotMediaTimeMs,
            tickMediaTimeMs,
        )
        return resolved.slot
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
                    audioLevel = 95 - index,
                ),
            )
        }
    }

    private data class DualSourceCtx(
        val sessionId: String,
        val m02: MemberBindingFact,
        val m03: MemberBindingFact,
        val startWallMs: Long,
    )

    private data class CycleSample(
        val m02DepthBeforePull: Int,
        val m02DepthAfterPull: Int,
        val targetMixSlot: Long?,
        val resolvedMixSlot: Long?,
        val plcCount: Int,
    )

    private class MonotonicNextTracker {
        val violations = mutableListOf<String>()
        private val lastBySource = mutableMapOf<String, Long>()

        fun record(
            sourceId: String,
            next: Long?,
        ) {
            if (next == null) return
            lastBySource[sourceId]?.let { prev ->
                if (next < prev) {
                    violations += "$sourceId regressed $prev->$next"
                }
            }
            lastBySource[sourceId] = next
        }
    }
}
