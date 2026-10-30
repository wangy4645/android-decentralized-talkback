package com.talkback.core.conference.session.integration

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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Track 2 P1 — Q2/Q3A trajectory on the render scheduler.
 * Healthy M01 must keep REAL while M03/M04 stop. No shared-M slot is an input.
 */
class Profile01T2RenderSchedulerIntegrationDeskTest {
    private lateinit var wiring: ConferenceSessionMediaWiring
    private var wallMs: Long = 0L
    private var priorLocal: (() -> String)? = null

    @Before
    fun setUp() {
        priorLocal = ConferenceSessionMediaCoordinatorDelegate.localModuleIdProvider
        ConferenceSessionMediaCoordinatorDelegate.localModuleIdProvider = { LOCAL }
        MeetingProductMediaShadow.enabled = true
        wiring = ConferenceSessionMediaWiring.forHarness()
        ConferenceSessionMediaBridge.wiring = wiring
        wallMs = 1_793_000_000_000L
    }

    @After
    fun tearDown() {
        ConferenceSessionMediaCoordinatorDelegate.localModuleIdProvider = priorLocal
        ConferenceSessionMediaBridge.wiring = null
    }

    @Test
    fun staleAndLateRemotes_doNotStopHealthyRealOrMixedOutput() {
        val ctx = establish()
        var slot = SESSION_ANCHOR
        repeat(SEED) {
            wallMs += MediaJitterConstants.MEDIA_SLOT_MS
            admit(ctx.sessionId, ctx.m01, slot)
            admit(ctx.sessionId, ctx.m03, slot)
            admit(ctx.sessionId, ctx.m04, slot)
            slot += 1
            render(ctx.sessionId)
        }

        var m01Real = 0
        var mixed = 0
        var m03BlockedM01 = 0
        repeat(TAIL) {
            wallMs += MediaJitterConstants.MEDIA_SLOT_MS
            admit(ctx.sessionId, ctx.m01, slot)
            slot += 1
            val cycle = render(ctx.sessionId)!!.mixCycle
            assertTrue(cycle.renderTickKinds.isNotEmpty())
            val m01 = cycle.renderTickKinds["M01"]
            val m03 = cycle.renderTickKinds["M03"]
            if (m01 == "REAL") m01Real += 1
            if (cycle.mixedBlock.samples.isNotEmpty()) mixed += 1
            if (m01 != "REAL" && (m03 == "PLC" || m03 == "EMPTY")) m03BlockedM01 += 1
            assertTrue(m03 == "REAL" || m03 == "PLC" || m03 == "EMPTY" || m03 == null)
        }

        assertTrue("M01 REAL ticks=$m01Real/$TAIL", m01Real > TAIL / 2)
        assertTrue("mixed output ticks=$mixed/$TAIL", mixed > TAIL / 2)
        assertEquals(0, m03BlockedM01)
    }

    @Test
    fun starvedRemote_withoutLaterPacket_doesNotDefaultConceal() {
        val ctx = establish()
        var slot = SESSION_ANCHOR
        repeat(SEED) {
            wallMs += MediaJitterConstants.MEDIA_SLOT_MS
            admit(ctx.sessionId, ctx.m01, slot)
            admit(ctx.sessionId, ctx.m03, slot)
            slot += 1
            render(ctx.sessionId)
        }
        var m03Plc = 0
        var m03Empty = 0
        var m01Real = 0
        repeat(TAIL) {
            wallMs += MediaJitterConstants.MEDIA_SLOT_MS
            admit(ctx.sessionId, ctx.m01, slot)
            slot += 1
            val cycle = render(ctx.sessionId)!!.mixCycle
            when (cycle.renderTickKinds["M03"]) {
                "PLC" -> m03Plc += 1
                "EMPTY", null -> m03Empty += 1
            }
            if (cycle.renderTickKinds["M01"] == "REAL") m01Real += 1
        }
        assertTrue("M01 REAL=$m01Real/$TAIL", m01Real > TAIL / 2)
        assertTrue("M03 EMPTY=$m03Empty PLC=$m03Plc", m03Empty > m03Plc)
        assertTrue("default-conceal would keep synthesizing PLC, got $m03Plc", m03Plc <= 5)
    }

    @Test
    fun starvedTopK_emptyBuffer_advancesNextExpectedViaPullSlot() {
        val ctx = establish()
        var slot = SESSION_ANCHOR
        repeat(SEED) {
            wallMs += MediaJitterConstants.MEDIA_SLOT_MS
            admit(ctx.sessionId, ctx.m01, slot)
            admit(ctx.sessionId, ctx.m03, slot)
            slot += 1
            render(ctx.sessionId)
        }
        val pipeline = wiring.orchestrator(ctx.sessionId)!!.pipeline
        val nextBefore = pipeline.nextExpectedSlot(ctx.m03.moduleId, ctx.m03.incarnationId)!!
        assertEquals(0, pipeline.jitterSize(ctx.m03.moduleId, ctx.m03.incarnationId))
        repeat(TAIL) {
            wallMs += MediaJitterConstants.MEDIA_SLOT_MS
            admit(ctx.sessionId, ctx.m01, slot)
            slot += 1
            render(ctx.sessionId)
        }
        val nextAfter = pipeline.nextExpectedSlot(ctx.m03.moduleId, ctx.m03.incarnationId)!!
        assertTrue(
            "empty Top-K cursor must advance: before=$nextBefore after=$nextAfter",
            nextAfter >= nextBefore + TAIL,
        )
        assertEquals(0, pipeline.jitterSize(ctx.m03.moduleId, ctx.m03.incarnationId))
    }

    private fun render(sessionId: String) =
        wiring.runRenderMixTick(sessionId, wallMs, wallMs)

    private fun establish(): Ctx {
        val sessionId = "t2-render-scheduler"
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
        listOf(m01, local, m03, m04).forEachIndexed { index, binding ->
            orchestrator.observeVoice(
                VoiceLevelObservation(
                    sourceIdentity = binding.moduleId,
                    incarnationId = binding.incarnationId,
                    voiceActive = true,
                    audioLevel = 90 - index,
                ),
            )
        }
        orchestrator.selectTopK(wallMs)
        return Ctx(sessionId, m01, m03, m04)
    }

    private fun admit(
        sessionId: String,
        binding: MemberBindingFact,
        mediaSlot: Int,
    ) {
        val fixture =
            Phase1MediaHarness.SourceFixture(
                sourceIdentity = binding.sourceIdentity,
                incarnationId = binding.incarnationId,
                ssrc = binding.ssrc,
                sourceAdmissionKey48 = binding.sourceAdmissionKey48,
                audioLevel = 10,
                frequencyHz = 440.0,
            )
        val result =
            wiring.admitProtectedDatagram(
                sessionId = sessionId,
                datagram = Phase1MediaHarness.buildToneProtectedPacket(fixture, mediaSlot),
                rxWallMs = wallMs,
            )
        val admit = result.frameAdmit ?: FrameAdmitDisposition.NOT_ADMITTED_INCARNATION
        assertEquals(FrameAdmitDisposition.QUEUED, admit)
    }

    private data class Ctx(
        val sessionId: String,
        val m01: MemberBindingFact,
        val m03: MemberBindingFact,
        val m04: MemberBindingFact,
    )

    companion object {
        private const val LOCAL = "M02"
        private const val SESSION_ANCHOR = 0x5000
        private const val SEED = 8
        private const val TAIL = 30
    }
}
