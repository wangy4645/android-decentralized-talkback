package com.talkback.core.conference.session.integration

import com.talkback.core.conference.runtime.AdmittedMediaFrame
import com.talkback.core.conference.runtime.ConferenceMediaExecutionOrchestrator
import com.talkback.core.conference.runtime.FrameAdmitDisposition
import com.talkback.core.conference.runtime.MediaJitterConstants
import com.talkback.core.conference.runtime.SlotPullDisposition
import com.talkback.core.conference.runtime.VoiceLevelObservation
import com.talkback.core.conference.session.ConferenceSessionMediaBridge
import com.talkback.core.conference.session.ConferenceSessionMediaWiring
import com.talkback.core.conference.session.MemberBindingFact
import com.talkback.core.conference.session.SessionMediaWiringHarness
import com.talkback.core.conference.transport.LateJoinSourcePlayoutActivationFence
import com.talkback.core.conference.transport.LateJoinSteadyPhaseLeadContract
import com.talkback.core.conference.transport.RelativeMediaTimeline
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Issue A1 — Case-C + resolver precedence desk (field-isomorphic GREEN).
 *
 * Snapshot from issue-a-steady-margin-field-20260922-164918 T+1:
 * M02 STEADY, next=20823, buffer={20824}, resolvedMix=20822.
 *
 * GREEN: Case-C authorized missing expected slot conceals exactly one (+1) within deadline,
 * then real buffered slot becomes consumable and resolved/target lag do not run away.
 */
class Profile01CaseCPlayoutLivenessPrecedenceDeskTest {

    companion object {
        /** Field freeze fingerprint (M01 observes M02). */
        private const val HOLE_NEXT = 20823L
        private const val HOLE_EARLIEST = 20824L
        private const val RESOLVED_AT_FREEZE = 20822L
        /** mixSlotForSource(20824) = 20822 in field-normalized domain. */
        private const val ALIGN_OFFSET = -2L
        private const val FIRST_SOURCE_SLOT = 20820L
        private const val OBSERVE_CYCLES = 12
        /** Bounded-progress window derived from 120ms playout budget. */
        private const val PROGRESS_WINDOW_CYCLES = 6
        /** Keep hole pull inside useful deadline → non-mutating EMPTY (field first break). */
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
        wallMs = 1_793_000_000_000L + scenarioCounter.incrementAndGet()
    }

    @After
    fun tearDown() {
        ConferenceSessionMediaBridge.wiring = null
    }

