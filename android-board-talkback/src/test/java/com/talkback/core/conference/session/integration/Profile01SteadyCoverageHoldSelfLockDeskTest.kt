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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Phase 2A — SteadyCoverage RECOVERING HOLD self-lock desk A/B.
 *
 * Field fingerprint (issue-a1-casec-liveness-field-20260922-172840):
 * STEADY, next==earliest, buffer non-empty, trustedIngressSpan=4, RECOVERING, Case-C=false.
 *
 * A: production HOLD enabled → resolved/cursor freeze while shared M advances.
 * B: harness HOLD bypass only → resolved/cursor forward under identical snapshot.
 */
class Profile01SteadyCoverageHoldSelfLockDeskTest {

    companion object {
        private const val NEXT = 22810L
        private const val LATEST = 22814L
        private const val FIRST_SOURCE_SLOT = 22800L
        private const val ALIGN_OFFSET = -2L
        private const val TRUSTED_SPAN = 4L
        private const val OBSERVE_CYCLES = 8
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
        wallMs = 1_794_000_000_000L + scenarioCounter.incrementAndGet()
    }

    @After
    fun tearDown() {
        ConferenceSessionMediaBridge.wiring = null
    }

    @Test
    fun phase2a_holdEnabled_recoveringHoldFreezesResolvedWhileTargetAdvances() {
        val ctx = establishRecoveringHoldSnapshot(holdBypass = false)
        assertSnapshotPreconditions(ctx)
        val tail = runObserveWindow(ctx, holdBypass = false)
        assertTrue(
            "A: RECOVERING HOLD must freeze cursor (tail=${summarizeTail(tail)})",
            cursorFrozenAtNext(tail, ctx.nextExpected),
        )
        assertTrue(
            "A: RECOVERING HOLD must freeze resolved (tail=${summarizeTail(tail)})",
            resolvedFrozenAtBaseline(tail, ctx.baselineResolved),
        )
        assertTrue(
            "A: target must advance during observe (tail=${summarizeTail(tail)})",
            targetAdvanced(tail),
        )
    }

    @Test
    fun phase2a_holdBypass_recoveringPhaseAllowsForwardProgress() {
        val ctx = establishRecoveringHoldSnapshot(holdBypass = true)
        assertSnapshotPreconditions(ctx)
        val tail = runObserveWindow(ctx, holdBypass = true)
        assertFalse(
            "B: bypass HOLD must not freeze cursor (tail=${summarizeTail(tail)})",
            cursorFrozenAtNext(tail, ctx.nextExpected),
        )
        assertTrue(
            "B: bypass HOLD must advance cursor beyond next (tail=${summarizeTail(tail)})",
            (tail.last().m02Next ?: ctx.nextExpected) > ctx.nextExpected,
        )
        assertTrue(
            "B: bypass HOLD must advance resolved mix slot (tail=${summarizeTail(tail)})",
            resolvedMixSlotAdvanced(tail),
        )
    }

