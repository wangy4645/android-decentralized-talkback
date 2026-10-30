package com.talkback.core.conference.session.integration

import com.talkback.core.conference.runtime.AdmittedMediaFrame
import com.talkback.core.conference.runtime.ConferenceMediaExecutionOrchestrator
import com.talkback.core.conference.runtime.FrameAdmitDisposition
import com.talkback.core.conference.runtime.MediaJitterConstants
import com.talkback.core.conference.runtime.PerIncarnationJitterBuffer
import com.talkback.core.conference.runtime.VoiceLevelObservation
import com.talkback.core.conference.session.ConferenceSessionMediaBridge
import com.talkback.core.conference.session.ConferenceSessionMediaWiring
import com.talkback.core.conference.session.MemberBindingFact
import com.talkback.core.conference.session.SessionMediaWiringHarness
import com.talkback.core.conference.transport.JitterBufferCursorLifecycleContract
import com.talkback.core.conference.transport.LateJoinSourcePlayoutActivationFence
import com.talkback.core.conference.transport.Phase1MediaHarness
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Jitter-buffer cursor lifecycle contract desk (J1 + J2).
 *
 * Production wiring HOLD — tests exercise current production behavior against J1/J2.
 * Primary RED target: F14-isomorphic late join with `next=null`, empty buffer, Top-K
 * selected, real [ConferenceSessionMediaWiring.runMixPlayoutCycle].
 */
class Profile01JitterBufferCursorLifecycleDeskTest {

    companion object {
        private const val SESSION_ANCHOR_SLOT = 0x5000 // 20480
        private const val F14_ESTABLISH_CYCLES = 450
        private const val F14_POST_JOIN_PLC_CYCLES = 15
    }

    private lateinit var wiring: ConferenceSessionMediaWiring
    private var wallMs: Long = 0L
    private val scenarioCounter = AtomicInteger()

    @Before
    fun setUp() {
        MeetingProductMediaShadow.enabled = true
        wiring = ConferenceSessionMediaWiring.forHarness()
        ConferenceSessionMediaBridge.wiring = wiring
        wallMs = 1_790_000_000_000L + scenarioCounter.incrementAndGet()
    }

    @After
    fun tearDown() {
        ConferenceSessionMediaBridge.wiring = null
    }

    /** J1 contract — monotonic advance. */
    @Test
    fun j1_contract_monotonicNextExpectedRejectsRegression() {
        assertEquals(
            100L,
            JitterBufferCursorLifecycleContract.monotonicNextExpected(
                currentNextExpected = 100L,
                proposedNextExpected = 99L,
            ),
        )
    }

    /** J1 contract — admit older slot must not regress cursor. */
    @Test
    fun j1_contract_admitOlderSlotMustNotRegressCursor() {
        assertEquals(
            100L,
            JitterBufferCursorLifecycleContract.admitCursorAfterQueuedFrame(
                currentNextExpected = 100L,
                admittingSlot = 99L,
            ),
        )
    }

    /** J2 contract — consumer blocked on empty buffer + null cursor. */
    @Test
    fun j2_contract_consumerMayNotSeedOriginOnEmptyBuffer() {
        assertTrue(
            !JitterBufferCursorLifecycleContract.consumerMayMutateCursor(
                currentNextExpected = null,
                bufferEmpty = true,
                writerKind = JitterBufferCursorLifecycleContract.CursorWriterKind.CONSUMER_PULL,
            ),
        )
        assertTrue(
            JitterBufferCursorLifecycleContract.consumerMayMutateCursor(
                currentNextExpected = null,
                bufferEmpty = true,
                writerKind = JitterBufferCursorLifecycleContract.CursorWriterKind.INGRESS_ADMIT,
            ),
        )
    }

    /**
     * J1 desk — production [PerIncarnationJitterBuffer.admit] L108 still regresses cursor.
     * Frame caching vs cursor advancement are intentionally separate assertions.
     */
    @Test
    fun j1_desk_productionAdmitOlderSlotMustNotRegressCursor() {
        val buf = PerIncarnationJitterBuffer("M01", 1L)
        val baseMs = 1_000_000L
        buf.advanceExpectedTo(100L)
        assertEquals(100L, buf.nextExpected())

        val older =
            AdmittedMediaFrame(
                sourceIdentity = "M01",
                incarnationId = 1L,
                mediaSlot = 99L,
                mediaTimeMs = baseMs,
                arrivalMs = baseMs,
            )
        assertEquals(FrameAdmitDisposition.QUEUED, buf.admit(older, baseMs))
        assertTrue("older frame may still cache", buf.peekFrame(99L) != null)
        assertEquals(
            "J1: admit(99) must not regress cursor from 100 (got ${buf.nextExpected()})",
            100L,
            buf.nextExpected(),
        )
    }

