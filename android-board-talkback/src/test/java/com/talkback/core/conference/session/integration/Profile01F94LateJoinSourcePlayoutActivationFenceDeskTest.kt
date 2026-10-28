package com.talkback.core.conference.session.integration

import com.talkback.core.conference.runtime.FrameAdmitDisposition
import com.talkback.core.conference.runtime.MediaJitterConstants
import com.talkback.core.conference.session.ConferenceSessionMediaBridge
import com.talkback.core.conference.session.ConferenceSessionMediaWiring
import com.talkback.core.conference.session.MemberBindingFact
import com.talkback.core.conference.session.SessionMediaWiringHarness
import com.talkback.core.conference.transport.LateJoinSourcePlayoutActivationFence
import com.talkback.core.conference.transport.Phase1MediaHarness
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

private const val F94_SESSION_ID = "f94-late-join-playout-activation-fence-desk"
private const val F94_SESSION_ANCHOR_SLOT = 0x5000 // 20480

/** Field F12 cycle 975 — issue-a-f93-field session 716f5009… */
private const val F94_ESTABLISH_CYCLES = 975
private const val F94_SHARED_M_FIELD = 21457L
private const val F94_CONSUMER_NEXT_FIELD = 20483L
private const val F94_FIRST_WIRE_S_FIELD = 20491L
private const val F94_REORDER_GAP_FIELD = 8L

private val f94ContractSessionCounter = AtomicInteger()

private data class F94F12Context(
    val m03: MemberBindingFact,
    val nextExpectedAfterConsumerPull: Long,
    val firstWireOutcome: FrameAdmitDisposition,
    val firstWireGap: Long,
    val readinessAfterPull: LateJoinSourcePlayoutActivationFence.SourcePlayoutReadiness,
)

/**
 * ADR-0058 F9.4 — Late-Join Source Playout Activation Fence (contract + desk).
 *
 * Field-isomorphic to F12 / issue-a-f93-field cycle 975 (M02 observes M03).
 *
 * Contract integration tests exercise production pull gate via [ConferenceSessionMediaWiring.runMixPlayoutCycle].
 */
class Profile01F94LateJoinSourcePlayoutActivationFenceDeskTest {

    companion object {
        @JvmStatic
        private lateinit var legacyWiring: ConferenceSessionMediaWiring

        @JvmStatic
        private lateinit var f12: F94F12Context

        @BeforeClass
        @JvmStatic
        fun establishF12Scenario() {
            MeetingProductMediaShadow.enabled = true
            legacyWiring = ConferenceSessionMediaWiring.forHarness()
            ConferenceSessionMediaBridge.wiring = legacyWiring
            f12 = buildF94F12LegacyScenario(legacyWiring)
        }

        @AfterClass
        @JvmStatic
        fun tearDownClass() {
            ConferenceSessionMediaBridge.wiring = null
        }
    }

    @After
    fun tearDown() {
        if (ConferenceSessionMediaBridge.wiring != legacyWiring) {
            ConferenceSessionMediaBridge.wiring = null
        }
    }

    /** Readiness derivation — session-coincident source with coverage authorizes activation pull. */
    @Test
    fun f94_boundary_sessionCoincidentSourceIsPlayoutReadyAfterFirstQueued() {
        val anchor = F94_SESSION_ANCHOR_SLOT.toLong()
        val snap =
            LateJoinSourcePlayoutActivationFence.SourceActivationSnapshot(
                alignment =
                    com.talkback.core.conference.transport.SourceMixPlayoutAlignment(
                        mixReferenceSlot = anchor,
                        sourceReferenceSlot = anchor,
                    ),
                nextExpectedSourceSlot = anchor,
                earliestBufferedSourceSlot = anchor,
                latestBufferedSourceSlot = anchor,
                playoutExpirationFrontierExclusive = null,
                activationFirstConsumptionPending = true,
            )
        assertTrue(
            LateJoinSourcePlayoutActivationFence.isMutationProducingPullAllowed(snap, anchor),
        )
    }

