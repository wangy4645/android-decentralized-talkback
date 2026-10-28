package com.talkback.core.conference.session.integration

import com.talkback.core.conference.aqc.AqcRuntime
import com.talkback.core.conference.runtime.FrameAdmitDisposition
import com.talkback.core.conference.runtime.MediaJitterConstants
import com.talkback.core.conference.runtime.MixCycleResult
import com.talkback.core.conference.runtime.MixedBlock
import com.talkback.core.conference.runtime.OpusTestVectors
import com.talkback.core.conference.runtime.SlotPullDisposition
import com.talkback.core.conference.runtime.VoiceLevelObservation
import com.talkback.core.conference.session.ConferenceSessionMediaBridge
import com.talkback.core.conference.session.ConferenceSessionMediaWiring
import com.talkback.core.conference.session.MemberBindingFact
import com.talkback.core.conference.session.SessionMediaWiringHarness
import com.talkback.core.conference.transport.ConferenceMulticastRealMediaAssembly
import com.talkback.core.conference.transport.Phase1MediaHarness
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * B-R desk gates R1–R4: production per-source playout restore, not AQC quality.
 */
class Profile01PerSourcePlayoutRestoreDeskTest {
    private val sessionId = "br-per-source-restore"
    private val baseSlot = 0x9_000
    private lateinit var wiring: ConferenceSessionMediaWiring

    @Before
    fun setUp() {
        AqcRuntime.resetToProductionDefaults()
        Profile01A3MixOutputTelemetry.resetForTest()
        MeetingProductMediaShadow.enabled = true
        wiring = ConferenceSessionMediaWiring.forHarness()
        ConferenceSessionMediaBridge.wiring = wiring
    }

    @After
    fun tearDown() {
        ConferenceSessionMediaBridge.wiring = null
        Profile01A3MixOutputTelemetry.resetForTest()
        AqcRuntime.resetToProductionDefaults()
    }

    @Test
    fun r1_resolveUsesPerSourceSlots_andMixPullsDecodeFrame() {
        val remote = startRemoteWithQueuedFrames(packetCount = 8)
        val tick = playTick(packetCount = 8)
        val pullNow = tick + 10L
        val resolved = wiring.resolvePlayoutMixSlot(sessionId, tick, pullNow)
            ?: error("resolvePlayoutMixSlot must return a slot")
        assertTrue(resolved.perSourceSlots.isNotEmpty())
        assertNotNull(resolved.perSourceSlots[remote.sourceIdentity])

        val mix =
            wiring.runMixPlayoutCycle(
                sessionId,
                mixNowMs(remote, resolved),
                resolved.slot,
                resolved.slotMediaTimeMs,
                resolved.perSourceSlots,
            ) ?: error("runMixPlayoutCycle")
        assertEquals(
            SlotPullDisposition.DECODE_FRAME,
            mix.mixCycle.sourcePullDispositions[remote.sourceIdentity],
        )
        assertTrue(remote.sourceIdentity in mix.mixCycle.mixParticipantIdentities)
        assertTrue(mix.mixCycle.mixedBlock.samples.isNotEmpty())
    }

    @Test
    fun r2_validWindow_emitsOutputFramesWriteCallsAndRealNotPlcOnly() {
        val remote = startRemoteWithQueuedFrames(packetCount = 30)
        val lines = mutableListOf<String>()
        Profile01A3MixOutputTelemetry.testLogSink = { lines += it }

        var lastMix: MixCycleResult? = null
        repeat(25) { i ->
            val tick = WALL0 + i * 20L
            val pullNow = tick + 10L
            val resolved =
                wiring.resolvePlayoutMixSlot(sessionId, tick, pullNow)
                    ?: error("resolve must stay non-null while buffer has frames")
            val mix =
                wiring.runMixPlayoutCycle(
                    sessionId,
                    pullNow,
                    resolved.slot,
                    resolved.slotMediaTimeMs,
                    resolved.perSourceSlots,
                ) ?: error("mix")
            lastMix = mix.mixCycle
            assertTrue(mix.mixCycle.mixedBlock.samples.isNotEmpty())
            assertTrue(mix.playoutObserved)
        }

        val summary = lines.joinToString("\n")
        assertTrue("expected A3 emit, got: $summary", summary.contains("A3_MIX_OUTPUT") || summary.contains("writeCalls="))
        val writeCalls = Regex("""writeCalls=(\d+)""").findAll(summary).map { it.groupValues[1].toInt() }.toList()
        assertTrue("writeCalls must be present", writeCalls.isNotEmpty())
        assertTrue("writeCalls must be > 0, got $writeCalls in $summary", writeCalls.any { it > 0 })
        val outputFrames = Regex("""outputFrames=(\d+)""").findAll(summary).map { it.groupValues[1].toLong() }.toList()
        assertTrue("outputFrames must be > 0", outputFrames.any { it > 0L })
        assertTrue(
            "REAL contribution required, not PLC-only: $summary",
            Regex("""real=([1-9]\d*)%""").containsMatchIn(summary) ||
                Regex("""real=\d+%\(([1-9]\d*)\)""").containsMatchIn(summary),
        )
        val mixCycle = lastMix ?: error("mix")
        assertTrue(remote.sourceIdentity in mixCycle.mixParticipantIdentities)
    }

