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
import com.talkback.core.conference.transport.LateJoinSteadyPhaseLeadContract
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
 * ADR-0058 A6 — late-join ACTIVATION → STEADY phase-depth contract (desk).
 *
 * Frozen Profile03 lead: 120 ms = 6 × 20 ms slots. Orthogonal to F9.2 offset.
 */
class Profile01A6PhaseDepthContractDeskTest {

    companion object {
        private const val SESSION_ANCHOR = 0x5000
        private const val ESTABLISH_CYCLES = 40
        private const val F15_OFFSET = 442L
        private const val FIRST_SOURCE = 20497L
        private val PHASE_LEAD = LateJoinSteadyPhaseLeadContract.STEADY_PHASE_LEAD_SLOTS
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

    /** A5-isomorphic thin span — must not authorize activation pull. */
    @Test
    fun a6_thinIngressSpan_blocksActivationMutation() {
        val next = FIRST_SOURCE
        val latest = next + 2L
        val snap = lateJoinSnapshot(next = next, earliest = next, latest = latest)
        assertFalse(LateJoinSourcePlayoutActivationFence.isLateJoinSteadyPhaseLeadReady(snap))
        assertFalse(
            LateJoinSourcePlayoutActivationFence.isMutationProducingPullAllowed(
                snap,
                latest + F15_OFFSET,
            ),
        )
        assertEquals(
            LateJoinSourcePlayoutActivationFence.ConsumerPlayoutPhase.ACTIVATION,
            LateJoinSourcePlayoutActivationFence.consumerPlayoutPhase(snap),
        )
    }

    /** Frozen 120 ms lead — span >= 6 establishes activation consumer behind live edge. */
    @Test
    fun a6_phaseLeadSpan_authorizesActivationBehindIngressEdge() {
        val next = FIRST_SOURCE
        val latest = next + PHASE_LEAD
        val sharedM = latest + F15_OFFSET
        val snap = lateJoinSnapshot(next = next, earliest = next, latest = latest)
        assertTrue(LateJoinSourcePlayoutActivationFence.isLateJoinSteadyPhaseLeadReady(snap))
        assertEquals(
            next,
            LateJoinSourcePlayoutActivationFence.resolveMutationProducingPullSourceSlot(
                snap,
                sharedM,
            ),
        )
    }

    /** Hole in coverage — temporal span sufficient; activation may PLC at cursor. */
    @Test
    fun a6_phaseLeadWithHole_allowsActivationPlcAtCursor() {
        val missing = 20579L
        val earliest = missing + 1L
        val latest = earliest + PHASE_LEAD
        val sharedM = missing + F15_OFFSET
        val snap =
            lateJoinSnapshot(
                next = missing,
                earliest = earliest,
                latest = latest,
            )
        assertTrue(LateJoinSourcePlayoutActivationFence.isLateJoinSteadyPhaseLeadReady(snap))
        assertEquals(
            missing,
            LateJoinSourcePlayoutActivationFence.resolveMutationProducingPullSourceSlot(
                snap,
                sharedM,
            ),
        )
    }

    /** Session-coincident offset==0 — phase lead gate must not apply. */
    @Test
    fun a6_sessionCoincidentOffsetZero_exemptFromPhaseLeadGate() {
        val anchor = SESSION_ANCHOR.toLong()
        val snap =
            LateJoinSourcePlayoutActivationFence.SourceActivationSnapshot(
                alignment =
                    SourceMixPlayoutAlignment(
                        mixReferenceSlot = anchor,
                        sourceReferenceSlot = anchor,
                    ),
                nextExpectedSourceSlot = anchor,
                earliestBufferedSourceSlot = anchor,
                latestBufferedSourceSlot = anchor,
                playoutExpirationFrontierExclusive = null,
                activationFirstConsumptionPending = true,
            )
        assertTrue(LateJoinSourcePlayoutActivationFence.isLateJoinSteadyPhaseLeadReady(snap))
        assertTrue(
            LateJoinSourcePlayoutActivationFence.isMutationProducingPullAllowed(snap, anchor),
        )
    }

    /** Production — late join waits for 6-slot span before first activation pull. */
    @Test
    fun a6_production_lateJoinWaitsForPhaseLeadBeforeSteady() {
        val ctx = establishLateJoin(frameCount = PHASE_LEAD.toInt() + 1)
        val snapBefore = a6Snapshot(ctx)
        assertFalse(
            "thin span before lead accumulation must block activation pull",
            LateJoinSourcePlayoutActivationFence.isMutationProducingPullAllowed(
                snapBefore.copy(
                    latestBufferedSourceSlot =
                        ctx.firstSourceSlot + 2L,
                    earliestBufferedSourceSlot = ctx.firstSourceSlot,
                    nextExpectedSourceSlot = ctx.firstSourceSlot,
                ),
                ctx.sharedMixAtJoin,
            ),
        )

        val snapReady = a6Snapshot(ctx)
        assertTrue(LateJoinSourcePlayoutActivationFence.isLateJoinSteadyPhaseLeadReady(snapReady))
        assertTrue(
            LateJoinSourcePlayoutActivationFence.isMutationProducingPullAllowed(
                snapReady,
                ctx.sharedMixAtJoin,
            ),
        )

        val pipeline = wiring.orchestrator(ctx.sessionId)!!.pipeline
        val nextBefore =
            pipeline.nextExpectedSlot(ctx.m03.moduleId, ctx.m03.incarnationId)!!
        a6RunProductionCycle(ctx.sessionId, ctx.tickMediaTimeMs, ctx.pullWallMs)
        assertFalse(
            pipeline.isActivationFirstConsumptionPending(ctx.m03.moduleId, ctx.m03.incarnationId),
        )
        val nextAfter =
            pipeline.nextExpectedSlot(ctx.m03.moduleId, ctx.m03.incarnationId)!!
        assertTrue("activation pull must advance consumer", nextAfter > nextBefore)
        assertTrue(
            "steady entry must preserve phase lead vs ingress latest",
            ctx.firstSourceSlot + PHASE_LEAD - nextAfter >= 0,
        )
    }

    private data class LateJoinCtx(
        val sessionId: String,
        val m03: MemberBindingFact,
        val firstSourceSlot: Long,
        val tickMediaTimeMs: Long,
        val pullWallMs: Long,
        val sharedMixAtJoin: Long,
    )

    private fun lateJoinSnapshot(
        next: Long,
        earliest: Long,
        latest: Long,
    ): LateJoinSourcePlayoutActivationFence.SourceActivationSnapshot =
        LateJoinSourcePlayoutActivationFence.SourceActivationSnapshot(
            alignment =
                SourceMixPlayoutAlignment(
                    mixReferenceSlot = latest + F15_OFFSET,
                    sourceReferenceSlot = next,
                ),
            nextExpectedSourceSlot = next,
            earliestBufferedSourceSlot = earliest,
            latestBufferedSourceSlot = latest,
            playoutExpirationFrontierExclusive = null,
            activationFirstConsumptionPending = true,
        )

    private fun establishLateJoin(frameCount: Int): LateJoinCtx {
        val sessionId = "a6-${scenarioCounter.get()}"
        assertTrue(
            ConferenceSessionMediaBridge.startSession(
                SessionMediaWiringHarness.sessionFact(sessionId).copy(startedAtMs = wallMs),
            ),
        )
        val m02 = SessionMediaWiringHarness.memberBinding("M02", incarnationId = 2L)
        val m01 = SessionMediaWiringHarness.memberBinding("M01", incarnationId = 3L)
        assertTrue(wiring.installMember(sessionId, m02))
        assertTrue(wiring.installMember(sessionId, m01))
        a6ObserveVoice(wiring.orchestrator(sessionId)!!, m01, m02)

        var tickWall = wallMs + MediaJitterConstants.MEDIA_SLOT_MS
        for (cycle in 0 until ESTABLISH_CYCLES) {
            val slot = SESSION_ANCHOR + cycle
            wiring.admitProtectedDatagram(sessionId, a6TonePacket(m02, slot), tickWall)
            wiring.admitProtectedDatagram(sessionId, a6TonePacket(m01, slot), tickWall + 1)
            val tickMediaTimeMs = wallMs + (cycle + 1) * MediaJitterConstants.MEDIA_SLOT_MS
            a6RunProductionCycle(sessionId, tickMediaTimeMs, tickWall)
            tickWall += MediaJitterConstants.MEDIA_SLOT_MS
        }

        val m03 = SessionMediaWiringHarness.memberBinding("M03", incarnationId = 2L)
        assertTrue(wiring.installMember(sessionId, m03))
        a6ObserveVoice(wiring.orchestrator(sessionId)!!, m01, m02, m03)
        wiring.orchestrator(sessionId)!!.selectTopK(tickWall)

        val joinWall = tickWall + 20L
        val liveSlot = SESSION_ANCHOR + ESTABLISH_CYCLES
        wiring.admitProtectedDatagram(sessionId, a6TonePacket(m02, liveSlot), joinWall)
        wiring.admitProtectedDatagram(sessionId, a6TonePacket(m01, liveSlot), joinWall + 1)
        val tickMediaTimeMs =
            wallMs + (ESTABLISH_CYCLES + 1) * MediaJitterConstants.MEDIA_SLOT_MS
        val sharedMixAtJoin =
            wiring.resolvePlayoutMixSlot(sessionId, tickMediaTimeMs)?.slot
                ?: (liveSlot.toLong() + 1)

        for (i in 0 until frameCount) {
            val sourceSlot = FIRST_SOURCE + i
            assertEquals(
                FrameAdmitDisposition.QUEUED,
                a6AdmitWithMixRef(
                    sessionId = sessionId,
                    binding = m03,
                    mediaSlot = sourceSlot,
                    rxWallMs = joinWall + i,
                    mixReferenceSlot = sourceSlot + F15_OFFSET,
                ),
            )
        }

        return LateJoinCtx(
            sessionId = sessionId,
            m03 = m03,
            firstSourceSlot = FIRST_SOURCE,
            tickMediaTimeMs = tickMediaTimeMs,
            pullWallMs = joinWall,
            sharedMixAtJoin = sharedMixAtJoin,
        )
    }

    private fun a6Snapshot(ctx: LateJoinCtx): LateJoinSourcePlayoutActivationFence.SourceActivationSnapshot =
        LateJoinSourcePlayoutActivationFence.snapshotFrom(
            wiring.orchestrator(ctx.sessionId)!!.pipeline,
            ctx.m03.moduleId,
            ctx.m03.incarnationId,
        )

    private fun a6AdmitWithMixRef(
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

    private fun a6ObserveVoice(
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

    private fun a6RunProductionCycle(
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

    private fun a6TonePacket(
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