    /** Legacy repro — F12 activation race: consumer before wire, gap=8, REORDER. GREEN pre-F9.4. */
    @Test
    fun legacy_f12_consumerBeforeWireOpensEightSlotReorderGap() {
        assertTrue(
            "legacy consumer must advance nextExpected ahead of wire (got ${f12.nextExpectedAfterConsumerPull})",
            f12.nextExpectedAfterConsumerPull >= F94_CONSUMER_NEXT_FIELD - 3L &&
                f12.nextExpectedAfterConsumerPull <= F94_CONSUMER_NEXT_FIELD + 3L,
        )
        assertEquals(
            FrameAdmitDisposition.REORDER_DISPLACEMENT_EXCEEDED,
            f12.firstWireOutcome,
        )
        assertTrue(
            "first wire gap must exceed reorder cap (got ${f12.firstWireGap})",
            f12.firstWireGap > MediaJitterConstants.MAX_REORDER_PACKETS,
        )
        assertTrue(
            "field-isomorphic gap≈8 (got ${f12.firstWireGap})",
            f12.firstWireGap >= F94_REORDER_GAP_FIELD - 1L,
        )
        assertEquals(
            LateJoinSourcePlayoutActivationFence.ConsumerPlayoutPhase.ACTIVATION,
            LateJoinSourcePlayoutActivationFence.consumerPlayoutPhase(
                f94ActivationSnapshot(legacyWiring, F94_SESSION_ID, f12.m03.moduleId, f12.m03.incarnationId),
            ),
        )
    }

    /** Contract — before PLAYOUT_READY, production cycle must not advance M03 nextExpected. */
    @Test
    fun f94_contract_beforeReadySharedCycleDoesNotAdvanceRemoteNextExpected() {
        val ctx = runF94ContractActivationPath(freshContractWiring())
        assertEquals(
            LateJoinSourcePlayoutActivationFence.SourcePlayoutReadiness.NOT_PLAYOUT_READY,
            ctx.readinessBeforeFirstQueued,
        )
        assertEquals(
            "F9.4: consumer must not advance nextExpected before PLAYOUT_READY " +
                "(before=${ctx.nextBeforeProductionCycle} after=${ctx.nextAfterNotReadyCycle})",
            ctx.nextBeforeProductionCycle,
            ctx.nextAfterNotReadyCycle,
        )
    }

    /** Contract — single QUEUED alone is insufficient; 6-slot phase lead required. */
    @Test
    fun f94_contract_firstQueuedIngressDerivesPlayoutReady() {
        val ctx = runF94ContractActivationPath(freshContractWiring())
        assertFalse(
            "A6: one queued frame span=0 must not authorize activation pull",
            LateJoinSourcePlayoutActivationFence.isMutationProducingPullAllowed(
                ctx.readinessSnapshotAfterFirstQueued,
                ctx.sharedMixAtFirstQueued,
            ),
        )
        assertTrue(
            "A6: phase-lead span must authorize activation pull " +
                "(sharedM=${ctx.sharedMixAtPhaseLead} snap=${ctx.readinessSnapshotAtPhaseLead})",
            LateJoinSourcePlayoutActivationFence.isMutationProducingPullAllowed(
                ctx.readinessSnapshotAtPhaseLead,
                ctx.sharedMixAtPhaseLead,
            ),
        )
        assertTrue(
            "phase lead uses temporal span not buffer.size (got size=${ctx.bufferSizeAtPhaseLead})",
            ctx.bufferSizeAtPhaseLead <= 7,
        )
    }

    /** Contract — after ready, in-order wire within reorder semantics. */
    @Test
    fun f94_contract_afterReadySubsequentIngressQueuesWithinReorder() {
        val ctx = runF94ContractActivationPath(freshContractWiring())
        assertEquals(FrameAdmitDisposition.QUEUED, ctx.firstWireAfterReadyOutcome)
    }