    @Test
    fun r3_emptyWindow_writeCallsZero_andPlcClassificationIsNotContinuousPlay() {
        val lines = mutableListOf<String>()
        Profile01A3MixOutputTelemetry.testLogSink = { lines += it }
        val mixCycle =
            MixCycleResult(
                topKIdentities = setOf("M01"),
                decodeInvocationIdentities = emptySet(),
                mixParticipantIdentities = emptySet(),
                mixedBlock = MixedBlock(shortArrayOf(), mixParticipantCount = 0, peakAbsFs = 0.0),
                sourcePullDispositions = mapOf("M01" to SlotPullDisposition.PLC_SYNTHESIS),
            )
        repeat(25) {
            Profile01A3MixOutputTelemetry.recordCycle(
                sessionId = "empty-window",
                owner = Profile01A3MixOutputTelemetry.OWNER_MULTICAST_PRODUCTION,
                mixCycle = mixCycle,
                playoutObserved = true,
            )
        }
        val summary = lines.joinToString("\n")
        assertTrue(summary.contains("writeCalls=0"))
        assertTrue(summary.contains("outputFrames=0"))
        assertFalse(summary.contains("real=100%"))
        assertEquals(
            Profile01A3MixOutputTelemetry.SourceContributionKind.PLC,
            Profile01A3MixOutputTelemetry.classifySourceContribution(
                "M01",
                SlotPullDisposition.PLC_SYNTHESIS,
                emptySet(),
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
    fun r4_aqcFlagsStayOff_emptyPullDoesNotSynthesizeMixPcm() {
        assertFalse(AqcRuntime.txProcessingEnabled)
        assertFalse(AqcRuntime.membershipMixEnabled)
        assertFalse(AqcRuntime.continuousPlayoutEnabled)

        val assembly = ConferenceMulticastRealMediaAssembly.createHarness()
        val fixture = Phase1MediaHarness.threeSourceFixtures.first()
        Phase1MediaHarness.installAuthority(
            assembly.orchestrator.authority.store,
            listOf(fixture),
        )
        assembly.orchestrator.authority.syncAllAdmittedToRuntime()
        assembly.opusPayloadStore.put(
            fixture.sourceIdentity,
            fixture.incarnationId,
            10L,
            OpusTestVectors.encodeTone(),
        )
        val mediaTime = 1_000L
        val now = mediaTime + MediaJitterConstants.MAX_PLAYOUT_DELAY_MS + 5
        val mix = assembly.pipeline.executeTimedMixCycle(now, 10L, mediaTime)
        assertTrue(mix.mixCycle.mixParticipantIdentities.isEmpty())
        assertTrue(mix.mixCycle.mixedBlock.samples.isEmpty())

        assertFalse(AqcRuntime.txProcessingEnabled)
        assertFalse(AqcRuntime.membershipMixEnabled)
        assertFalse(AqcRuntime.continuousPlayoutEnabled)
    }

    private fun startRemoteWithQueuedFrames(packetCount: Int): MemberBindingFact {
        val fact =
            SessionMediaWiringHarness.sessionFact(sessionId)
                .copy(startedAtMs = WALL0 - 10_000L)
        assertTrue(ConferenceSessionMediaBridge.startSession(fact))
        val remote = SessionMediaWiringHarness.memberBinding("M02")
        assertTrue(wiring.installMember(sessionId, remote))
        val orchestrator = wiring.orchestrator(sessionId) ?: error("orchestrator")
        orchestrator.selection.observeVoice(
            VoiceLevelObservation(
                sourceIdentity = remote.sourceIdentity,
                incarnationId = remote.incarnationId,
                voiceActive = true,
                audioLevel = 80,
            ),
        )
        for (i in 0 until packetCount) {
            assertEquals(
                FrameAdmitDisposition.QUEUED,
                wiring.admitProtectedDatagram(
                    sessionId,
                    tonePacket(remote, baseSlot + i),
                    WALL0 + i * 20L,
                ).frameAdmit,
            )
        }
        return remote
    }

    private fun playTick(packetCount: Int): Long = WALL0 + packetCount * 20L

    /** Pull deadline uses frame mediaTimeMs domain, not the wall play-tick. */
    private fun mixNowMs(
        remote: MemberBindingFact,
        resolved: ConferenceSessionMediaWiring.BufferedMixSlot,
    ): Long {
        val pullSlot =
            resolved.perSourceSlots[remote.sourceIdentity]
                ?: error("missing per-source slot")
        val pipeline =
            wiring.orchestrator(sessionId)?.pipeline ?: error("pipeline")
        val frame =
            pipeline.peekBufferedFrame(
                remote.sourceIdentity,
                remote.incarnationId,
                pullSlot,
            ) ?: error("expected buffered frame at per-source slot $pullSlot")
        return frame.mediaTimeMs + 10L
    }

    private fun tonePacket(binding: MemberBindingFact, mediaSlot: Int): ByteArray {
        val fixture =
            Phase1MediaHarness.SourceFixture(
                sourceIdentity = binding.sourceIdentity,
                incarnationId = binding.incarnationId,
                ssrc = binding.ssrc,
                sourceAdmissionKey48 = binding.sourceAdmissionKey48,
                audioLevel = 80,
                frequencyHz = 440.0,
            )
        return Phase1MediaHarness.buildToneProtectedPacket(fixture, mediaSlot)
    }

    companion object {
        private const val WALL0 = 1_700_000_400_000L
    }
}