    /** J1 desk — promotion cleanup may regress cursor via minRemaining path. */
    @Test
    fun j1_desk_productionPromotionDiscardMustNotRegressCursor() {
        val buf = PerIncarnationJitterBuffer("M01", 1L)
        val baseMs = 2_000_000L
        fun frame(slot: Long) =
            AdmittedMediaFrame(
                sourceIdentity = "M01",
                incarnationId = 1L,
                mediaSlot = slot,
                mediaTimeMs = baseMs + slot * MediaJitterConstants.MEDIA_SLOT_MS,
                arrivalMs = baseMs,
            )
        assertEquals(FrameAdmitDisposition.QUEUED, buf.admit(frame(95L), baseMs))
        assertEquals(FrameAdmitDisposition.QUEUED, buf.admit(frame(98L), baseMs))
        buf.advanceExpectedTo(100L)

        val stale95Deadline =
            baseMs + 95L * MediaJitterConstants.MEDIA_SLOT_MS +
                MediaJitterConstants.MAX_PLAYOUT_DELAY_MS
        val fresh98Deadline =
            baseMs + 98L * MediaJitterConstants.MEDIA_SLOT_MS +
                MediaJitterConstants.MAX_PLAYOUT_DELAY_MS
        buf.promotionDiscardLateForPlayout(
            currentPlayoutTimeMs = stale95Deadline + (fresh98Deadline - stale95Deadline) / 2,
        )

        assertEquals(
            "J1: promotion must not regress cursor below 100 (got ${buf.nextExpected()})",
            100L,
            buf.nextExpected(),
        )
    }

    /**
     * J2 desk — F14-isomorphic: late M03 join, no trusted ingress, Top-K selected,
     * many real production mix cycles with PLC-eligible wall clock.
     *
     * If this FAILS while F94 single-cycle sanity PASSes, production has a consumer
     * writer path that bypasses the F9.4 NOT_READY gate (see legacy direct pullSlot).
     */
    @Test
    fun j2_desk_f14Isomorphic_productionMixCycleMustNotEstablishCursorOrigin() {
        val ctx = establishF14LateJoinNoIngress()
        val pipeline = wiring.orchestrator(ctx.sessionId)!!.pipeline

        assertNull(
            "precondition: M03 cursor unset before wire",
            pipeline.nextExpectedSlot(ctx.m03.moduleId, ctx.m03.incarnationId),
        )
        assertTrue(
            "precondition: M03 buffer empty before wire",
            pipeline.bufferedSlots(ctx.m03.moduleId, ctx.m03.incarnationId).isEmpty(),
        )
        assertNull(
            "precondition: M03 alignment unset before wire",
            pipeline.mixPlayoutAlignment(ctx.m03.moduleId, ctx.m03.incarnationId),
        )
        assertTrue(
            "precondition: M03 must be Top-K selected",
            wiring.orchestrator(ctx.sessionId)!!.selection.currentTopK().members.any {
                it.sourceIdentity == ctx.m03.moduleId
            },
        )

        var tickMediaTimeMs = ctx.tickMediaTimeMs
        var pullWall = ctx.pullWallMs
        repeat(F14_POST_JOIN_PLC_CYCLES) {
            jRunProductionPlayoutCycle(
                wiring = wiring,
                sessionId = ctx.sessionId,
                tickMediaTimeMs = tickMediaTimeMs,
                nowMs = pullWall + MediaJitterConstants.MAX_PLAYOUT_DELAY_MS + 1,
            )
            tickMediaTimeMs += MediaJitterConstants.MEDIA_SLOT_MS
            pullWall += MediaJitterConstants.MEDIA_SLOT_MS
        }

        val nextAfter =
            pipeline.nextExpectedSlot(ctx.m03.moduleId, ctx.m03.incarnationId)
        val frontierAfter =
            pipeline.playoutExpirationFrontier(ctx.m03.moduleId, ctx.m03.incarnationId)
        val bufferAfter =
            pipeline.bufferedSlots(ctx.m03.moduleId, ctx.m03.incarnationId)

        assertNull(
            "J2/F14: production mix cycle must not seed M03 cursor before trusted ingress " +
                "(next=$nextAfter frontier=$frontierAfter buffer=$bufferAfter)",
            nextAfter,
        )
        assertNull(
            "J2/F14: frontier must remain unset before trusted ingress (got $frontierAfter)",
            frontierAfter,
        )
        assertTrue(
            "J2/F14: buffer must remain empty before trusted ingress (size=${bufferAfter.size})",
            bufferAfter.isEmpty(),
        )
    }

