package com.talkback.core.conference.session.integration

import com.talkback.core.conference.runtime.ConferenceMediaExecutionOrchestrator
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
import com.talkback.core.conference.transport.LateJoinSteadyPhaseLeadContract
import com.talkback.core.conference.transport.Phase1MediaHarness
import com.talkback.core.conference.transport.SharedMixPlayoutResolverAuthority
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A3-Q3A — selected stale remote must not min-pin shared-M resolver (≠ A3-L local exclusion).
 */
class Profile01A3Q3AStaleResolverCandidateDeskTest {
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
        wallMs = 1_793_000_000_000L
    }

    @After
    fun tearDown() {
        ConferenceSessionMediaCoordinatorDelegate.localModuleIdProvider = priorLocalModuleIdProvider
        ConferenceSessionMediaBridge.wiring = null
    }

    @Test
    fun staleSelectedRemote_mustNotPinSharedResolver_whileHealthyRemoteAdvances() {
        val ctx = establishSession()
        val pipeline = wiring.orchestrator(ctx.sessionId)!!.pipeline

        for (slot in STALE_BUFFER_START until STALE_BUFFER_START + STALE_BUFFER_SLOTS) {
            admitTone(ctx.sessionId, ctx.m03, slot, wallMs + 1)
            admitTone(ctx.sessionId, ctx.m04, slot, wallMs + 2)
        }

        var m01Slot = STALE_BUFFER_START + STALE_BUFFER_SLOTS
        val staleCeilingMix =
            STALE_BUFFER_START + STALE_BUFFER_SLOTS - 1 +
                LateJoinSteadyPhaseLeadContract.STEADY_PHASE_LEAD_SLOTS
        while (true) {
            wallMs += MediaJitterConstants.MEDIA_SLOT_MS
            admitTone(ctx.sessionId, ctx.m01, m01Slot, wallMs)
            m01Slot += 1
            runResolvedCycle(ctx.sessionId, wallMs)
            val target = wiring.sessionPlayoutTargetSlot(ctx.sessionId, wallMs)
            if (target != null && target > staleCeilingMix) break
            require(m01Slot < STALE_BUFFER_START + 200) { "bootstrap did not reach stale ceiling" }
        }

        val phase2Resolved = mutableListOf<Long>()
        var m01RealPulls = 0
        repeat(PHASE2_CYCLES) {
            wallMs += MediaJitterConstants.MEDIA_SLOT_MS
            admitTone(ctx.sessionId, ctx.m01, m01Slot, wallMs)
            m01Slot += 1
            val target =
                wiring.sessionPlayoutTargetSlot(ctx.sessionId, wallMs)
                    ?: error("missing playout target")
            val m03Snap =
                LateJoinSourcePlayoutActivationFence.snapshotFrom(
                    pipeline,
                    ctx.m03.moduleId,
                    ctx.m03.incarnationId,
                )
            assertFalse(
                "stale M03 must lose resolver forward authority at live target (target=$target latest=${m03Snap.latestBufferedSourceSlot})",
                SharedMixPlayoutResolverAuthority.hasForwardAuthority(m03Snap, target),
            )
            val resolved = wiring.resolvePlayoutMixSlot(ctx.sessionId, wallMs)
            assertNotNull(resolved)
            val buffered = resolved!!
            phase2Resolved += buffered.slot
            val mix =
                wiring.runMixPlayoutCycle(
                    ctx.sessionId,
                    wallMs,
                    buffered.slot,
                    buffered.slotMediaTimeMs,
                    wallMs,
                )
            if (mix != null &&
                mix.mixCycle.sourcePullDispositions[ctx.m01.moduleId] == SlotPullDisposition.DECODE_FRAME
            ) {
                m01RealPulls += 1
            }
        }

        val topK =
            wiring.orchestrator(ctx.sessionId)!!.selection.currentTopK().members
                .map { it.sourceIdentity }
                .toSet()
        assertEquals(setOf("M01", "M03", "M04"), topK)
        assertFalse(topK.contains(LOCAL))

        assertTrue("phase2 produced no resolved samples", phase2Resolved.isNotEmpty())
        val first = phase2Resolved.first()
        val last = phase2Resolved.last()
        assertTrue(
            "shared M pinned by stale remote (first=$first last=$last target~=$m01Slot)",
            last > first + MIN_FORWARD_SLOTS,
        )
        assertTrue(
            "healthy M01 never got REAL pull after stale pin (pulls=$m01RealPulls)",
            m01RealPulls > PHASE2_CYCLES / 4,
        )
    }

    @Test
    fun healthySlowerRemote_stillConstrainsResolver_notChasingLeadOnly() {
        val ctx = establishSession()
        var slot = SESSION_ANCHOR
        val resolvedTrace = mutableListOf<Long>()
        val targetTrace = mutableListOf<Long>()

        repeat(SLOWER_CYCLES) {
            wallMs += MediaJitterConstants.MEDIA_SLOT_MS
            val m01Slot = slot
            val m03Slot = slot - SLOWER_LAG_SLOTS
            admitTone(ctx.sessionId, ctx.m01, m01Slot, wallMs)
            if (m03Slot >= SESSION_ANCHOR) {
                admitTone(ctx.sessionId, ctx.m03, m03Slot, wallMs + 1)
            }
            slot += 1
            val target =
                wiring.sessionPlayoutTargetSlot(ctx.sessionId, wallMs)
                    ?: error("missing target")
            targetTrace += target
            val resolved = wiring.resolvePlayoutMixSlot(ctx.sessionId, wallMs)!!
            resolvedTrace += resolved.slot
            runResolvedCycle(ctx.sessionId, wallMs)
        }

        assertTrue(resolvedTrace.isNotEmpty())
        val lagSamples =
            resolvedTrace.zip(targetTrace).count { (resolved, target) ->
                target - resolved >= SLOWER_LAG_SLOTS - 1
            }
        assertTrue(
            "resolver chased lead only (lagSamples=$lagSamples/${resolvedTrace.size})",
            lagSamples > SLOWER_CYCLES / 4,
        )
    }

    private fun establishSession(): QuadCtx {
        val sessionId = "a3q3a-stale-resolver-candidate"
        assertTrue(
            ConferenceSessionMediaBridge.startSession(
                SessionMediaWiringHarness.sessionFact(sessionId).copy(startedAtMs = wallMs),
            ),
        )
        val m01 = SessionMediaWiringHarness.memberBinding("M01", incarnationId = 1L)
        val local = SessionMediaWiringHarness.memberBinding(LOCAL, incarnationId = 2L)
        val m03 = SessionMediaWiringHarness.memberBinding("M03", incarnationId = 3L)
        val m04 = SessionMediaWiringHarness.memberBinding("M04", incarnationId = 4L)
        listOf(m01, local, m03, m04).forEach { assertTrue(wiring.installMember(sessionId, it)) }
        val orchestrator = wiring.orchestrator(sessionId)!!
        orchestrator.pipeline.selection.localModuleIdForTopKExclusion = LOCAL
        observeVoice(orchestrator, m01, local, m03, m04)
        orchestrator.selectTopK(wallMs)
        return QuadCtx(sessionId, m01, local, m03, m04)
    }

    private fun runResolvedCycle(
        sessionId: String,
        tickMediaTimeMs: Long,
    ) {
        val resolved =
            wiring.resolvePlayoutMixSlot(sessionId, tickMediaTimeMs)
                ?: return
        wiring.runMixPlayoutCycle(
            sessionId,
            wallMs,
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

    private data class QuadCtx(
        val sessionId: String,
        val m01: MemberBindingFact,
        val local: MemberBindingFact,
        val m03: MemberBindingFact,
        val m04: MemberBindingFact,
    )

    companion object {
        private const val LOCAL = "M02"
        private const val SESSION_ANCHOR = 0x5000 // 20480
        private const val STALE_BUFFER_START = SESSION_ANCHOR
        private const val STALE_BUFFER_SLOTS = 5
        private const val PHASE2_CYCLES = 80
        private const val MIN_FORWARD_SLOTS = 15
        private const val SLOWER_CYCLES = 50
        /** Within frozen 120 ms playout budget (6 slots). */
        private const val SLOWER_LAG_SLOTS = 4
    }
}
