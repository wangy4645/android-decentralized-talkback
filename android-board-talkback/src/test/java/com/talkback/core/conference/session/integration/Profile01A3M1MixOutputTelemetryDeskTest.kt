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
import com.talkback.core.conference.transport.Phase1MediaHarness
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A3-M1 — mixer output telemetry fidelity (desk only; no new audio policy tests).
 */
class Profile01A3M1MixOutputTelemetryDeskTest {
    private lateinit var wiring: ConferenceSessionMediaWiring
    private var wallMs: Long = 0L
    private var priorLocalModuleIdProvider: (() -> String)? = null
    private val telemetryLines = mutableListOf<String>()

    @Before
    fun setUp() {
        priorLocalModuleIdProvider = ConferenceSessionMediaCoordinatorDelegate.localModuleIdProvider
        ConferenceSessionMediaCoordinatorDelegate.localModuleIdProvider = { LOCAL }
        MeetingProductMediaShadow.enabled = true
        Profile01A3MixOutputTelemetry.resetForTest()
        Profile01A3MixOutputTelemetry.testLogSink = { telemetryLines += it }
        wiring = ConferenceSessionMediaWiring.forHarness()
        ConferenceSessionMediaBridge.wiring = wiring
        wallMs = 1_793_000_000_000L
    }

    @After
    fun tearDown() {
        ConferenceSessionMediaCoordinatorDelegate.localModuleIdProvider = priorLocalModuleIdProvider
        ConferenceSessionMediaBridge.wiring = null
        Profile01A3MixOutputTelemetry.resetForTest()
    }

    @Test
    fun twoRemotePcmInputs_contributorsAndRmsNonSilent() {
        val ctx = establishSession(includeM04 = false)
        var slot = SESSION_ANCHOR
        repeat(WINDOW_CYCLES) {
            wallMs += MediaJitterConstants.MEDIA_SLOT_MS
            admitTone(ctx.sessionId, ctx.m01, slot, wallMs)
            admitTone(ctx.sessionId, ctx.m03, slot, wallMs + 1)
            slot += 1
            runResolvedCycle(ctx.sessionId)
        }
        val summary = latestSummary()
        assertTrue(summary.contributors.contains("M01"))
        assertTrue(summary.contributors.contains("M03"))
        assertFalse(summary.contributors.contains(LOCAL))
        assertTrue(summary.outputFrames.toLong() > 0L)
        assertNotEquals("na", summary.rmsDbfs)
        assertTrue(summary.rmsDbfs.toDouble() > SILENCE_RMS_DBFS_CEILING)
        assertTrue(summary.peakDbfs.toDouble() > SILENCE_RMS_DBFS_CEILING)
    }

    @Test
    fun sourceContribution_emptyAndPlcClassifiedCorrectly() {
        assertEquals(
            Profile01A3MixOutputTelemetry.SourceContributionKind.EMPTY,
            Profile01A3MixOutputTelemetry.classifySourceContribution(
                "M04",
                SlotPullDisposition.EMPTY,
                emptySet(),
            ),
        )
        assertEquals(
            Profile01A3MixOutputTelemetry.SourceContributionKind.PLC,
            Profile01A3MixOutputTelemetry.classifySourceContribution(
                "M03",
                SlotPullDisposition.PLC_SYNTHESIS,
                setOf("M03"),
            ),
        )
        assertEquals(
            Profile01A3MixOutputTelemetry.SourceContributionKind.REAL,
            Profile01A3MixOutputTelemetry.classifySourceContribution(
                "M01",
                SlotPullDisposition.DECODE_FRAME,
                setOf("M01"),
            ),
        )
    }

    @Test
    fun mixerNoOutput_outputFramesZero_notSilenceMasquerade() {
        val emptyMix =
            com.talkback.core.conference.runtime.MixCycleResult(
                topKIdentities = setOf("M01"),
                decodeInvocationIdentities = emptySet(),
                mixParticipantIdentities = emptySet(),
                mixedBlock =
                    com.talkback.core.conference.runtime.MixedBlock(
                        samples = shortArrayOf(),
                        mixParticipantCount = 0,
                        peakAbsFs = 0.0,
                    ),
                sourcePullDispositions = mapOf("M01" to SlotPullDisposition.EMPTY),
            )
        repeat(WINDOW_CYCLES) {
            Profile01A3MixOutputTelemetry.recordCycle(
                sessionId = "a3m1-empty-mix",
                owner = Profile01A3MixOutputTelemetry.OWNER_MULTICAST_PRODUCTION,
                mixCycle = emptyMix,
                playoutObserved = false,
            )
        }
        val summary = latestSummary()
        assertEquals("0", summary.outputFrames)
        assertEquals("0", summary.silenceFrames)
        assertEquals("na", summary.rmsDbfs)
        assertEquals("na", summary.peakDbfs)
        assertEquals("0", summary.writeCalls)
        assertEquals("0", summary.writtenFrames)
    }

