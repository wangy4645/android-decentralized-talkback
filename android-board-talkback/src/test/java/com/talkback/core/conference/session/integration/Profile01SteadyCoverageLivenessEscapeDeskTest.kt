package com.talkback.core.conference.session.integration

import com.talkback.core.conference.runtime.AdmittedMediaFrame
import com.talkback.core.conference.runtime.ConferenceMediaExecutionOrchestrator
import com.talkback.core.conference.runtime.FrameAdmitDisposition
import com.talkback.core.conference.runtime.MediaJitterConstants
import com.talkback.core.conference.runtime.VoiceLevelObservation
import com.talkback.core.conference.session.ConferenceSessionMediaBridge
import com.talkback.core.conference.session.ConferenceSessionMediaWiring
import com.talkback.core.conference.session.MemberBindingFact
import com.talkback.core.conference.session.SessionMediaWiringHarness
import com.talkback.core.conference.transport.LateJoinSourcePlayoutActivationFence
import com.talkback.core.conference.transport.LateJoinSteadyPhaseLeadContract
import com.talkback.core.conference.transport.RelativeMediaTimeline
import com.talkback.core.conference.transport.SteadyCoverageMarginPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Phase 2B — SteadyCoverage RECOVERING bounded liveness escape (contract GREEN).
 *
 * Invariant: STEADY recovery may delay consumption to rebuild coverage, but MUST NOT
 * indefinitely suppress consumption of the current expected real frame while ingress
 * remains consumable.
 */
class Profile01SteadyCoverageLivenessEscapeDeskTest {

    companion object {
        private const val NEXT = 22810L
        private const val LATEST = 22814L
        private const val FIRST_SOURCE_SLOT = 22800L
        private const val ALIGN_OFFSET = -2L
        private const val TRUSTED_SPAN = 4L
        private const val WITHIN_DEADLINE_SKEW_MS = 50L
        private const val OBSERVE_START_ORDINAL = 10
    }

    private lateinit var wiring: ConferenceSessionMediaWiring
    private var wallMs: Long = 0L
    private val scenarioCounter = AtomicInteger()

    @Before
    fun setUp() {
        MeetingProductMediaShadow.enabled = true
        wiring = ConferenceSessionMediaWiring.forHarness()
        ConferenceSessionMediaBridge.wiring = wiring
        wallMs = 1_794_100_000_000L + scenarioCounter.incrementAndGet()
    }

    @After
    fun tearDown() {
        ConferenceSessionMediaBridge.wiring = null
    }

    /** L1 — self-lock snapshot must not deadlock; escape after bounded hold budget. */
    @Test
    fun phase2b_selfLockSnapshot_boundedForwardProgressAfterHoldBudget() {
        val ctx = establishRecoveringHoldSnapshot()
        val pipeline = wiring.orchestrator(ctx.sessionId)!!.pipeline
        val holdBudget =
            pipeline.steadyCoverageMarginPolicyForHarness().livenessEscapeHoldCyclesForHarness()

        val samples = runObserveProduction(ctx, cycles = holdBudget + 5)
        assertTrue(
            "L1: first $holdBudget cycles must hold cursor (tail=${summarizeTail(samples)})",
            samples.take(holdBudget).all { it.m02Next == NEXT },
        )
        assertTrue(
            "L1: escape must advance cursor beyond next (tail=${summarizeTail(samples)})",
            (samples.last().m02Next ?: NEXT) > NEXT,
        )
        assertTrue(
            "L1: target must advance while recovering (tail=${summarizeTail(samples)})",
            targetAdvanced(samples),
        )
    }

    /** L2 — liveness escape consumes expected real frame only; no PLC from this policy. */
    @Test
    fun phase2b_escapePullUsesRealFrameNotPlc() {
        val ctx = establishRecoveringHoldSnapshot()
        val pipeline = wiring.orchestrator(ctx.sessionId)!!.pipeline
        val holdBudget =
            pipeline.steadyCoverageMarginPolicyForHarness().livenessEscapeHoldCyclesForHarness()
        val plcBefore = pipeline.plcCount

        runObserveProduction(ctx, cycles = holdBudget)
        assertEquals(NEXT, pipeline.nextExpectedSlot(ctx.m02.moduleId, ctx.m02.incarnationId))
        assertEquals(plcBefore, pipeline.plcCount)

        val snap =
            LateJoinSourcePlayoutActivationFence.snapshotFrom(
                pipeline,
                ctx.m02.moduleId,
                ctx.m02.incarnationId,
            )
        assertEquals(NEXT, snap.nextExpectedSourceSlot)
        assertEquals(NEXT, snap.earliestBufferedSourceSlot)
        assertTrue(pipeline.hasBufferedFrame(ctx.m02.moduleId, ctx.m02.incarnationId, NEXT))

        runObserveProduction(ctx, cycles = 1, cycleOrdinalStart = OBSERVE_START_ORDINAL + holdBudget)
        assertTrue(
            pipeline.nextExpectedSlot(ctx.m02.moduleId, ctx.m02.incarnationId)!! > NEXT,
        )
        assertEquals(
            "L2: escape must not synthesize PLC",
            plcBefore,
            pipeline.plcCount,
        )
    }

