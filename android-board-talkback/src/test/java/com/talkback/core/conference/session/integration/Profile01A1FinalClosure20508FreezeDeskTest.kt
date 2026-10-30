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
import com.talkback.core.conference.transport.RelativeMediaTimeline
import com.talkback.core.conference.transport.SteadyCoverageMarginPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Issue A1 — field-isomorphic desk for `resolvedMixSlot=20508` permanent freeze.
 *
 * Evidence: `talkback/logs/issue-a1-final-closure-field-20260923-091956/M01.logcat`
 * session `98ea8871-7b8f-422e-b8cd-73d48e51fe9c`.
 */
class Profile01A1FinalClosure20508FreezeDeskTest {

    companion object {
        private const val HOLE_NEXT = 20507L
        private const val HOLE_EARLIEST = 20508L
        private const val RESOLVED_AT_FREEZE = 20508L
        private const val FIRST_SOURCE_SLOT = 20480L
        private const val ALIGN_OFFSET = 0L
        private const val OBSERVE_CYCLES = 20
        private const val PROGRESS_WINDOW_CYCLES = 6
        private const val WITHIN_DEADLINE_SKEW_MS = 50L
    }

    private lateinit var wiring: ConferenceSessionMediaWiring
    private var wallMs: Long = 0L
    private val scenarioCounter = AtomicInteger()

    @Before
    fun setUp() {
        MeetingProductMediaShadow.enabled = true
        wiring = ConferenceSessionMediaWiring.forHarness()
        ConferenceSessionMediaBridge.wiring = wiring
        wallMs = 1_790_126_597_000L + scenarioCounter.incrementAndGet()
    }

    @After
    fun tearDown() {
        ConferenceSessionMediaBridge.wiring = null
    }

    /**
     * GREEN-A: offset==0 STEADY hole — fence returns consumer cursor, not mapped S.
     */
    @Test
    fun a1_greenA_offsetZeroConfirmedHole_effectivePullMustTargetCursorNotMapped() {
        val ctx = establishFieldHoleSnapshot()
        val pipeline = wiring.orchestrator(ctx.sessionId)!!.pipeline
        val snap =
            LateJoinSourcePlayoutActivationFence.snapshotFrom(
                pipeline,
                ctx.m02.moduleId,
                ctx.m02.incarnationId,
            )
        assertTrue(isCaseCHole(snap, RESOLVED_AT_FREEZE))
        assertEquals(
            HOLE_NEXT,
            LateJoinSourcePlayoutActivationFence.resolveMutationProducingPullSourceSlot(
                snap,
                RESOLVED_AT_FREEZE,
            ),
        )
    }

    /**
     * GREEN-B: Case-C cursor recovery survives RECOVERING HOLD — no permanent freeze.
     */
    @Test
    fun a1_greenB_offsetZeroCaseCHoleWithRecoveringHold_cursorCrossesHoleAndResolvedProgresses() {
        val ctx = establishFieldHoleSnapshot(seedRecoveringHold = true)
        assertEquals(RESOLVED_AT_FREEZE, ctx.baselineResolved)

        val tail = runObserveWindow(ctx)

        assertFalse(
            "Case-C must not leave cursor at $HOLE_NEXT (tail=${summarizeTail(tail)})",
            cursorFrozenAtHole(tail),
        )
        assertTrue(
            "cursor must cross confirmed hole (tail=${summarizeTail(tail)})",
            (tail.last().m02Next ?: HOLE_NEXT) > HOLE_NEXT,
        )
        assertFalse(
            "resolved tail must not stay pinned at $RESOLVED_AT_FREEZE (tail=${summarizeTail(tail)})",
            resolvedFrozenAtBaseline(tail.takeLast(PROGRESS_WINDOW_CYCLES), RESOLVED_AT_FREEZE),
        )
        assertTrue(
            "target must advance (tail=${summarizeTail(tail)})",
            targetAdvanced(tail),
        )
        assertTrue(
            "resolved must make forward progress (tail=${summarizeTail(tail)})",
            resolvedAdvanced(tail, ctx.baselineResolved),
        )
    }

