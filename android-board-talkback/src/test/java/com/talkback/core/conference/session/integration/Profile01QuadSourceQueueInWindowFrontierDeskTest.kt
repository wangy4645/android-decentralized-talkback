package com.talkback.core.conference.session.integration

import com.talkback.core.conference.runtime.AdmittedMediaFrame
import com.talkback.core.conference.runtime.ConferenceMediaExecutionOrchestrator
import com.talkback.core.conference.runtime.FrameAdmitDisposition
import com.talkback.core.conference.runtime.MediaExecutionPipeline
import com.talkback.core.conference.runtime.MediaJitterConstants
import com.talkback.core.conference.runtime.VoiceLevelObservation
import com.talkback.core.conference.session.AdrALiveWatermarkFreeze
import com.talkback.core.conference.session.ConferenceSessionMediaBridge
import com.talkback.core.conference.session.ConferenceSessionMediaWiring
import com.talkback.core.conference.session.MemberBindingFact
import com.talkback.core.conference.session.SessionMediaLiveEdgeAligner
import com.talkback.core.conference.session.SessionMediaWiringHarness
import com.talkback.core.conference.transport.Phase1MediaHarness
import com.talkback.core.conference.transport.SessionPlayoutIngressDeadline
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * B25/B26 — quad-source isomorphic desk for [SessionMediaLiveEdgeAligner.Kind.QUEUE_IN_WINDOW]
 * under shared mix playout (field session cea47d73…).
 *
 * Asserts **P1 frontier recoverability within the QIW recovery window** ([IN_WINDOW_SLOTS]):
 * after concurrent reorder/align bursts and bounded shared playout, the hole slot must still
 * be admissible (not [FrameAdmitDisposition.LATE_FOR_PLAYOUT]).
 *
 * A-1 timeout→RESYNC after the window is covered by [SessionMediaLiveEdgeAlignerTest]; this
 * desk stays inside the recovery window so P1 coupling remains GREEN.
 */
class Profile01QuadSourceQueueInWindowFrontierDeskTest {

    companion object {
        private const val SESSION_ANCHOR = 0x5000
        private const val PREFILL_CYCLES = 24
        private const val STRESS_ROUNDS = 2
        private const val PLAYOUT_CYCLES_PER_BURST = 4
    }

    private lateinit var wiring: ConferenceSessionMediaWiring
    private var wallMs: Long = 0L
    private val scenarioCounter = AtomicInteger()

    @Before
    fun setUp() {
        AdrALiveWatermarkFreeze.enabled = false
        AdrALiveWatermarkFreeze.resetForTest()
        AdrALiveWatermarkFreeze.enabled = false
        SessionMediaLiveEdgeAligner.a1QiwTimeoutResyncEnabled = false
        SessionMediaLiveEdgeAligner.cQiwLagDebtConvergenceEnabled = false
        SessionMediaLiveEdgeAligner.q3QiwDeferredPinEnabled = false
        MeetingProductMediaShadow.enabled = true
        Profile01B21PreJitterObservation.resetForTest()
        Profile01B21PreJitterObservation.testLogSink = {}
        wiring = ConferenceSessionMediaWiring.forHarness()
        ConferenceSessionMediaBridge.wiring = wiring
        wallMs = 1_792_000_000_000L + scenarioCounter.incrementAndGet()
    }

    @After
    fun tearDown() {
        ConferenceSessionMediaBridge.wiring = null
        Profile01B21PreJitterObservation.resetForTest()
        SessionMediaLiveEdgeAligner.a1QiwTimeoutResyncEnabled = false
        SessionMediaLiveEdgeAligner.cQiwLagDebtConvergenceEnabled = false
        SessionMediaLiveEdgeAligner.q3QiwDeferredPinEnabled = false
        AdrALiveWatermarkFreeze.resetForTest()
    }