    /** L3 — sustained ingress during RECOVERING must retain rebuild opportunity. */
    @Test
    fun phase2b_recoveringWithIngress_retainsRebuildOpportunity() {
        val ctx = establishRecoveringHoldSnapshot()
        val pipeline = wiring.orchestrator(ctx.sessionId)!!.pipeline
        val policy = pipeline.steadyCoverageMarginPolicyForHarness()
        val holdBudget = policy.livenessEscapeHoldCyclesForHarness()
        val startSpan = currentTrustedSpan(ctx)!!
        assertEquals(TRUSTED_SPAN, startSpan)

        var peakSpan = startSpan
        var cycleOrdinal = OBSERVE_START_ORDINAL - 1
        var latestAdmitted = LATEST
        var prevNext = NEXT
        var advancesWhileRecovering = 0
        var cyclesWhileRecovering = 0
        val recoveryCycles = 30

        for (i in 0 until recoveryCycles) {
            cycleOrdinal += 1
            latestAdmitted += 1
            assertEquals(
                FrameAdmitDisposition.QUEUED,
                admitWithMixRef(
                    sessionId = ctx.sessionId,
                    binding = ctx.m02,
                    sourceSlot = latestAdmitted,
                    rxWallMs = ctx.withinDeadlineNowMs + i,
                    mixReferenceSlot = latestAdmitted + ALIGN_OFFSET,
                    mediaTimeMs =
                        ctx.nextMediaTimeMs +
                            (latestAdmitted - NEXT) * MediaJitterConstants.MEDIA_SLOT_MS,
                ),
            )
            val tickMediaTimeMs = wallMs + cycleOrdinal * MediaJitterConstants.MEDIA_SLOT_MS
            val resolved =
                wiring.resolvePlayoutMixSlot(ctx.sessionId, tickMediaTimeMs)
                    ?: wiring.resolveEarliestBufferedMixSlot(ctx.sessionId)
                    ?: break
            wiring.runMixPlayoutCycle(
                sessionId = ctx.sessionId,
                nowMs = ctx.withinDeadlineNowMs,
                slot = resolved.slot,
                slotMediaTimeMs = resolved.slotMediaTimeMs,
                tickMediaTimeMs = tickMediaTimeMs,
            )
            currentTrustedSpan(ctx)?.let { span -> peakSpan = maxOf(peakSpan, span) }
            if (
                policy.consumptionPhaseForHarness(ctx.m02.moduleId, ctx.m02.incarnationId) ==
                    SteadyCoverageMarginPolicy.ConsumptionPhase.RECOVERING
            ) {
                cyclesWhileRecovering += 1
                val curNext = pipeline.nextExpectedSlot(ctx.m02.moduleId, ctx.m02.incarnationId)!!
                if (curNext > prevNext) {
                    advancesWhileRecovering += 1
                    prevNext = curNext
                }
            }
        }

        assertTrue(
            "L3: coverage margin must rebuild (start=$startSpan peak=$peakSpan)",
            peakSpan > startSpan,
        )
        if (cyclesWhileRecovering > 0) {
            val maxEscapesAllowed = cyclesWhileRecovering / holdBudget + 1
            assertTrue(
                "L3: RECOVERING consumption must stay sparse " +
                    "(advances=$advancesWhileRecovering recoveringCycles=$cyclesWhileRecovering " +
                    "maxEscapes=$maxEscapesAllowed)",
                advancesWhileRecovering <= maxEscapesAllowed,
            )
        }
    }

    private fun currentTrustedSpan(ctx: EscapeCtx): Long? {
        val pipeline = wiring.orchestrator(ctx.sessionId)?.pipeline ?: return null
        val snap =
            LateJoinSourcePlayoutActivationFence.snapshotFrom(
                pipeline,
                ctx.m02.moduleId,
                ctx.m02.incarnationId,
            )
        val next = snap.nextExpectedSourceSlot ?: return null
        val latest = snap.latestBufferedSourceSlot ?: return null
        return LateJoinSteadyPhaseLeadContract.trustedIngressSpan(next, latest)
    }