    /** Production wiring — NOT ready skips consumer advancement; ready resumes F9.2 pull. */
    @Test
    fun f94_production_notReadyCycleSkipsConsumerAdvancementUntilTrustedQueued() {
        val ctx = runF94ContractActivationPath(freshContractWiring())
        assertEquals(
            LateJoinSourcePlayoutActivationFence.SourcePlayoutReadiness.NOT_PLAYOUT_READY,
            ctx.readinessBeforeFirstQueued,
        )
        assertEquals(ctx.nextBeforeProductionCycle, ctx.nextAfterNotReadyCycle)
        assertEquals(ctx.frontierBeforeProductionCycle, ctx.frontierAfterNotReadyCycle)
        assertTrue(
            LateJoinSourcePlayoutActivationFence.isMutationProducingPullAllowed(
                ctx.readinessSnapshotAtPhaseLead,
                ctx.sharedMixAtPhaseLead,
            ),
        )
        assertTrue(
            "production pull must advance nextExpected after PLAYOUT_READY " +
                "(queued=${ctx.readinessSnapshotAfterFirstQueued.nextExpectedSourceSlot} " +
                "after=${ctx.nextAfterReadyCycle})",
            ctx.nextAfterReadyCycle != null &&
                ctx.readinessSnapshotAtPhaseLead.nextExpectedSourceSlot != null &&
                ctx.nextAfterReadyCycle!! >
                    ctx.readinessSnapshotAtPhaseLead.nextExpectedSourceSlot!!,
        )
    }

    /** Established source — activation fence must not block steady-state pull. */
    @Test
    fun f94_boundary_establishedSourceRemainsPlayoutReadyAndPulls() {
        MeetingProductMediaShadow.enabled = true
        val wiring = ConferenceSessionMediaWiring.forHarness()
        ConferenceSessionMediaBridge.wiring = wiring
        val sessionId = "$F94_SESSION_ID-established"
        val wallMs = 1_800_000_000_000L
        assertTrue(
            ConferenceSessionMediaBridge.startSession(
                SessionMediaWiringHarness.sessionFact(sessionId).copy(startedAtMs = wallMs),
            ),
        )
        val m01 = SessionMediaWiringHarness.memberBinding("M01", incarnationId = 11L)
        assertTrue(wiring.installMember(sessionId, m01))
        var tickWall = wallMs + MediaJitterConstants.MEDIA_SLOT_MS
        for (cycle in 0 until 40) {
            val slot = F94_SESSION_ANCHOR_SLOT + cycle
            wiring.admitProtectedDatagram(sessionId, f94TonePacket(m01, slot), tickWall)
            val tickMediaTimeMs = wallMs + (cycle + 1) * MediaJitterConstants.MEDIA_SLOT_MS
            f94RunLegacyPlayoutCycle(wiring, sessionId, tickMediaTimeMs, tickWall)
            tickWall += MediaJitterConstants.MEDIA_SLOT_MS
        }
        val liveSlot = F94_SESSION_ANCHOR_SLOT + 40
        wiring.admitProtectedDatagram(sessionId, f94TonePacket(m01, liveSlot), tickWall)
        val pipeline = wiring.orchestrator(sessionId)!!.pipeline
        val before = pipeline.nextExpectedSlot(m01.moduleId, m01.incarnationId)!!
        val snap = f94ActivationSnapshot(wiring, sessionId, m01.moduleId, m01.incarnationId)
        assertEquals(
            LateJoinSourcePlayoutActivationFence.SourcePlayoutReadiness.PLAYOUT_READY,
            LateJoinSourcePlayoutActivationFence.readiness(snap),
        )
        val tickMs = wallMs + 41 * MediaJitterConstants.MEDIA_SLOT_MS
        f94RunProductionPlayoutCycle(
            wiring = wiring,
            sessionId = sessionId,
            tickMediaTimeMs = tickMs,
            nowMs = tickWall + MediaJitterConstants.MEDIA_SLOT_MS,
        )
        val after = pipeline.nextExpectedSlot(m01.moduleId, m01.incarnationId)!!
        assertTrue(
            "established source must still advance under fence (before=$before after=$after)",
            after > before,
        )
    }
}