    @Test
    fun quadSource_concurrentQueueInWindow_playoutFrontierMustStayCoupledToIngressWindow() {
        val ctx = establishQuadSourceSession()
        val pipeline = wiring.orchestrator(ctx.sessionId)!!.pipeline
        val ingressSlot =
            ctx.members.associate { it.moduleId to SESSION_ANCHOR }.toMutableMap()

        var tickWall = ctx.startWallMs
        var cycleOrdinal = 0

        for (cycle in 0 until PREFILL_CYCLES) {
            tickWall += MediaJitterConstants.MEDIA_SLOT_MS
            cycleOrdinal += 1
            val tickMediaTimeMs = wallMs + cycleOrdinal * MediaJitterConstants.MEDIA_SLOT_MS
            ctx.members.forEachIndexed { index, member ->
                val slot = ingressSlot.getValue(member.moduleId)
                admitTone(ctx.sessionId, member, slot, tickWall + index)
                ingressSlot[member.moduleId] = slot + 1
            }
            runProductionCycle(ctx.sessionId, tickMediaTimeMs, tickWall)
        }

        repeat(STRESS_ROUNDS) { round ->
            tickWall += MediaJitterConstants.MEDIA_SLOT_MS
            cycleOrdinal += 1
            val burstMediaTimeMs = wallMs + cycleOrdinal * MediaJitterConstants.MEDIA_SLOT_MS
            val bursts =
                ctx.members.map { member ->
                    val burst =
                        triggerQueueInWindowBurst(
                            sessionId = ctx.sessionId,
                            pipeline = pipeline,
                            member = member,
                            rxWallMs = tickWall,
                            slotMediaTimeMs = burstMediaTimeMs,
                        )
                    assertEquals(
                        SessionMediaLiveEdgeAligner.Kind.QUEUE_IN_WINDOW,
                        burst.alignKind,
                    )
                    ingressSlot[member.moduleId] =
                        (
                            pipeline.jitterSlotDomainSnapshot(member.moduleId, member.incarnationId)
                                ?.latestBufferedSlot
                                ?: burst.liveEdgeAfterBurst
                        ).toInt() + 1
                    burst
                }

            bursts.forEach { burst ->
                // Probe while still inside A-1 recovery window (before long shared playout).
                assertFrontierRecoverableForSource(
                    sessionId = ctx.sessionId,
                    pipeline = pipeline,
                    member = burst.member,
                    holeSlot = burst.holeSlot,
                    probeRxWallMs = tickWall + 1,
                    roundLabel = "round=$round post-burst source=${burst.member.moduleId}",
                )
            }

            repeat(PLAYOUT_CYCLES_PER_BURST) {
                tickWall += MediaJitterConstants.MEDIA_SLOT_MS
                cycleOrdinal += 1
                val cycleMediaTimeMs = wallMs + cycleOrdinal * MediaJitterConstants.MEDIA_SLOT_MS
                ctx.members.forEachIndexed { index, other ->
                    val slot = ingressSlot.getValue(other.moduleId)
                    admitTone(ctx.sessionId, other, slot, tickWall + index)
                    ingressSlot[other.moduleId] = slot + 1
                }
                runProductionCycle(
                    ctx.sessionId,
                    cycleMediaTimeMs,
                    tickWall + MediaJitterConstants.MAX_PLAYOUT_DELAY_MS + 5,
                )
            }
        }
    }

    private fun assertFrontierRecoverableForSource(
        sessionId: String,
        pipeline: MediaExecutionPipeline,
        member: MemberBindingFact,
        holeSlot: Long,
        probeRxWallMs: Long,
        roundLabel: String,
    ) {
        val snap = pipeline.jitterSlotDomainSnapshot(member.moduleId, member.incarnationId)
        val expected = snap?.nextExpectedSlot
        val frontier = pipeline.playoutExpirationFrontier(member.moduleId, member.incarnationId)
        assertTrue("$roundLabel: frontier present (snap=$snap)", frontier != null)
        val frontierExclusive = frontier!!

        assertFalse(
            "$roundLabel: hole $holeSlot late vs frontier $frontierExclusive " +
                "(nextExpected=$expected latestBuffered=${snap?.latestBufferedSlot} jitter=${snap?.bySlotSize})",
            SessionPlayoutIngressDeadline.isLateForPlayout(holeSlot, frontierExclusive),
        )

        val probe =
            wiring.admitProtectedDatagram(
                sessionId = sessionId,
                datagram = tonePacket(member, holeSlot.toInt()),
                rxWallMs = probeRxWallMs,
            )
        assertEquals(
            "$roundLabel: hole $holeSlot must stay QUEUED after shared playout " +
                "(frontier=$frontierExclusive nextExpected=$expected)",
            FrameAdmitDisposition.QUEUED,
            probe.frameAdmit,
        )
    }

    private data class QueueInWindowBurst(
        val member: MemberBindingFact,
        val holeSlot: Long,
        val liveEdgeAfterBurst: Long,
        val alignKind: SessionMediaLiveEdgeAligner.Kind,
    )

