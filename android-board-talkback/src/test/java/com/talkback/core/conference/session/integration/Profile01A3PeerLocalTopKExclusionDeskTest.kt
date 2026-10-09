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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * A3-P — local TX source remains in registry/catalog but is INELIGIBLE for Top-K/mix.
 */
class Profile01A3PeerLocalTopKExclusionDeskTest {
    private lateinit var wiring: ConferenceSessionMediaWiring
    private var wallMs: Long = 0L
    private val scenarioCounter = AtomicInteger()
    private var priorLocalModuleIdProvider: (() -> String)? = null

    @Before
    fun setUp() {
        priorLocalModuleIdProvider = ConferenceSessionMediaCoordinatorDelegate.localModuleIdProvider
        ConferenceSessionMediaCoordinatorDelegate.localModuleIdProvider = { LOCAL }
        MeetingProductMediaShadow.enabled = true
        wiring = ConferenceSessionMediaWiring.forHarness()
        ConferenceSessionMediaBridge.wiring = wiring
        wallMs = 1_792_000_000_000L + scenarioCounter.incrementAndGet()
    }

    @After
    fun tearDown() {
        ConferenceSessionMediaCoordinatorDelegate.localModuleIdProvider = priorLocalModuleIdProvider
        ConferenceSessionMediaBridge.wiring = null
    }

    @Test
    fun quadSource_localExcludedFromTopKAndMixPull_remoteSelectionUnchanged() {
        val ctx = establishQuadSourceSession(localModuleId = LOCAL)
        val orchestrator = wiring.orchestrator(ctx.sessionId)!!
        val pipeline = orchestrator.pipeline

        val topK =
            orchestrator.selection.currentTopK().members.map { it.sourceIdentity }.toSet()
        assertEquals(setOf("M01", "M03", "M04"), topK)
        assertFalse(topK.contains(LOCAL))
        assertFalse(orchestrator.selection.isDecodeEligible(LOCAL, ctx.local.incarnationId))

        var localSlot = SESSION_ANCHOR
        repeat(PREFILL_SLOTS) {
            wallMs += MediaJitterConstants.MEDIA_SLOT_MS
            assertEquals(
                FrameAdmitDisposition.QUEUED,
                admitTone(ctx.sessionId, ctx.local, localSlot, wallMs),
            )
            localSlot += 1
        }
        val localDepthAfterPrefill = pipeline.jitterSize(ctx.local.moduleId, ctx.local.incarnationId)
        assertTrue(localDepthAfterPrefill >= PREFILL_SLOTS - 1)

        val localNextBefore = pipeline.nextExpectedSlot(ctx.local.moduleId, ctx.local.incarnationId)
        val localJitterDepthBefore =
            pipeline.jitterSize(ctx.local.moduleId, ctx.local.incarnationId)

        var remoteSlot = SESSION_ANCHOR
        for (cycle in 0 until MIX_CYCLES) {
            wallMs += MediaJitterConstants.MEDIA_SLOT_MS
            admitTone(ctx.sessionId, ctx.m01, remoteSlot, wallMs)
            remoteSlot += 1
            val resolved = wiring.resolvePlayoutMixSlot(ctx.sessionId, wallMs)
            if (resolved != null) {
                val playout =
                    wiring.runMixPlayoutCycle(
                        ctx.sessionId,
                        wallMs,
                        resolved.slot,
                        resolved.slotMediaTimeMs,
                    )
                        ?: continue
                val mix = playout.mixCycle
                assertFalse(mix.topKIdentities.contains(LOCAL))
                assertTrue(mix.topKIdentities.contains("M01"))
                assertFalse(mix.decodeInvocationIdentities.contains(LOCAL))
                assertFalse(mix.mixParticipantIdentities.contains(LOCAL))
            }
        }

        assertEquals(
            localNextBefore,
            pipeline.nextExpectedSlot(ctx.local.moduleId, ctx.local.incarnationId),
        )
        assertEquals(
            localJitterDepthBefore,
            pipeline.jitterSize(ctx.local.moduleId, ctx.local.incarnationId),
        )
    }

    private fun establishQuadSourceSession(localModuleId: String): QuadCtx {
        val sessionId = "a3p-local-exclusion-${scenarioCounter.get()}"
        assertTrue(
            ConferenceSessionMediaBridge.startSession(
                SessionMediaWiringHarness.sessionFact(sessionId).copy(startedAtMs = wallMs),
            ),
        )
        val m01 = SessionMediaWiringHarness.memberBinding("M01", incarnationId = 1L)
        val local = SessionMediaWiringHarness.memberBinding(localModuleId, incarnationId = 2L)
        val m03 = SessionMediaWiringHarness.memberBinding("M03", incarnationId = 3L)
        val m04 = SessionMediaWiringHarness.memberBinding("M04", incarnationId = 4L)
        listOf(m01, local, m03, m04).forEach { binding ->
            assertTrue(wiring.installMember(sessionId, binding))
        }
        val orchestrator = wiring.orchestrator(sessionId)!!
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
        private const val SESSION_ANCHOR = 0x6000
        private const val PREFILL_SLOTS = 6
        private const val MIX_CYCLES = 10
    }
}