    /** Sanity — F94 contract path single NOT_READY cycle (existing claim). */
    @Test
    fun j2_desk_f94SingleCycleSanity_notReadyDoesNotAdvanceRemoteCursor() {
        val sessionId = "j-lifecycle-f94-sanity-${scenarioCounter.get()}"
        val ctx = runF94StyleSingleNotReadyCycle(sessionId)
        assertEquals(
            LateJoinSourcePlayoutActivationFence.SourcePlayoutReadiness.NOT_PLAYOUT_READY,
            ctx.readinessBeforeFirstQueued,
        )
        assertEquals(ctx.nextBeforeProductionCycle, ctx.nextAfterNotReadyCycle)
    }

    /**
     * Documents F9.4 bypass: [MediaExecutionPipeline.pullSlot] PLC advances cursor without
     * passing through shared-mix activation gate (same writer class as F94 legacy F12 desk).
     */
    @Test
    fun j2_desk_bypassDocument_directPullSlotPlcBypassesF94ActivationGate() {
        val ctx = establishF14LateJoinNoIngress()
        val pipeline = wiring.orchestrator(ctx.sessionId)!!.pipeline
        val seedSlot = SESSION_ANCHOR_SLOT + F14_ESTABLISH_CYCLES
        assertEquals(
            FrameAdmitDisposition.QUEUED,
            wiring.orchestrator(ctx.sessionId)!!.admitFrame(
                AdmittedMediaFrame(
                    sourceIdentity = ctx.m03.moduleId,
                    incarnationId = ctx.m03.incarnationId,
                    mediaSlot = seedSlot.toLong(),
                    mediaTimeMs = ctx.pullWallMs,
                    arrivalMs = ctx.pullWallMs,
                ),
                nowMs = ctx.pullWallMs,
                sharedMixReferenceSlot = seedSlot.toLong(),
            ),
        )
        val targetNext = seedSlot + 15L
        jPlcAdvanceNextExpectedViaDirectPullSlot(
            pipeline = pipeline,
            binding = ctx.m03,
            targetNextExpected = targetNext,
            pullWallMs = ctx.pullWallMs + MediaJitterConstants.MAX_PLAYOUT_DELAY_MS + 1,
        )
        val nextAfter =
            pipeline.nextExpectedSlot(ctx.m03.moduleId, ctx.m03.incarnationId)
        assertTrue(
            "direct pullSlot PLC bypasses F9.4 gate (target=$targetNext got=$nextAfter)",
            nextAfter != null && nextAfter >= targetNext,
        )
    }

    private data class F14LateJoinContext(
        val sessionId: String,
        val m03: MemberBindingFact,
        val tickMediaTimeMs: Long,
        val pullWallMs: Long,
    )

    private data class F94SingleCycleContext(
        val readinessBeforeFirstQueued: LateJoinSourcePlayoutActivationFence.SourcePlayoutReadiness,
        val nextBeforeProductionCycle: Long?,
        val nextAfterNotReadyCycle: Long?,
    )

    private fun establishF14LateJoinNoIngress(): F14LateJoinContext {
        val sessionId = "j-lifecycle-f14-${scenarioCounter.get()}"
        assertTrue(
            ConferenceSessionMediaBridge.startSession(
                SessionMediaWiringHarness.sessionFact(sessionId).copy(startedAtMs = wallMs),
            ),
        )
        val m01 = SessionMediaWiringHarness.memberBinding("M01", incarnationId = 101L)
        val m02 = SessionMediaWiringHarness.memberBinding("M02", incarnationId = 102L)
        assertTrue(wiring.installMember(sessionId, m01))
        assertTrue(wiring.installMember(sessionId, m02))
        jObserveVoice(wiring.orchestrator(sessionId)!!, m01, m02)

        var tickWall = wallMs + MediaJitterConstants.MEDIA_SLOT_MS
        for (cycle in 0 until F14_ESTABLISH_CYCLES) {
            val slot = SESSION_ANCHOR_SLOT + cycle
            wiring.admitProtectedDatagram(sessionId, jTonePacket(m02, slot), tickWall)
            wiring.admitProtectedDatagram(sessionId, jTonePacket(m01, slot), tickWall + 1)
            val tickMediaTimeMs = wallMs + (cycle + 1) * MediaJitterConstants.MEDIA_SLOT_MS
            jRunProductionPlayoutCycle(
                wiring = wiring,
                sessionId = sessionId,
                tickMediaTimeMs = tickMediaTimeMs,
                nowMs = tickWall,
            )
            tickWall += MediaJitterConstants.MEDIA_SLOT_MS
        }

        val m03 = SessionMediaWiringHarness.memberBinding("M03", incarnationId = 103L)
        assertTrue(wiring.installMember(sessionId, m03))
        jObserveVoice(wiring.orchestrator(sessionId)!!, m01, m02, m03)

        val joinWall = tickWall + 20L
        val tickMediaTimeMs =
            wallMs + (F14_ESTABLISH_CYCLES + 1) * MediaJitterConstants.MEDIA_SLOT_MS
        wiring.orchestrator(sessionId)!!.selectTopK(joinWall)

        return F14LateJoinContext(
            sessionId = sessionId,
            m03 = m03,
            tickMediaTimeMs = tickMediaTimeMs,
            pullWallMs = joinWall,
        )
    }

