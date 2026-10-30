package com.talkback.core.conference.session.integration

import com.talkback.core.conference.runtime.ConferenceMediaExecutionOrchestrator
import com.talkback.core.conference.runtime.FrameAdmitDisposition
import com.talkback.core.conference.runtime.MediaJitterConstants
import com.talkback.core.conference.runtime.VoiceLevelObservation
import com.talkback.core.conference.session.ConferenceSessionMediaBridge
import com.talkback.core.conference.session.ConferenceSessionMediaCoordinatorDelegate
import com.talkback.core.conference.session.ConferenceSessionMediaWiring
import com.talkback.core.conference.session.MemberBindingFact
import com.talkback.core.conference.session.SessionMediaWiringHarness
import com.talkback.core.conference.transport.Phase1MediaHarness
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A3-L — shared mix liveness under continuous remote ingress while local stays out of Top-K.
 *
 * Replays the process, not the frozen field snapshot: selected remotes keep admitting,
 * session target advances, local self-ingress is excluded from selection.
 * resolvedMixSlot must leave the session anchor. It must not jump to the live target.
 */
class Profile01A3LSharedMixLivenessDeskTest {
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
    fun continuousRemoteIngress_resolvedLeavesAnchor_localDoesNotSupplyTimeline() {
        val ctx = establishSession()
        val pipeline = wiring.orchestrator(ctx.sessionId)!!.pipeline
        val resolvedTrace = mutableListOf<Long>()
        var slot = SESSION_ANCHOR

        repeat(CYCLES) {
            wallMs += MediaJitterConstants.MEDIA_SLOT_MS
            admitTone(ctx.sessionId, ctx.local, slot, wallMs)
            admitTone(ctx.sessionId, ctx.m01, slot, wallMs + 1)
            admitTone(ctx.sessionId, ctx.m03, slot, wallMs + 2)
            admitTone(ctx.sessionId, ctx.m04, slot, wallMs + 3)
            slot += 1
            val resolved = wiring.resolvePlayoutMixSlot(ctx.sessionId, wallMs)
            if (resolved != null) {
                resolvedTrace += resolved.slot
                wiring.runMixPlayoutCycle(
                    ctx.sessionId,
                    wallMs,
                    resolved.slot,
                    resolved.slotMediaTimeMs,
                    wallMs,
                )
            }
        }

        val topK =
            wiring.orchestrator(ctx.sessionId)!!.selection.currentTopK().members
                .map { it.sourceIdentity }
                .toSet()
        assertEquals(setOf("M01", "M03", "M04"), topK)
        assertFalse(topK.contains(LOCAL))

        assertTrue("resolver never committed a mix slot", resolvedTrace.isNotEmpty())
        val first = resolvedTrace.first()
        val last = resolvedTrace.last()
        val target = wiring.sessionPlayoutTargetSlot(ctx.sessionId, wallMs)
        assertNotNull(target)
        assertTrue(
            "resolved stuck at $last (first=$first target=$target)",
            last > first + MIN_FORWARD_SLOTS,
        )
        assertTrue(
            "resolved must not jump to live target (last=$last target=$target)",
            last <= target!!,
        )

        val m01Next = pipeline.nextExpectedSlot(ctx.m01.moduleId, ctx.m01.incarnationId)
        assertNotNull(m01Next)
        assertTrue(
            "selected remote window did not advance (next=$m01Next)",
            m01Next!! > SESSION_ANCHOR + MIN_FORWARD_SLOTS,
        )
        val localNext = pipeline.nextExpectedSlot(ctx.local.moduleId, ctx.local.incarnationId)
        assertEquals(SESSION_ANCHOR.toLong(), localNext)
    }

    private fun establishSession(): QuadCtx {
        val sessionId = "a3l-shared-mix-liveness"
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
        private const val SESSION_ANCHOR = 0x5000
        private const val CYCLES = 40
        private const val MIN_FORWARD_SLOTS = 15
    }
}