    private fun isCaseCHole(
        snap: LateJoinSourcePlayoutActivationFence.SourceActivationSnapshot,
        sharedMixPlayoutSlot: Long,
    ): Boolean {
        val alignment = snap.alignment ?: return false
        val next = snap.nextExpectedSourceSlot ?: return false
        val earliest = snap.earliestBufferedSourceSlot ?: return false
        val mapped = alignment.sourceSlotForMix(sharedMixPlayoutSlot)
        return next < earliest && mapped >= next
    }

    private fun cursorFrozenAtHole(tail: List<ObserveSample>): Boolean {
        if (tail.size < PROGRESS_WINDOW_CYCLES) return false
        val targets = tail.mapNotNull { it.targetMixSlot }
        if (targets.size < 2 || targets.last() <= targets.first()) return false
        return tail.takeLast(PROGRESS_WINDOW_CYCLES).all { it.m02Next == HOLE_NEXT }
    }

    private fun resolvedFrozenAtBaseline(tail: List<ObserveSample>, baseline: Long): Boolean {
        if (tail.size < PROGRESS_WINDOW_CYCLES) return false
        return tail.takeLast(PROGRESS_WINDOW_CYCLES).all { it.resolvedMixSlot == baseline }
    }

    private fun resolvedAdvanced(tail: List<ObserveSample>, baseline: Long): Boolean {
        val resolved = tail.mapNotNull { it.resolvedMixSlot }
        return resolved.isNotEmpty() && resolved.last() > baseline
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

    private fun runObserveWindow(ctx: HoleCtx): List<ObserveSample> {
        val pipeline = wiring.orchestrator(ctx.sessionId)!!.pipeline
        pipeline.setSteadyCoverageHoldBypassForHarness(false)
        val samples = mutableListOf<ObserveSample>()
        var cycleOrdinal = ctx.cycleOrdinal
        for (i in 0 until OBSERVE_CYCLES) {
            cycleOrdinal += 1
            val tickMediaTimeMs = wallMs + cycleOrdinal * MediaJitterConstants.MEDIA_SLOT_MS
            val target = wiring.sessionPlayoutTargetSlot(ctx.sessionId, tickMediaTimeMs)
            target?.let { targetMix ->
                val feedSlot = HOLE_EARLIEST + i
                if (feedSlot <= targetMix &&
                    !pipeline.hasBufferedFrame(ctx.m02.moduleId, ctx.m02.incarnationId, feedSlot)
                ) {
                    admitWithMixRef(
                        sessionId = ctx.sessionId,
                        binding = ctx.m02,
                        sourceSlot = feedSlot,
                        rxWallMs = ctx.withinDeadlineNowMs + i,
                        mixReferenceSlot = feedSlot + ALIGN_OFFSET,
                        mediaTimeMs =
                            ctx.holeMediaTimeMs +
                                (feedSlot - HOLE_NEXT) * MediaJitterConstants.MEDIA_SLOT_MS,
                    )
                }
            }
            val resolved =
                wiring.resolvePlayoutMixSlot(ctx.sessionId, tickMediaTimeMs)
                    ?: error("expected resolved mix slot at tick=$tickMediaTimeMs target=$target")
            wiring.runMixPlayoutCycle(
                sessionId = ctx.sessionId,
                nowMs = ctx.withinDeadlineNowMs + i * MediaJitterConstants.MEDIA_SLOT_MS,
                slot = resolved.slot,
                slotMediaTimeMs = resolved.slotMediaTimeMs,
                tickMediaTimeMs = tickMediaTimeMs,
            )
            samples +=
                ObserveSample(
                    targetMixSlot = target,
                    resolvedMixSlot = resolved.slot,
                    m02Next = pipeline.nextExpectedSlot(ctx.m02.moduleId, ctx.m02.incarnationId),
                )
        }
        return samples
    }

    private data class HoleCtx(
        val sessionId: String,
        val m02: MemberBindingFact,
        val holeMediaTimeMs: Long,
        val withinDeadlineNowMs: Long,
        val baselineResolved: Long,
        val cycleOrdinal: Int,
    )

    private data class ObserveSample(
        val targetMixSlot: Long?,
        val resolvedMixSlot: Long?,
        val m02Next: Long?,
    )

    private fun establishFieldHoleSnapshot(seedRecoveringHold: Boolean = false): HoleCtx {
        val sessionId = "a1-final-closure-20508-${scenarioCounter.get()}"
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
        var pullWall = wallMs + MediaJitterConstants.MEDIA_SLOT_MS

        fun mediaTimeForSlot(slot: Long): Long =
            wallMs + (slot - FIRST_SOURCE_SLOT + 1) * MediaJitterConstants.MEDIA_SLOT_MS

        for (slot in FIRST_SOURCE_SLOT..20505L) {
            assertEquals(
                FrameAdmitDisposition.QUEUED,
                admitWithMixRef(sessionId, m02, slot, pullWall, slot + ALIGN_OFFSET, mediaTimeForSlot(slot)),
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

        assertEquals(
            FrameAdmitDisposition.QUEUED,
            admitWithMixRef(
                sessionId,
                m02,
                20506L,
                pullWall,
                20506L + ALIGN_OFFSET,
                mediaTimeForSlot(20506L),
            ),
        )
        pipeline.pullSlotForSharedMixPlayout(
            sourceIdentity = m02.moduleId,
            incarnationId = m02.incarnationId,
            sharedMixPlayoutSlot = 20506L + ALIGN_OFFSET,
            resolvedMixSlotMediaTimeMs = mediaTimeForSlot(20506L),
            nowMs = pullWall + MediaJitterConstants.MAX_PLAYOUT_DELAY_MS + 5L,
            effectivePullSourceSlot = 20506L,
        )
        assertEquals(HOLE_NEXT, pipeline.nextExpectedSlot(m02.moduleId, m02.incarnationId))
        assertFalse(pipeline.isActivationFirstConsumptionPending(m02.moduleId, m02.incarnationId))

        pullWall += MediaJitterConstants.MEDIA_SLOT_MS
        assertEquals(
            FrameAdmitDisposition.QUEUED,
            admitWithMixRef(
                sessionId,
                m02,
                HOLE_EARLIEST,
                pullWall,
                HOLE_EARLIEST + ALIGN_OFFSET,
                mediaTimeForSlot(HOLE_EARLIEST),
            ),
        )

        if (seedRecoveringHold) {
            pipeline.steadyCoverageMarginPolicyForHarness().setConsumptionPhaseForHarness(
                sourceIdentity = m02.moduleId,
                incarnationId = m02.incarnationId,
                phase = SteadyCoverageMarginPolicy.ConsumptionPhase.RECOVERING,
            )
        }

        val holeMediaTimeMs = mediaTimeForSlot(HOLE_NEXT)
        val withinDeadlineNowMs = holeMediaTimeMs + WITHIN_DEADLINE_SKEW_MS
        val cycleOrdinal = 50
        val baselineResolved =
            wiring.resolvePlayoutMixSlot(
                sessionId,
                wallMs + (cycleOrdinal + 1) * MediaJitterConstants.MEDIA_SLOT_MS,
            )!!.slot

        return HoleCtx(
            sessionId = sessionId,
            m02 = m02,
            holeMediaTimeMs = holeMediaTimeMs,
            withinDeadlineNowMs = withinDeadlineNowMs,
            baselineResolved = baselineResolved,
            cycleOrdinal = cycleOrdinal,
        )
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
}