    private fun assertSnapshotPreconditions(ctx: HoldCtx) {
        val pipeline = wiring.orchestrator(ctx.sessionId)!!.pipeline
        val policy = pipeline.steadyCoverageMarginPolicyForHarness()
        val snap =
            LateJoinSourcePlayoutActivationFence.snapshotFrom(
                pipeline,
                ctx.m02.moduleId,
                ctx.m02.incarnationId,
            )
        assertEquals(
            LateJoinSourcePlayoutActivationFence.ConsumerPlayoutPhase.STEADY,
            LateJoinSourcePlayoutActivationFence.consumerPlayoutPhase(snap),
        )
        assertEquals(NEXT, snap.nextExpectedSourceSlot)
        assertEquals(NEXT, snap.earliestBufferedSourceSlot)
        assertEquals(LATEST, snap.latestBufferedSourceSlot)
        assertEquals(
            TRUSTED_SPAN,
            LateJoinSteadyPhaseLeadContract.trustedIngressSpan(
                snap.nextExpectedSourceSlot!!,
                snap.latestBufferedSourceSlot!!,
            ),
        )
        assertFalse(isCaseCHole(snap, ctx.baselineResolved))
        assertEquals(
            SteadyCoverageMarginPolicy.ConsumptionPhase.RECOVERING,
            policy.consumptionPhaseForHarness(ctx.m02.moduleId, ctx.m02.incarnationId),
        )
        assertEquals(ctx.holdBypass, pipeline.steadyCoverageMarginPolicyForHarness().deskHarnessBypassHold)
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

    private fun runObserveWindow(ctx: HoldCtx, holdBypass: Boolean): List<ObserveSample> {
        val pipeline = wiring.orchestrator(ctx.sessionId)!!.pipeline
        pipeline.setSteadyCoverageHoldBypassForHarness(holdBypass)
        val samples = mutableListOf<ObserveSample>()
        var cycleOrdinal = ctx.cycleOrdinal
        for (i in 0 until OBSERVE_CYCLES) {
            cycleOrdinal += 1
            val tickMediaTimeMs = wallMs + cycleOrdinal * MediaJitterConstants.MEDIA_SLOT_MS
            val target = wiring.sessionPlayoutTargetSlot(ctx.sessionId, tickMediaTimeMs)
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
            samples +=
                ObserveSample(
                    targetMixSlot = target,
                    resolvedMixSlot = resolved.slot,
                    m02Next = pipeline.nextExpectedSlot(ctx.m02.moduleId, ctx.m02.incarnationId),
                )
        }
        return samples
    }

    private data class HoldCtx(
        val sessionId: String,
        val m02: MemberBindingFact,
        val nextExpected: Long,
        val nextMediaTimeMs: Long,
        val withinDeadlineNowMs: Long,
        val baselineResolved: Long,
        val cycleOrdinal: Int,
        val holdBypass: Boolean,
    )

    private fun establishRecoveringHoldSnapshot(holdBypass: Boolean): HoldCtx {
        val sessionId = "phase2a-hold-${scenarioCounter.get()}-$holdBypass"
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

        val policy = pipeline.steadyCoverageMarginPolicyForHarness()
        policy.setConsumptionPhaseForHarness(
            sourceIdentity = m02.moduleId,
            incarnationId = m02.incarnationId,
            phase = SteadyCoverageMarginPolicy.ConsumptionPhase.RECOVERING,
        )
        pipeline.setSteadyCoverageHoldBypassForHarness(holdBypass)

        val snap =
            LateJoinSourcePlayoutActivationFence.snapshotFrom(
                pipeline,
                m02.moduleId,
                m02.incarnationId,
            )
        assertEquals(NEXT, snap.nextExpectedSourceSlot)
        assertEquals(NEXT, snap.earliestBufferedSourceSlot)
        assertEquals(LATEST, snap.latestBufferedSourceSlot)
        assertFalse(
            pipeline.isActivationFirstConsumptionPending(m02.moduleId, m02.incarnationId),
        )

        val nextMediaTimeMs = mediaTimeForSlot(NEXT)
        val observeStartOrdinal = 10
        val baselineResolved =
            wiring.resolvePlayoutMixSlot(
                sessionId,
                wallMs + observeStartOrdinal * MediaJitterConstants.MEDIA_SLOT_MS,
            )!!.slot

        return HoldCtx(
            sessionId = sessionId,
            m02 = m02,
            nextExpected = NEXT,
            nextMediaTimeMs = nextMediaTimeMs,
            withinDeadlineNowMs = nextMediaTimeMs + WITHIN_DEADLINE_SKEW_MS,
            baselineResolved = baselineResolved,
            cycleOrdinal = observeStartOrdinal - 1,
            holdBypass = holdBypass,
        )
    }

    private fun cursorFrozenAtNext(tail: List<ObserveSample>, next: Long): Boolean {
        if (tail.size < PROGRESS_WINDOW_CYCLES) return false
        if (!targetAdvanced(tail)) return false
        return tail.takeLast(PROGRESS_WINDOW_CYCLES).all { it.m02Next == next }
    }

    private fun resolvedFrozenAtBaseline(tail: List<ObserveSample>, baseline: Long): Boolean {
        if (tail.size < PROGRESS_WINDOW_CYCLES) return false
        return tail.takeLast(PROGRESS_WINDOW_CYCLES).all { it.resolvedMixSlot == baseline }
    }

    private fun targetAdvanced(tail: List<ObserveSample>): Boolean {
        val targets = tail.mapNotNull { it.targetMixSlot }
        return targets.size >= 2 && targets.last() > targets.first()
    }

    private fun resolvedMixSlotAdvanced(tail: List<ObserveSample>): Boolean {
        if (tail.size < 2) return false
        val first = tail.first().resolvedMixSlot ?: return false
        val last = tail.last().resolvedMixSlot ?: return false
        return last > first
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

    private data class ObserveSample(
        val targetMixSlot: Long?,
        val resolvedMixSlot: Long?,
        val m02Next: Long?,
    )
}