    private fun triggerQueueInWindowBurst(
        sessionId: String,
        pipeline: MediaExecutionPipeline,
        member: MemberBindingFact,
        rxWallMs: Long,
        slotMediaTimeMs: Long,
    ): QueueInWindowBurst {
        val base =
            pipeline.nextExpectedSlot(member.moduleId, member.incarnationId)
                ?: error("no nextExpected before burst for ${member.moduleId}")
        assertEquals(
            FrameAdmitDisposition.QUEUED,
            admitTone(sessionId, member, base.toInt(), rxWallMs),
        )
        pipeline.pullSlot(
            member.moduleId,
            member.incarnationId,
            base,
            slotMediaTimeMs,
            slotMediaTimeMs + MediaJitterConstants.MEDIA_SLOT_MS,
        )
        val holeSlot =
            pipeline.nextExpectedSlot(member.moduleId, member.incarnationId)
                ?: error("no nextExpected after anchor admit for ${member.moduleId}")
        val anchorSlot = base + 6
        for (offset in 2..5) {
            assertEquals(
                FrameAdmitDisposition.QUEUED,
                admitTone(sessionId, member, (base + offset).toInt(), rxWallMs + offset),
            )
        }
        val anchorRx = rxWallMs + 10
        val anchorFrame =
            AdmittedMediaFrame(
                sourceIdentity = member.moduleId,
                incarnationId = member.incarnationId,
                mediaSlot = anchorSlot,
                mediaTimeMs = anchorRx,
                arrivalMs = anchorRx,
            )
        val anchorAdmit = pipeline.admitFrame(anchorFrame, anchorRx)
        val alignKind =
            when (anchorAdmit) {
                FrameAdmitDisposition.REORDER_DISPLACEMENT_EXCEEDED -> {
                    val aligned =
                        SessionMediaLiveEdgeAligner.maybeAlignLiveEdgeOnReorder(
                            pipeline = pipeline,
                            sourceIdentity = member.moduleId,
                            incarnationId = member.incarnationId,
                            liveSlot = anchorSlot,
                            mediaTimeMs = anchorRx,
                            arrivalMs = anchorRx,
                            nowMs = anchorRx,
                        )
                    assertEquals(SessionMediaLiveEdgeAligner.Kind.QUEUE_IN_WINDOW, aligned!!.kind)
                    assertEquals(FrameAdmitDisposition.QUEUED, aligned.disposition)
                    aligned.recoverableHoleSlot?.let { hole ->
                        pipeline.markQueuedInWindowRecoverableHole(
                            member.moduleId,
                            member.incarnationId,
                            hole,
                        )
                    }
                    aligned.kind
                }
                FrameAdmitDisposition.QUEUED -> {
                    pipeline.markQueuedInWindowRecoverableHole(
                        member.moduleId,
                        member.incarnationId,
                        holeSlot,
                    )
                    SessionMediaLiveEdgeAligner.Kind.QUEUE_IN_WINDOW
                }
                else -> error("unexpected anchor admit $anchorAdmit")
            }
        val expectedAfter = pipeline.nextExpectedSlot(member.moduleId, member.incarnationId)
        assertEquals(holeSlot, expectedAfter)
        return QueueInWindowBurst(
            member = member,
            holeSlot = holeSlot,
            liveEdgeAfterBurst = anchorSlot,
            alignKind = alignKind,
        )
    }

    private fun establishQuadSourceSession(): QuadCtx {
        val sessionId = "quad-qiw-frontier-${scenarioCounter.get()}"
        assertTrue(
            ConferenceSessionMediaBridge.startSession(
                SessionMediaWiringHarness.sessionFact(sessionId).copy(startedAtMs = wallMs),
            ),
        )
        val members =
            listOf(
                SessionMediaWiringHarness.memberBinding("M01", incarnationId = 1L),
                SessionMediaWiringHarness.memberBinding("M02", incarnationId = 2L),
                SessionMediaWiringHarness.memberBinding("M03", incarnationId = 3L),
                SessionMediaWiringHarness.memberBinding("M04", incarnationId = 4L),
            )
        members.forEach { assertTrue(wiring.installMember(sessionId, it)) }
        val orchestrator = wiring.orchestrator(sessionId)!!
        observeVoice(orchestrator, members)
        orchestrator.selectTopK(wallMs)
        assertEquals(
            setOf("M01", "M02", "M03", "M04"),
            orchestrator.selection.currentTopK().members.map { it.sourceIdentity }.toSet(),
        )
        return QuadCtx(
            sessionId = sessionId,
            members = members,
            startWallMs = wallMs + MediaJitterConstants.MEDIA_SLOT_MS,
        )
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
        members: List<MemberBindingFact>,
    ) {
        members.forEachIndexed { index, binding ->
            orchestrator.observeVoice(
                VoiceLevelObservation(
                    sourceIdentity = binding.moduleId,
                    incarnationId = binding.incarnationId,
                    voiceActive = true,
                    audioLevel = 96 - index,
                ),
            )
        }
    }

    private data class QuadCtx(
        val sessionId: String,
        val members: List<MemberBindingFact>,
        val startWallMs: Long,
    )
}