    @Test
    fun a1_caseCFieldIsomorphic_confirmedHolePullMustMutateCursorOrResolvedProgresses() {
        val ctx = establishFieldHoleSnapshot()
        val pipeline = wiring.orchestrator(ctx.sessionId)!!.pipeline
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
        assertEquals(HOLE_NEXT, snap.nextExpectedSourceSlot)
        assertEquals(HOLE_EARLIEST, snap.earliestBufferedSourceSlot)
        assertEquals(HOLE_EARLIEST, snap.latestBufferedSourceSlot)
        assertTrue(isCaseCHole(snap, RESOLVED_AT_FREEZE))

        val effectivePull =
            LateJoinSourcePlayoutActivationFence.resolveMutationProducingPullSourceSlot(
                snap,
                RESOLVED_AT_FREEZE,
            )
        assertEquals(HOLE_NEXT, effectivePull)

        val nextBefore = pipeline.nextExpectedSlot(ctx.m02.moduleId, ctx.m02.incarnationId)!!
        assertEquals(HOLE_NEXT, nextBefore)
        val pullDisposition =
            pipeline.pullSlotForSharedMixPlayout(
                sourceIdentity = ctx.m02.moduleId,
                incarnationId = ctx.m02.incarnationId,
                sharedMixPlayoutSlot = RESOLVED_AT_FREEZE,
                resolvedMixSlotMediaTimeMs = ctx.holeMediaTimeMs,
                nowMs = ctx.withinDeadlineNowMs,
                effectivePullSourceSlot = effectivePull,
            )
        assertTrue(
            "Case-C hole must conceal exactly one expected slot",
            pullDisposition == SlotPullDisposition.PLC_SYNTHESIS ||
                pullDisposition == SlotPullDisposition.SILENCE_GAP,
        )
        assertEquals(
            HOLE_EARLIEST,
            pipeline.nextExpectedSlot(ctx.m02.moduleId, ctx.m02.incarnationId),
        )

        val baselineResolved =
            wiring.resolvePlayoutMixSlot(ctx.sessionId, ctx.firstObserveMediaTimeMs)!!.slot
        assertEquals(RESOLVED_AT_FREEZE, baselineResolved)

        val samples = mutableListOf<ObserveSample>()
        var cycleOrdinal = ctx.cycleOrdinal
        for (i in 0 until OBSERVE_CYCLES) {
            cycleOrdinal += 1
            val tickMediaTimeMs = wallMs + cycleOrdinal * MediaJitterConstants.MEDIA_SLOT_MS
            val target = wiring.sessionPlayoutTargetSlot(ctx.sessionId, tickMediaTimeMs)
            target?.let { targetMix ->
                val ingressSourceSlot = targetMix - ALIGN_OFFSET
                if (!pipeline.hasBufferedFrame(ctx.m02.moduleId, ctx.m02.incarnationId, ingressSourceSlot)) {
                    assertEquals(
                        FrameAdmitDisposition.QUEUED,
                        admitWithMixRef(
                            sessionId = ctx.sessionId,
                            binding = ctx.m02,
                            sourceSlot = ingressSourceSlot,
                            rxWallMs = ctx.withinDeadlineNowMs + i,
                            mixReferenceSlot = targetMix,
                            mediaTimeMs =
                                ctx.holeMediaTimeMs +
                                    (ingressSourceSlot - HOLE_NEXT) * MediaJitterConstants.MEDIA_SLOT_MS,
                        ),
                    )
                }
            }
            val resolved =
                wiring.resolvePlayoutMixSlot(ctx.sessionId, tickMediaTimeMs)
                    ?: error("expected resolved mix slot at tick=$tickMediaTimeMs target=$target")
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

        val tail = samples
        assertFalse(
            "Case-C GREEN: cursor must not remain at $HOLE_NEXT while target advances " +
                "(tail=${summarizeTail(tail)})",
            cursorFrozenAtHole(tail),
        )
        assertFalse(
            "Case-C GREEN: resolved must make bounded forward progress " +
                "(tail=${summarizeTail(tail)})",
            lacksBoundedResolvedProgress(tail),
        )
        assertFalse(
            "Case-C GREEN: target-resolved lag must not runaway (tail=${summarizeTail(tail)})",
            targetResolvedLagRunaway(tail),
        )
        assertFalse(
            "Case-C GREEN: must not PLC-runaway across multiple holes (tail=${summarizeTail(tail)})",
            caseCPlcRunawayBeyondOneSlot(tail),
        )
    }

    /** J2/F14 symmetric — ordinary absent pull must not advance cursor. */
    @Test
    fun a1_ordinaryAbsentPullMustNotAdvanceCursor() {
        val ctx = establishFieldHoleSnapshot()
        val pipeline = wiring.orchestrator(ctx.sessionId)!!.pipeline
        val nextBefore = pipeline.nextExpectedSlot(ctx.m02.moduleId, ctx.m02.incarnationId)!!
        assertEquals(HOLE_NEXT, nextBefore)

        val disposition =
            pipeline.pullSlot(
                sourceIdentity = ctx.m02.moduleId,
                incarnationId = ctx.m02.incarnationId,
                slot = HOLE_NEXT,
                slotMediaTimeMs = ctx.holeMediaTimeMs,
                nowMs = ctx.withinDeadlineNowMs,
            )
        assertEquals(SlotPullDisposition.EMPTY, disposition)
        assertEquals(
            nextBefore,
            pipeline.nextExpectedSlot(ctx.m02.moduleId, ctx.m02.incarnationId),
        )
    }

    /** Case-C authorized missing expected — exactly +1, never jump to mappedS/earliest. */
    @Test
    fun a1_caseCAuthorizedMissingExpectedAdvancesExactlyOneSlot() {
        val ctx = establishFieldHoleSnapshot()
        val pipeline = wiring.orchestrator(ctx.sessionId)!!.pipeline
        val snap =
            LateJoinSourcePlayoutActivationFence.snapshotFrom(
                pipeline,
                ctx.m02.moduleId,
                ctx.m02.incarnationId,
            )
        val effectivePull =
            LateJoinSourcePlayoutActivationFence.resolveMutationProducingPullSourceSlot(
                snap,
                RESOLVED_AT_FREEZE,
            )!!
        assertTrue(
            LateJoinSourcePlayoutActivationFence.isCaseCAuthorizedMissingExpectedAdvance(
                snap,
                RESOLVED_AT_FREEZE,
                effectivePull,
            ),
        )

        pipeline.pullSlotForSharedMixPlayout(
            sourceIdentity = ctx.m02.moduleId,
            incarnationId = ctx.m02.incarnationId,
            sharedMixPlayoutSlot = RESOLVED_AT_FREEZE,
            resolvedMixSlotMediaTimeMs = ctx.holeMediaTimeMs,
            nowMs = ctx.withinDeadlineNowMs,
            effectivePullSourceSlot = effectivePull,
        )
        assertEquals(
            HOLE_EARLIEST,
            pipeline.nextExpectedSlot(ctx.m02.moduleId, ctx.m02.incarnationId),
        )
        assertEquals(
            HOLE_EARLIEST,
            pipeline.bufferedSlots(ctx.m02.moduleId, ctx.m02.incarnationId).single(),
        )
    }

    private fun caseCPlcRunawayBeyondOneSlot(tail: List<ObserveSample>): Boolean {
        if (tail.any { it.m02Next == HOLE_NEXT }) return true
        for (pair in tail.zipWithNext()) {
            val prev = pair.first.m02Next ?: continue
            val curr = pair.second.m02Next ?: continue
            if (curr - prev > 1L) return true
        }
        return false
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

    private fun lacksBoundedResolvedProgress(tail: List<ObserveSample>): Boolean {
        if (tail.size < PROGRESS_WINDOW_CYCLES + 1) return true
        for (windowStart in 0..tail.size - PROGRESS_WINDOW_CYCLES - 1) {
            val window = tail.subList(windowStart, windowStart + PROGRESS_WINDOW_CYCLES + 1)
            val resolved = window.mapNotNull { it.resolvedMixSlot }
            if (resolved.size >= 2 && resolved.last() > resolved.first()) return false
        }
        return true
    }

    private fun targetResolvedLagRunaway(tail: List<ObserveSample>): Boolean {
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
            lastFive.last() > lastFive.first() + PROGRESS_WINDOW_CYCLES
    }

    private fun summarizeTail(tail: List<ObserveSample>): String {
        if (tail.isEmpty()) return "empty"
        val first = tail.first()
        val last = tail.last()
        return "resolved ${first.resolvedMixSlot}->${last.resolvedMixSlot} " +
            "next ${first.m02Next}->${last.m02Next} " +
            "target ${first.targetMixSlot}->${last.targetMixSlot}"
    }

    private data class HoleCtx(
        val sessionId: String,
        val m02: MemberBindingFact,
        val holeMediaTimeMs: Long,
        val withinDeadlineNowMs: Long,
        val firstObserveMediaTimeMs: Long,
        val cycleOrdinal: Int,
    )

    private fun establishFieldHoleSnapshot(): HoleCtx {
        val sessionId = "a1-casec-hole-${scenarioCounter.get()}"
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
        var tickWall = wallMs + MediaJitterConstants.MEDIA_SLOT_MS
        var cycleOrdinal: Int

        fun mediaTimeForSlot(slot: Long): Long =
            wallMs + (slot - FIRST_SOURCE_SLOT + 1) * MediaJitterConstants.MEDIA_SLOT_MS

        var pullWall = tickWall + MediaJitterConstants.MAX_PLAYOUT_DELAY_MS + 5L
        for (slot in FIRST_SOURCE_SLOT..20821L) {
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
        assertEquals(
            FrameAdmitDisposition.QUEUED,
            admitWithMixRef(
                sessionId,
                m02,
                20822L,
                pullWall,
                20822L + ALIGN_OFFSET,
                mediaTimeForSlot(20822L),
            ),
        )
        pipeline.pullSlotForSharedMixPlayout(
            sourceIdentity = m02.moduleId,
            incarnationId = m02.incarnationId,
            sharedMixPlayoutSlot = 20822L + ALIGN_OFFSET,
            resolvedMixSlotMediaTimeMs = mediaTimeForSlot(20822L),
            nowMs = pullWall + MediaJitterConstants.MAX_PLAYOUT_DELAY_MS + 5L,
            effectivePullSourceSlot = 20822L,
        )
        assertEquals(
            HOLE_NEXT,
            pipeline.nextExpectedSlot(m02.moduleId, m02.incarnationId),
        )
        assertFalse(
            pipeline.isActivationFirstConsumptionPending(m02.moduleId, m02.incarnationId),
        )

        tickWall = pullWall + MediaJitterConstants.MEDIA_SLOT_MS
        assertEquals(
            FrameAdmitDisposition.QUEUED,
            admitWithMixRef(
                sessionId,
                m02,
                HOLE_EARLIEST,
                tickWall,
                HOLE_EARLIEST + ALIGN_OFFSET,
                mediaTimeForSlot(HOLE_EARLIEST),
            ),
        )
        cycleOrdinal = 3

        val snap =
            LateJoinSourcePlayoutActivationFence.snapshotFrom(
                pipeline,
                m02.moduleId,
                m02.incarnationId,
            )
        assertEquals(HOLE_NEXT, snap.nextExpectedSourceSlot)
        assertEquals(HOLE_EARLIEST, snap.earliestBufferedSourceSlot)
        assertEquals(HOLE_EARLIEST, snap.latestBufferedSourceSlot)
        assertEquals(
            1L,
            LateJoinSteadyPhaseLeadContract.trustedIngressSpan(
                snap.nextExpectedSourceSlot!!,
                snap.latestBufferedSourceSlot!!,
            ),
        )
        assertFalse(
            pipeline.isActivationFirstConsumptionPending(m02.moduleId, m02.incarnationId),
        )

        val holeMediaTimeMs = mediaTimeForSlot(HOLE_NEXT)
        val withinDeadlineNowMs = holeMediaTimeMs + WITHIN_DEADLINE_SKEW_MS
        val firstObserveMediaTimeMs = wallMs + (cycleOrdinal + 1) * MediaJitterConstants.MEDIA_SLOT_MS

        return HoleCtx(
            sessionId = sessionId,
            m02 = m02,
            holeMediaTimeMs = holeMediaTimeMs,
            withinDeadlineNowMs = withinDeadlineNowMs,
            firstObserveMediaTimeMs = firstObserveMediaTimeMs,
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

    private data class ObserveSample(
        val targetMixSlot: Long?,
        val resolvedMixSlot: Long?,
        val m02Next: Long?,
    )
}