    @Test
    fun normalOutput_writeCountersMatchProductionPlayoutSeam() {
        val ctx = establishSession(includeM04 = false)
        var expectedWriteCalls = 0L
        var expectedWrittenFrames = 0L
        var slot = SESSION_ANCHOR
        repeat(WINDOW_CYCLES) {
            wallMs += MediaJitterConstants.MEDIA_SLOT_MS
            admitTone(ctx.sessionId, ctx.m01, slot, wallMs)
            admitTone(ctx.sessionId, ctx.m03, slot, wallMs + 1)
            slot += 1
            val resolved = wiring.resolvePlayoutMixSlot(ctx.sessionId, wallMs) ?: return@repeat
            val result =
                wiring.runMixPlayoutCycle(
                    ctx.sessionId,
                    wallMs,
                    resolved.slot,
                    resolved.slotMediaTimeMs,
                    wallMs,
                ) ?: return@repeat
            if (result.playoutObserved && result.mixCycle.mixedBlock.samples.isNotEmpty()) {
                expectedWriteCalls += 1
                expectedWrittenFrames += result.mixCycle.mixedBlock.samples.size
            }
        }
        val summary = latestSummary()
        assertTrue(expectedWriteCalls > 0L)
        assertEquals(expectedWriteCalls.toString(), summary.writeCalls)
        assertEquals(expectedWrittenFrames.toString(), summary.writtenFrames)
    }

    private fun establishSession(includeM04: Boolean): SessionCtx {
        val sessionId = "a3m1-mix-output-${includeM04}"
        assertTrue(
            ConferenceSessionMediaBridge.startSession(
                SessionMediaWiringHarness.sessionFact(sessionId).copy(startedAtMs = wallMs),
            ),
        )
        val m01 = SessionMediaWiringHarness.memberBinding("M01", incarnationId = 1L)
        val local = SessionMediaWiringHarness.memberBinding(LOCAL, incarnationId = 2L)
        val m03 = SessionMediaWiringHarness.memberBinding("M03", incarnationId = 3L)
        val bindings = mutableListOf(m01, local, m03)
        val m04 =
            if (includeM04) {
                SessionMediaWiringHarness.memberBinding("M04", incarnationId = 4L).also {
                    bindings += it
                }
            } else {
                null
            }
        bindings.forEach { assertTrue(wiring.installMember(sessionId, it)) }
        val orchestrator = wiring.orchestrator(sessionId)!!
        orchestrator.pipeline.selection.localModuleIdForTopKExclusion = LOCAL
        observeVoice(orchestrator, *bindings.toTypedArray())
        orchestrator.selectTopK(wallMs)
        return SessionCtx(sessionId, m01, m03, m04)
    }

    private fun runResolvedCycle(sessionId: String) {
        val resolved = wiring.resolvePlayoutMixSlot(sessionId, wallMs) ?: return
        wiring.runMixPlayoutCycle(
            sessionId,
            wallMs,
            resolved.slot,
            resolved.slotMediaTimeMs,
            wallMs,
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

    private data class ParsedSummary(
        val contributors: String,
        val outputFrames: String,
        val rmsDbfs: String,
        val peakDbfs: String,
        val silenceFrames: String,
        val writeCalls: String,
        val writtenFrames: String,
        val sourceContribution: String,
    )

    private fun latestSummary(): ParsedSummary {
        val line =
            telemetryLines.lastOrNull { it.startsWith(Profile01A3MixOutputTelemetry.PHASE) }
                ?: error("missing ${Profile01A3MixOutputTelemetry.PHASE} log")
        val fields =
            line.split(' ').drop(1).associate { token ->
                val eq = token.indexOf('=')
                token.substring(0, eq) to token.substring(eq + 1)
            }
        return ParsedSummary(
            contributors = fields["contributors"] ?: "",
            outputFrames = fields["outputFrames"] ?: "",
            rmsDbfs = fields["rmsDbfs"] ?: "",
            peakDbfs = fields["peakDbfs"] ?: "",
            silenceFrames = fields["silenceFrames"] ?: "",
            writeCalls = fields["writeCalls"] ?: "",
            writtenFrames = fields["writtenFrames"] ?: "",
            sourceContribution = fields["sourceContribution"] ?: "",
        )
    }

    private data class SessionCtx(
        val sessionId: String,
        val m01: MemberBindingFact,
        val m03: MemberBindingFact,
        val m04: MemberBindingFact?,
    )

    companion object {
        private const val LOCAL = "M02"
        private const val SESSION_ANCHOR = 0x5000
        private const val WINDOW_CYCLES = 25
        private const val SILENCE_RMS_DBFS_CEILING = -50.0
    }
}