private fun freshContractWiring(): ConferenceSessionMediaWiring {
    MeetingProductMediaShadow.enabled = true
    val wiring = ConferenceSessionMediaWiring.forHarness()
    ConferenceSessionMediaBridge.wiring = wiring
    return wiring
}

private data class F94ContractActivationContext(
    val sessionId: String,
    val m03: MemberBindingFact,
    val readinessBeforeFirstQueued: LateJoinSourcePlayoutActivationFence.SourcePlayoutReadiness,
    val readinessSnapshotAfterFirstQueued: LateJoinSourcePlayoutActivationFence.SourceActivationSnapshot,
    val sharedMixAtFirstQueued: Long,
    val readinessSnapshotAtPhaseLead: LateJoinSourcePlayoutActivationFence.SourceActivationSnapshot,
    val sharedMixAtPhaseLead: Long,
    val nextBeforeProductionCycle: Long?,
    val nextAfterNotReadyCycle: Long?,
    val frontierBeforeProductionCycle: Long?,
    val frontierAfterNotReadyCycle: Long?,
    val readinessAfterFirstQueued: LateJoinSourcePlayoutActivationFence.SourcePlayoutReadiness,
    val nextAfterReadyCycle: Long?,
    val bufferSizeAfterFirstQueued: Int,
    val bufferSizeAtPhaseLead: Int,
    val firstWireAfterReadyOutcome: FrameAdmitDisposition,
)

private fun buildF94F12LegacyScenario(
    wiring: ConferenceSessionMediaWiring,
): F94F12Context {
    val wallMs = 1_700_000_000_000L
    assertTrue(
        ConferenceSessionMediaBridge.startSession(
            SessionMediaWiringHarness.sessionFact(F94_SESSION_ID).copy(startedAtMs = wallMs),
        ),
    )
    val m02 = SessionMediaWiringHarness.memberBinding("M02", incarnationId = 2L)
    val m01 = SessionMediaWiringHarness.memberBinding("M01", incarnationId = 3L)
    assertTrue(wiring.installMember(F94_SESSION_ID, m02))
    assertTrue(wiring.installMember(F94_SESSION_ID, m01))

    var tickWall = wallMs + MediaJitterConstants.MEDIA_SLOT_MS
    for (cycle in 0 until F94_ESTABLISH_CYCLES) {
        val slot = F94_SESSION_ANCHOR_SLOT + cycle
        wiring.admitProtectedDatagram(F94_SESSION_ID, f94TonePacket(m02, slot), tickWall)
        wiring.admitProtectedDatagram(F94_SESSION_ID, f94TonePacket(m01, slot), tickWall + 1)
        val tickMediaTimeMs = wallMs + (cycle + 1) * MediaJitterConstants.MEDIA_SLOT_MS
        f94RunLegacyPlayoutCycle(wiring, F94_SESSION_ID, tickMediaTimeMs, tickWall)
        tickWall += MediaJitterConstants.MEDIA_SLOT_MS
    }

    val m03 = SessionMediaWiringHarness.memberBinding("M03", incarnationId = 2L)
    assertTrue(wiring.installMember(F94_SESSION_ID, m03))

    // Field cycle 975: alignment exists but bulk continuity not yet — consumer pull runs first.
    val joinWall = tickWall + 20L
    wiring.admitProtectedDatagram(F94_SESSION_ID, f94TonePacket(m03, 20480), joinWall)
    wiring.admitProtectedDatagram(F94_SESSION_ID, f94TonePacket(m03, 20481), joinWall + 1)

    var tickMediaTimeMs =
        wallMs + (F94_ESTABLISH_CYCLES + 1) * MediaJitterConstants.MEDIA_SLOT_MS
    var pullWall = joinWall + MediaJitterConstants.MEDIA_SLOT_MS
    repeat(3) {
        f94RunLegacyPlayoutCycle(
            wiring,
            F94_SESSION_ID,
            tickMediaTimeMs,
            pullWall + MediaJitterConstants.MAX_PLAYOUT_DELAY_MS + 1,
        )
        tickMediaTimeMs += MediaJitterConstants.MEDIA_SLOT_MS
        pullWall += MediaJitterConstants.MEDIA_SLOT_MS
    }

    val pipeline = wiring.orchestrator(F94_SESSION_ID)!!.pipeline
    f94PlcAdvanceNextExpectedTo(
        pipeline = pipeline,
        binding = m03,
        targetNextExpected = F94_CONSUMER_NEXT_FIELD,
        pullWallMs = pullWall + MediaJitterConstants.MAX_PLAYOUT_DELAY_MS + 1,
    )
    val nextAfterPull =
        pipeline.nextExpectedSlot(m03.moduleId, m03.incarnationId)
            ?: error("M03 nextExpected missing after legacy consumer pull")
    val readinessAfterPull =
        LateJoinSourcePlayoutActivationFence.readiness(
            f94ActivationSnapshot(wiring, F94_SESSION_ID, m03.moduleId, m03.incarnationId),
        )

    val firstWireSlot = nextAfterPull + F94_REORDER_GAP_FIELD
    val firstWireOutcome =
        f94AdmitJitterFrame(
            wiring = wiring,
            sessionId = F94_SESSION_ID,
            binding = m03,
            mediaSlot = firstWireSlot.toInt(),
            rxWallMs = joinWall + MediaJitterConstants.MEDIA_SLOT_MS * 3,
        )
    val gap = firstWireSlot - nextAfterPull

    return F94F12Context(
        m03 = m03,
        nextExpectedAfterConsumerPull = nextAfterPull,
        firstWireOutcome = firstWireOutcome,
        firstWireGap = gap,
        readinessAfterPull = readinessAfterPull,
    )
}