    private fun runF94StyleSingleNotReadyCycle(sessionId: String): F94SingleCycleContext {
        assertTrue(
            ConferenceSessionMediaBridge.startSession(
                SessionMediaWiringHarness.sessionFact(sessionId).copy(startedAtMs = wallMs),
            ),
        )
        val m02 = SessionMediaWiringHarness.memberBinding("M02", incarnationId = 201L)
        val m01 = SessionMediaWiringHarness.memberBinding("M01", incarnationId = 202L)
        assertTrue(wiring.installMember(sessionId, m02))
        assertTrue(wiring.installMember(sessionId, m01))
        jObserveVoice(wiring.orchestrator(sessionId)!!, m01, m02)

        var tickWall = wallMs + MediaJitterConstants.MEDIA_SLOT_MS
        repeat(40) { cycle ->
            val slot = SESSION_ANCHOR_SLOT + cycle
            wiring.admitProtectedDatagram(sessionId, jTonePacket(m02, slot), tickWall)
            wiring.admitProtectedDatagram(sessionId, jTonePacket(m01, slot), tickWall + 1)
            val tickMediaTimeMs = wallMs + (cycle + 1) * MediaJitterConstants.MEDIA_SLOT_MS
            jRunProductionPlayoutCycle(
                wiring = wiring,
                sessionId = sessionId,
                tickMediaTimeMs = tickMediaTimeMs,
                nowMs = tickWall,
            )
            tickWall += MediaJitterConstants.MEDIA_SLOT_MS
        }

        val m03 = SessionMediaWiringHarness.memberBinding("M03", incarnationId = 203L)
        assertTrue(wiring.installMember(sessionId, m03))
        jObserveVoice(wiring.orchestrator(sessionId)!!, m01, m02, m03)
        val joinWall = tickWall + 20L
        val tickMediaTimeMs = wallMs + 41 * MediaJitterConstants.MEDIA_SLOT_MS
        val pipeline = wiring.orchestrator(sessionId)!!.pipeline
        val readinessBeforeFirstQueued =
            LateJoinSourcePlayoutActivationFence.readiness(
                LateJoinSourcePlayoutActivationFence.snapshotFrom(
                    pipeline,
                    m03.moduleId,
                    m03.incarnationId,
                ),
            )
        val nextBefore = pipeline.nextExpectedSlot(m03.moduleId, m03.incarnationId)
        jRunProductionPlayoutCycle(
            wiring = wiring,
            sessionId = sessionId,
            tickMediaTimeMs = tickMediaTimeMs,
            nowMs = joinWall,
        )
        val nextAfter = pipeline.nextExpectedSlot(m03.moduleId, m03.incarnationId)
        return F94SingleCycleContext(
            readinessBeforeFirstQueued = readinessBeforeFirstQueued,
            nextBeforeProductionCycle = nextBefore,
            nextAfterNotReadyCycle = nextAfter,
        )
    }

    private fun jPlcAdvanceNextExpectedViaDirectPullSlot(
        pipeline: com.talkback.core.conference.runtime.MediaExecutionPipeline,
        binding: MemberBindingFact,
        targetNextExpected: Long,
        pullWallMs: Long,
    ) {
        var next =
            pipeline.nextExpectedSlot(binding.moduleId, binding.incarnationId)
                ?: return
        var pullWall = pullWallMs
        var slotMediaTimeMs = pullWall - MediaJitterConstants.MAX_PLAYOUT_DELAY_MS - 1
        while (next < targetNextExpected) {
            pipeline.pullSlot(
                sourceIdentity = binding.moduleId,
                incarnationId = binding.incarnationId,
                slot = next,
                slotMediaTimeMs = slotMediaTimeMs,
                nowMs = pullWall,
            )
            val advanced =
                pipeline.nextExpectedSlot(binding.moduleId, binding.incarnationId)
                    ?: break
            if (advanced <= next) break
            next = advanced
            pullWall += MediaJitterConstants.MEDIA_SLOT_MS
            slotMediaTimeMs += MediaJitterConstants.MEDIA_SLOT_MS
        }
    }

    private fun jObserveVoice(
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

    private fun jRunProductionPlayoutCycle(
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

    private fun jTonePacket(
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