    private fun runObserveProduction(
        ctx: EscapeCtx,
        cycles: Int,
        cycleOrdinalStart: Int = OBSERVE_START_ORDINAL - 1,
    ): List<ObserveSample> {
        val pipeline = wiring.orchestrator(ctx.sessionId)!!.pipeline
        pipeline.setSteadyCoverageHoldBypassForHarness(false)
        val samples = mutableListOf<ObserveSample>()
        var cycleOrdinal = cycleOrdinalStart
        repeat(cycles) {
            cycleOrdinal += 1
            samples += runSingleProductionCycle(
                ctx,
                wallMs + cycleOrdinal * MediaJitterConstants.MEDIA_SLOT_MS,
            )
        }
        return samples
    }

    private fun runSingleProductionCycle(
        ctx: EscapeCtx,
        tickMediaTimeMs: Long,
    ): ObserveSample {
        val pipeline = wiring.orchestrator(ctx.sessionId)!!.pipeline
        val target = wiring.sessionPlayoutTargetSlot(ctx.sessionId, tickMediaTimeMs)
        val resolved =
            wiring.resolvePlayoutMixSlot(ctx.sessionId, tickMediaTimeMs)
                ?: wiring.resolveEarliestBufferedMixSlot(ctx.sessionId)
                ?: error("expected resolved mix slot at tick=$tickMediaTimeMs")
        wiring.runMixPlayoutCycle(
            sessionId = ctx.sessionId,
            nowMs = ctx.withinDeadlineNowMs,
            slot = resolved.slot,
            slotMediaTimeMs = resolved.slotMediaTimeMs,
            tickMediaTimeMs = tickMediaTimeMs,
        )
        return ObserveSample(
            targetMixSlot = target,
            resolvedMixSlot = resolved.slot,
            m02Next = pipeline.nextExpectedSlot(ctx.m02.moduleId, ctx.m02.incarnationId),
        )
    }

    private fun establishRecoveringHoldSnapshot(): EscapeCtx {
        val sessionId = "phase2b-escape-${scenarioCounter.get()}"
        assertTrue(
            ConferenceSessionMediaBridge.startSession(
                SessionMediaWiringHarness.sessionFact(sessionId).copy(startedAtMs = wallMs),
            ),
        )
        val m02 = SessionMediaWiringHarness.memberBinding("M02", incarnationId = 2L)
        assertTrue(wiring.installMember(sessionId, m02))
        observeVoice(wiring.orchestrator(sessionId)!!, m02)
        wiring.orchestrator(sessionId)!!.selectTopK(wallMs)
        anchorSessionPlayoutTimeline(sessionId, FIRST_SOURCE_SLOT, wallMs + MediaJitterConstants.MEDIA_SLOT_MS)

        val pipeline = wiring.orchestrator(sessionId)!!.pipeline
        var pullWall = wallMs + 2 * MediaJitterConstants.MEDIA_SLOT_MS + MediaJitterConstants.MAX_PLAYOUT_DELAY_MS

        fun mediaTimeForSlot(slot: Long): Long =
            wallMs + (slot - FIRST_SOURCE_SLOT + 1) * MediaJitterConstants.MEDIA_SLOT_MS

        for (slot in FIRST_SOURCE_SLOT until NEXT) {
            assertEquals(
                FrameAdmitDisposition.QUEUED,
                admitWithMixRef(
                    sessionId,
                    m02,
                    slot,
                    pullWall,
                    slot + ALIGN_OFFSET,
                    mediaTimeForSlot(slot),
                ),
            )
            pipeline.pullSlot(
                sourceIdentity = m02.moduleId,
                incarnationId = m02.incarnationId,
                slot = slot,
                slotMediaTimeMs = mediaTimeForSlot(slot),
                nowMs = pullWall + MediaJitterConstants.MAX_PLAYOUT_DELAY_MS + 5L,
            )
            pullWall += MediaJitterConstants.MEDIA_SLOT_MS
        }

        val activationMixSlot = (NEXT - 1) + ALIGN_OFFSET
        assertEquals(
            FrameAdmitDisposition.QUEUED,
            admitWithMixRef(
                sessionId,
                m02,
                NEXT - 1,
                pullWall,
                activationMixSlot,
                mediaTimeForSlot(NEXT - 1),
            ),
        )
        pipeline.pullSlotForSharedMixPlayout(
            sourceIdentity = m02.moduleId,
            incarnationId = m02.incarnationId,
            sharedMixPlayoutSlot = activationMixSlot,
            resolvedMixSlotMediaTimeMs = mediaTimeForSlot(NEXT - 1),
            nowMs = pullWall + MediaJitterConstants.MAX_PLAYOUT_DELAY_MS + 5L,
            effectivePullSourceSlot = NEXT - 1,
        )
        assertEquals(NEXT, pipeline.nextExpectedSlot(m02.moduleId, m02.incarnationId))

        var tickWall = pullWall + MediaJitterConstants.MEDIA_SLOT_MS
        for (slot in NEXT..LATEST) {
            assertEquals(
                FrameAdmitDisposition.QUEUED,
                admitWithMixRef(
                    sessionId,
                    m02,
                    slot,
                    tickWall,
                    slot + ALIGN_OFFSET,
                    mediaTimeForSlot(slot),
                ),
            )
            tickWall += MediaJitterConstants.MEDIA_SLOT_MS
        }

        pipeline.steadyCoverageMarginPolicyForHarness().setConsumptionPhaseForHarness(
            sourceIdentity = m02.moduleId,
            incarnationId = m02.incarnationId,
            phase = SteadyCoverageMarginPolicy.ConsumptionPhase.RECOVERING,
        )
        pipeline.setSteadyCoverageHoldBypassForHarness(false)

        val snap =
            LateJoinSourcePlayoutActivationFence.snapshotFrom(
                pipeline,
                m02.moduleId,
                m02.incarnationId,
            )
        assertEquals(NEXT, snap.nextExpectedSourceSlot)
        assertEquals(NEXT, snap.earliestBufferedSourceSlot)
        assertEquals(LATEST, snap.latestBufferedSourceSlot)
        assertEquals(TRUSTED_SPAN, currentTrustedSpan(EscapeCtx(sessionId, m02, mediaTimeForSlot(NEXT), 0))!!)

        return EscapeCtx(
            sessionId = sessionId,
            m02 = m02,
            nextMediaTimeMs = mediaTimeForSlot(NEXT),
            withinDeadlineNowMs = mediaTimeForSlot(NEXT) + WITHIN_DEADLINE_SKEW_MS,
        )
    }