private fun runF94ContractActivationPath(
    wiring: ConferenceSessionMediaWiring,
): F94ContractActivationContext {
    val sessionId = "$F94_SESSION_ID-contract-${f94ContractSessionCounter.incrementAndGet()}"
    val wallMs = 1_750_000_000_000L
    assertTrue(
        ConferenceSessionMediaBridge.startSession(
            SessionMediaWiringHarness.sessionFact(sessionId).copy(startedAtMs = wallMs),
        ),
    )
    val m02 = SessionMediaWiringHarness.memberBinding("M02", incarnationId = 22L)
    val m01 = SessionMediaWiringHarness.memberBinding("M01", incarnationId = 33L)
    assertTrue(wiring.installMember(sessionId, m02))
    assertTrue(wiring.installMember(sessionId, m01))

    var tickWall = wallMs + MediaJitterConstants.MEDIA_SLOT_MS
    for (cycle in 0 until F94_ESTABLISH_CYCLES) {
        val slot = F94_SESSION_ANCHOR_SLOT + cycle
        wiring.admitProtectedDatagram(sessionId, f94TonePacket(m02, slot), tickWall)
        wiring.admitProtectedDatagram(sessionId, f94TonePacket(m01, slot), tickWall + 1)
        val tickMediaTimeMs = wallMs + (cycle + 1) * MediaJitterConstants.MEDIA_SLOT_MS
        f94RunLegacyPlayoutCycle(wiring, sessionId, tickMediaTimeMs, tickWall)
        tickWall += MediaJitterConstants.MEDIA_SLOT_MS
    }

    val m03 = SessionMediaWiringHarness.memberBinding("M03", incarnationId = 22L)
    assertTrue(wiring.installMember(sessionId, m03))

    val joinWall = tickWall + 20L
    val liveSlot = F94_SESSION_ANCHOR_SLOT + F94_ESTABLISH_CYCLES
    wiring.admitProtectedDatagram(sessionId, f94TonePacket(m02, liveSlot), joinWall)
    wiring.admitProtectedDatagram(sessionId, f94TonePacket(m01, liveSlot), joinWall + 1)

    val tickMediaTimeMs =
        wallMs + (F94_ESTABLISH_CYCLES + 1) * MediaJitterConstants.MEDIA_SLOT_MS
    val pipeline = wiring.orchestrator(sessionId)!!.pipeline

    val readinessBeforeFirstQueued =
        LateJoinSourcePlayoutActivationFence.readiness(
            f94ActivationSnapshot(wiring, sessionId, m03.moduleId, m03.incarnationId),
        )
    val nextBeforeProductionCycle = pipeline.nextExpectedSlot(m03.moduleId, m03.incarnationId)
    val frontierBeforeProductionCycle =
        pipeline.playoutExpirationFrontier(m03.moduleId, m03.incarnationId)

    f94RunProductionPlayoutCycle(
        wiring = wiring,
        sessionId = sessionId,
        tickMediaTimeMs = tickMediaTimeMs,
        nowMs = joinWall,
    )
    val nextAfterNotReadyCycle = pipeline.nextExpectedSlot(m03.moduleId, m03.incarnationId)
    val frontierAfterNotReadyCycle =
        pipeline.playoutExpirationFrontier(m03.moduleId, m03.incarnationId)

    val firstQueued =
        wiring.admitProtectedDatagram(
            sessionId,
            f94TonePacket(m03, 20483),
            joinWall + 1,
        )
    assertEquals(FrameAdmitDisposition.QUEUED, firstQueued.frameAdmit)

    val readinessSnapshotAfterFirstQueued =
        f94ActivationSnapshot(wiring, sessionId, m03.moduleId, m03.incarnationId)
    val readinessAfterFirstQueued =
        LateJoinSourcePlayoutActivationFence.readiness(readinessSnapshotAfterFirstQueued)
    val sharedMixAtFirstQueued =
        wiring.resolvePlayoutMixSlot(sessionId, tickMediaTimeMs)?.slot
            ?: error("expected resolved mix slot after first queued")
    val bufferSize =
        pipeline.bufferedSlots(m03.moduleId, m03.incarnationId).size

    val phaseLeadSlots =
        com.talkback.core.conference.transport.LateJoinSteadyPhaseLeadContract.STEADY_PHASE_LEAD_SLOTS
    val phaseLeadFirstSlot = 20483L
    for (i in 1..phaseLeadSlots.toInt()) {
        val sourceSlot = phaseLeadFirstSlot + i
        assertEquals(
            FrameAdmitDisposition.QUEUED,
            f94AdmitJitterFrame(
                wiring = wiring,
                sessionId = sessionId,
                binding = m03,
                mediaSlot = sourceSlot.toInt(),
                rxWallMs = joinWall + 1 + i,
            ),
        )
    }

    val readinessSnapshotAtPhaseLead =
        f94ActivationSnapshot(wiring, sessionId, m03.moduleId, m03.incarnationId)
    val sharedMixAtPhaseLead =
        wiring.resolvePlayoutMixSlot(sessionId, tickMediaTimeMs)?.slot
            ?: error("expected resolved mix slot at phase lead")
    val bufferSizeAtPhaseLead =
        pipeline.bufferedSlots(m03.moduleId, m03.incarnationId).size

    f94RunProductionPlayoutCycle(
        wiring = wiring,
        sessionId = sessionId,
        tickMediaTimeMs = tickMediaTimeMs,
        nowMs = joinWall + MediaJitterConstants.MEDIA_SLOT_MS,
    )

    val nextAfterReadyCycle =
        pipeline.nextExpectedSlot(m03.moduleId, m03.incarnationId)
            ?: error("nextExpected missing after ready pull")
    val followOnSlot = nextAfterReadyCycle
    val followOn =
        wiring.admitProtectedDatagram(
            sessionId,
            f94TonePacket(m03, followOnSlot.toInt()),
            joinWall + MediaJitterConstants.MEDIA_SLOT_MS * 2,
        )

    return F94ContractActivationContext(
        sessionId = sessionId,
        m03 = m03,
        readinessBeforeFirstQueued = readinessBeforeFirstQueued,
        readinessSnapshotAfterFirstQueued = readinessSnapshotAfterFirstQueued,
        sharedMixAtFirstQueued = sharedMixAtFirstQueued,
        readinessSnapshotAtPhaseLead = readinessSnapshotAtPhaseLead,
        sharedMixAtPhaseLead = sharedMixAtPhaseLead,
        nextBeforeProductionCycle = nextBeforeProductionCycle,
        nextAfterNotReadyCycle = nextAfterNotReadyCycle,
        frontierBeforeProductionCycle = frontierBeforeProductionCycle,
        frontierAfterNotReadyCycle = frontierAfterNotReadyCycle,
        readinessAfterFirstQueued = readinessAfterFirstQueued,
        nextAfterReadyCycle = nextAfterReadyCycle,
        bufferSizeAfterFirstQueued = bufferSize,
        bufferSizeAtPhaseLead = bufferSizeAtPhaseLead,
        firstWireAfterReadyOutcome = followOn.frameAdmit!!,
    )
}

