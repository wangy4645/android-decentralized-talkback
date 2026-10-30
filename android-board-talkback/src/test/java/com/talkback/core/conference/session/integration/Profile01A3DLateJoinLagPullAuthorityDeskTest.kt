package com.talkback.core.conference.session.integration

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
import com.talkback.core.conference.transport.SourceMixPlayoutAlignment
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A3-D — M02←M04 late-join lag pull authority (field a3m1-mix-output 804583e6…).
 *
 * First break: STEADY with mapped(M) < next, buffer present → NO_PULL_AUTHORITY (null).
 * Fix: consume earliest trusted ingress; decode at pulled source slot S.
 */
class Profile01A3DLateJoinLagPullAuthorityDeskTest {
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
        wallMs = 1_793_500_000_000L
    }

    @After
    fun tearDown() {
        ConferenceSessionMediaCoordinatorDelegate.localModuleIdProvider = priorLocalModuleIdProvider
        ConferenceSessionMediaBridge.wiring = null
    }

    @Test
    fun a3d_fenceContract_mappedBehindCursorWithBuffer_pullsEarliestNotNull() {
        val alignment =
            SourceMixPlayoutAlignment(
                mixReferenceSlot = FIELD_M04_FIRST_SOURCE + M04_OFFSET,
                sourceReferenceSlot = FIELD_M04_FIRST_SOURCE,
            )
        assertEquals(M04_OFFSET, alignment.offset)
        val snap =
            LateJoinSourcePlayoutActivationFence.SourceActivationSnapshot(
                alignment = alignment,
                nextExpectedSourceSlot = FIELD_M04_NEXT,
                earliestBufferedSourceSlot = FIELD_M04_NEXT,
                latestBufferedSourceSlot = FIELD_M04_LATEST,
                playoutExpirationFrontierExclusive = null,
                activationFirstConsumptionPending = false,
            )
        assertEquals(
            LateJoinSourcePlayoutActivationFence.ConsumerPlayoutPhase.STEADY,
            LateJoinSourcePlayoutActivationFence.consumerPlayoutPhase(snap),
        )
        val mappedS = alignment.sourceSlotForMix(FIELD_RESOLVED_M)
        assertTrue("field: mapped S must trail next", mappedS < FIELD_M04_NEXT)

        val effectivePull =
            LateJoinSourcePlayoutActivationFence.resolveMutationProducingPullSourceSlot(
                snap,
                FIELD_RESOLVED_M,
            )
        assertEquals(FIELD_M04_NEXT, effectivePull)
    }

    @Test
    fun a3d_fieldIsomorphic_m04BufferedLag_producesRealMixContribution() {
        val ctx = establishFieldSteadySnapshot()
        val pipeline = wiring.orchestrator(ctx.sessionId)!!.pipeline
        val snap =
            LateJoinSourcePlayoutActivationFence.snapshotFrom(
                pipeline,
                ctx.m04.moduleId,
                ctx.m04.incarnationId,
            )
        assertEquals(
            LateJoinSourcePlayoutActivationFence.ConsumerPlayoutPhase.ACTIVATION,
            LateJoinSourcePlayoutActivationFence.consumerPlayoutPhase(snap),
        )
        assertEquals(ctx.expectedNext, snap.nextExpectedSourceSlot)
        assertNotNull(snap.earliestBufferedSourceSlot)
        assertTrue(
            "buffer must cover lag window",
            snap.latestBufferedSourceSlot!! >= snap.earliestBufferedSourceSlot!! + FIELD_BUFFER_SPAN - 2,
        )

        val resolved =
            wiring.resolvePlayoutMixSlot(ctx.sessionId, ctx.tickMediaTimeMs)
                ?: error("expected resolved mix slot")
        assertTrue(
            "resolved M must map behind M04 consumer cursor",
            alignmentFor(pipeline, ctx.m04).sourceSlotForMix(resolved.slot) < ctx.expectedNext,
        )

        val effectivePull =
            LateJoinSourcePlayoutActivationFence.resolveMutationProducingPullSourceSlot(
                snap,
                resolved.slot,
            )
        assertEquals(ctx.expectedNext, effectivePull)

        val result =
            wiring.runMixPlayoutCycle(
                sessionId = ctx.sessionId,
                nowMs = ctx.withinDeadlineNowMs,
                slot = resolved.slot,
                slotMediaTimeMs = resolved.slotMediaTimeMs,
                tickMediaTimeMs = ctx.tickMediaTimeMs,
            ) ?: error("expected mix cycle result")

        assertEquals(
            SlotPullDisposition.DECODE_FRAME,
            result.mixCycle.sourcePullDispositions[ctx.m04.moduleId],
        )
        assertTrue(
            "M04 must decode into mix after pull authority restore",
            ctx.m04.moduleId in result.mixCycle.mixParticipantIdentities,
        )
        assertTrue(result.mixCycle.mixedBlock.samples.isNotEmpty())
        assertEquals(
            Profile01A3MixOutputTelemetry.SourceContributionKind.REAL,
            Profile01A3MixOutputTelemetry.classifySourceContribution(
                ctx.m04.moduleId,
                result.mixCycle.sourcePullDispositions[ctx.m04.moduleId],
                result.mixCycle.mixParticipantIdentities,
            ),
        )
        assertEquals(
            ctx.expectedNext + 1,
            pipeline.nextExpectedSlot(ctx.m04.moduleId, ctx.m04.incarnationId),
        )
    }

    private fun establishFieldSteadySnapshot(): FieldCtx {
        val sessionId = "a3d-late-join-lag-pull"
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
            runResolvedCycle(sessionId, wallMs, wallMs)
        }
        orchestrator.selectTopK(wallMs)

        val m04 = SessionMediaWiringHarness.memberBinding("M04", incarnationId = 4L)
        assertTrue(wiring.installMember(sessionId, m04))
        observeVoice(orchestrator, m04)
        orchestrator.selectTopK(wallMs)

        val mixRefAtJoin = slot.toLong()
        val m04FirstSlot = (mixRefAtJoin - M04_OFFSET).toInt()
        val bufferEnd = m04FirstSlot + FIELD_BUFFER_SPAN
        for (s in m04FirstSlot..bufferEnd) {
            admitTone(sessionId, m04, s, wallMs + (s - m04FirstSlot))
        }
        orchestrator.selectTopK(wallMs)

        val tickMediaTimeMs = wallMs + MediaJitterConstants.MEDIA_SLOT_MS
        val withinDeadlineNowMs = tickMediaTimeMs + WITHIN_DEADLINE_SKEW_MS
        return FieldCtx(
            sessionId = sessionId,
            m04 = m04,
            expectedNext = m04FirstSlot.toLong(),
            tickMediaTimeMs = tickMediaTimeMs,
            withinDeadlineNowMs = withinDeadlineNowMs,
        )
    }

    private fun alignmentFor(
        pipeline: com.talkback.core.conference.runtime.MediaExecutionPipeline,
        binding: MemberBindingFact,
    ): SourceMixPlayoutAlignment =
        pipeline.mixPlayoutAlignment(binding.moduleId, binding.incarnationId)
            ?: error("alignment missing for ${binding.moduleId}")

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
        orchestrator: com.talkback.core.conference.runtime.ConferenceMediaExecutionOrchestrator,
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

    private data class FieldCtx(
        val sessionId: String,
        val m04: MemberBindingFact,
        val expectedNext: Long,
        val tickMediaTimeMs: Long,
        val withinDeadlineNowMs: Long,
    )

    companion object {
        private const val LOCAL = "M02"
        private const val SESSION_ANCHOR = 0x5000
        private const val M04_OFFSET = 572L
        private const val ESTABLISH_CYCLES = 420
        private const val FIELD_BUFFER_SPAN = 37

        /** Field steady snapshot (M02 @ a3m1-mix-output-field-20260923-150840). */
        private const val FIELD_RESOLVED_M = 20697L
        private const val FIELD_M04_NEXT = 20481L
        private const val FIELD_M04_LATEST = 20518L
        private const val FIELD_M04_FIRST_SOURCE = FIELD_M04_NEXT
        private const val WITHIN_DEADLINE_SKEW_MS = 50L
    }
}