    private fun targetAdvanced(tail: List<ObserveSample>): Boolean {
        val targets = tail.mapNotNull { it.targetMixSlot }
        return targets.size >= 2 && targets.last() > targets.first()
    }

    private fun summarizeTail(tail: List<ObserveSample>): String {
        if (tail.isEmpty()) return "empty"
        val first = tail.first()
        val last = tail.last()
        return "resolved ${first.resolvedMixSlot}->${last.resolvedMixSlot} " +
            "next ${first.m02Next}->${last.m02Next} " +
            "target ${first.targetMixSlot}->${last.targetMixSlot}"
    }

    private fun anchorSessionPlayoutTimeline(
        sessionId: String,
        baseSlot: Long,
        anchorWallMs: Long,
    ) {
        val sessionsField = ConferenceSessionMediaWiring::class.java.getDeclaredField("sessions")
        sessionsField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val sessions = sessionsField.get(wiring) as LinkedHashMap<String, Any>
        val state = sessions[sessionId] ?: error("missing session $sessionId")
        val timelineField = state.javaClass.getDeclaredField("mediaTimeline")
        timelineField.isAccessible = true
        val timeline = timelineField.get(state) as RelativeMediaTimeline
        timeline.mediaTimeMs(baseSlot.toInt(), anchorWallMs)
    }

    private fun admitWithMixRef(
        sessionId: String,
        binding: MemberBindingFact,
        sourceSlot: Long,
        rxWallMs: Long,
        mixReferenceSlot: Long,
        mediaTimeMs: Long,
    ): FrameAdmitDisposition =
        wiring.orchestrator(sessionId)!!.admitFrame(
            AdmittedMediaFrame(
                sourceIdentity = binding.moduleId,
                incarnationId = binding.incarnationId,
                mediaSlot = sourceSlot,
                mediaTimeMs = mediaTimeMs,
                arrivalMs = rxWallMs,
            ),
            nowMs = rxWallMs,
            sharedMixReferenceSlot = mixReferenceSlot,
        )

    private fun observeVoice(
        orchestrator: ConferenceMediaExecutionOrchestrator,
        binding: MemberBindingFact,
    ) {
        orchestrator.observeVoice(
            VoiceLevelObservation(
                sourceIdentity = binding.moduleId,
                incarnationId = binding.incarnationId,
                voiceActive = true,
                audioLevel = 95,
            ),
        )
    }

    private data class EscapeCtx(
        val sessionId: String,
        val m02: MemberBindingFact,
        val nextMediaTimeMs: Long,
        val withinDeadlineNowMs: Long,
    )

    private data class ObserveSample(
        val targetMixSlot: Long?,
        val resolvedMixSlot: Long?,
        val m02Next: Long?,
    )
}