private fun f94ActivationSnapshot(
    wiring: ConferenceSessionMediaWiring,
    sessionId: String,
    sourceIdentity: String,
    incarnationId: Long,
): LateJoinSourcePlayoutActivationFence.SourceActivationSnapshot =
    LateJoinSourcePlayoutActivationFence.snapshotFrom(
        wiring.orchestrator(sessionId)!!.pipeline,
        sourceIdentity,
        incarnationId,
    )

private fun f94PlcAdvanceNextExpectedTo(
    pipeline: com.talkback.core.conference.runtime.MediaExecutionPipeline,
    binding: MemberBindingFact,
    targetNextExpected: Long,
    pullWallMs: Long,
) {
    var next =
        pipeline.nextExpectedSlot(binding.moduleId, binding.incarnationId)
            ?: return
    var slotMediaTimeMs = pullWallMs - MediaJitterConstants.MAX_PLAYOUT_DELAY_MS - 1
    while (next < targetNextExpected) {
        pipeline.pullSlot(
            sourceIdentity = binding.moduleId,
            incarnationId = binding.incarnationId,
            slot = next,
            slotMediaTimeMs = slotMediaTimeMs,
            nowMs = pullWallMs,
        )
        val advanced =
            pipeline.nextExpectedSlot(binding.moduleId, binding.incarnationId)
                ?: break
        if (advanced <= next) break
        next = advanced
        slotMediaTimeMs += MediaJitterConstants.MEDIA_SLOT_MS
    }
}

private fun f94RunLegacyPlayoutCycle(
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

private fun f94RunProductionPlayoutCycle(
    wiring: ConferenceSessionMediaWiring,
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

private fun f94AdmitJitterFrame(
    wiring: ConferenceSessionMediaWiring,
    sessionId: String,
    binding: MemberBindingFact,
    mediaSlot: Int,
    rxWallMs: Long,
): FrameAdmitDisposition {
    val mixReferenceSlot =
        wiring.sessionPlayoutTargetSlot(sessionId, rxWallMs)
            ?: wiring.resolvePlayoutMixSlot(sessionId, rxWallMs)?.slot
    val orchestrator = wiring.orchestrator(sessionId)!!
    return orchestrator.admitFrame(
        com.talkback.core.conference.runtime.AdmittedMediaFrame(
            sourceIdentity = binding.moduleId,
            incarnationId = binding.incarnationId,
            mediaSlot = mediaSlot.toLong(),
            mediaTimeMs = rxWallMs,
            arrivalMs = rxWallMs,
        ),
        nowMs = rxWallMs,
        sharedMixReferenceSlot = mixReferenceSlot,
    )
}

private fun f94TonePacket(
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
