package com.talkback.core.conference.session

import com.talkback.core.conference.aqc.AqcRuntime
import com.talkback.core.conference.runtime.FrameAdmitDisposition
import com.talkback.core.conference.runtime.MediaJitterConstants
import com.talkback.core.conference.runtime.SlotPullDisposition
import com.talkback.core.conference.runtime.VoiceLevelObservation
import com.talkback.core.conference.session.integration.BrightScreenMeetingMixPolicy
import com.talkback.core.conference.session.integration.MeetingProductMediaShadow
import com.talkback.core.conference.session.integration.Profile01A3MixOutputTelemetry
import com.talkback.core.conference.transport.Phase1MediaHarness
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * D1 desk harness — T5 media-time alignment + T4 A3 six-field surfacing (wiring path).
 */
class D1PlayoutTopKDeferralHarnessDeskTest {
    private val sessionId = "d1-harness-t5"
    private val baseSlot = 0xA_100
    private lateinit var wiring: ConferenceSessionMediaWiring

    @Before
    fun setUp() {
        AqcRuntime.resetToProductionDefaults()
        BrightScreenMeetingMixPolicy.resetForTest()
        PlayoutTopKDeferral.resetForTest()
        Profile01A3MixOutputTelemetry.resetForTest()
        MeetingProductMediaShadow.enabled = true
        wiring = ConferenceSessionMediaWiring.forHarness()
        ConferenceSessionMediaBridge.wiring = wiring
        val fact =
            SessionMediaWiringHarness.sessionFact(sessionId)
                .copy(startedAtMs = WALL0 - 10_000L)
        assertTrue(ConferenceSessionMediaBridge.startSession(fact))
    }

    @After
    fun tearDown() {
        ConferenceSessionMediaBridge.wiring = null
        Profile01A3MixOutputTelemetry.resetForTest()
        PlayoutTopKDeferral.resetForTest()
        AqcRuntime.resetToProductionDefaults()
    }

    @Test
    fun t5_twoSourcePulledFrameMediaTimeDeltaWithinOneSlot() {
        val remoteA = installVoiceRemote("M02", audioLevel = 92)
        val remoteB = installVoiceRemote("M03", audioLevel = 88)
        val packetCount = 14
        queueAlignedFrames(remoteA, remoteB, packetCount)

        val tick = WALL0 + packetCount * MediaJitterConstants.MEDIA_SLOT_MS
        val pullNow = tick + 10L
        val resolved =
            wiring.resolvePlayoutMixSlot(sessionId, tick, pullNow)
                ?: error("resolve must succeed with dual-source buffer")
        assertEquals(2, resolved.perSourceSlots.size)

        val pipeline = wiring.orchestrator(sessionId)!!.pipeline
        val mediaTimesMs =
            resolved.perSourceSlots.map { (sourceId, slot) ->
                val inc =
                    when (sourceId) {
                        remoteA.sourceIdentity -> remoteA.incarnationId
                        remoteB.sourceIdentity -> remoteB.incarnationId
                        else -> error("unexpected source $sourceId")
                    }
                val frame =
                    pipeline.peekBufferedFrame(sourceId, inc, slot)
                        ?: error("missing frame for $sourceId slot $slot")
                frame.mediaTimeMs
            }
        val deltaMs = mediaTimesMs.max() - mediaTimesMs.min()
        val limitMs = MediaJitterConstants.MEDIA_SLOT_MS
        assertTrue(
            "mediaTimeMs delta=$deltaMs limit=$limitMs values=$mediaTimesMs slots=${resolved.perSourceSlots}",
            deltaMs <= limitMs,
        )

        val mixNow =
            mediaTimesMs.max() + MediaJitterConstants.MEDIA_SLOT_MS / 2
        val mix =
            wiring.runMixPlayoutCycle(
                sessionId,
                mixNow,
                resolved.slot,
                resolved.slotMediaTimeMs,
                resolved.perSourceSlots,
            ) ?: error("mix cycle")
        assertEquals(
            setOf(remoteA.sourceIdentity, remoteB.sourceIdentity),
            mix.mixCycle.mixParticipantIdentities,
        )
        assertEquals(
            SlotPullDisposition.DECODE_FRAME,
            mix.mixCycle.sourcePullDispositions[remoteA.sourceIdentity],
        )
        assertEquals(
            SlotPullDisposition.DECODE_FRAME,
            mix.mixCycle.sourcePullDispositions[remoteB.sourceIdentity],
        )
        assertTrue(mix.mixCycle.mixedBlock.samples.isNotEmpty())
    }

    @Test
    fun t4_a3SummaryIncludesSixDeferralTelemetryFields() {
        val lines = mutableListOf<String>()
        Profile01A3MixOutputTelemetry.testLogSink = { lines += it }
        val deferral =
            Profile01A3MixOutputTelemetry.TopKDeferralTickTelemetry(
                rankedTopKCount = 3,
                effectiveTopKCount = 2,
                deferredSourceCount = 1,
                pullSucceededSourceCount = 2,
                mixProducedThisTick = true,
                allTopKUnpullableTick = false,
            )
        val emptyMix =
            com.talkback.core.conference.runtime.MixCycleResult(
                topKIdentities = setOf("M02", "M03"),
                decodeInvocationIdentities = setOf("M02", "M03"),
                mixParticipantIdentities = setOf("M02", "M03"),
                mixedBlock =
                    com.talkback.core.conference.runtime.MixedBlock(
                        samples = ShortArray(160) { 100 },
                        mixParticipantCount = 2,
                        peakAbsFs = 0.1,
                    ),
                sourcePullDispositions =
                    mapOf(
                        "M02" to SlotPullDisposition.DECODE_FRAME,
                        "M03" to SlotPullDisposition.DECODE_FRAME,
                    ),
            )
        repeat(25) {
            Profile01A3MixOutputTelemetry.recordCycle(
                sessionId = sessionId,
                owner = Profile01A3MixOutputTelemetry.OWNER_MULTICAST_PRODUCTION,
                mixCycle = emptyMix,
                playoutObserved = true,
                deferralTick = deferral,
            )
        }
        val summary = lines.joinToString("\n")
        for (field in SIX_DEFERRAL_FIELDS) {
            assertTrue("missing $field in $summary", summary.contains("$field="))
        }
        assertTrue(summary.contains("deferredSourceCount=1"))
        assertTrue(summary.contains("mixProducedThisTick=true"))
        assertTrue(summary.contains("contributors=M02,M03"))
    }

    @Test
    fun t1_partialStarvationUsesDeferralEffective_notFallbackSubstitution() {
        val pullable = installVoiceRemote("M02", 95)
        val starved = installVoiceRemote("M03", 90)
        queueAlignedFrames(pullable, starved, packetCount = 12)
        val pipeline = wiring.orchestrator(sessionId)!!.pipeline
        repeat(12) {
            pipeline.pullSlot(
                starved.sourceIdentity,
                starved.incarnationId,
                baseSlot + it.toLong(),
                WALL0 + it * 20L,
                WALL0 + it * 20L,
            )
        }
        val tick = WALL0 + 12 * MediaJitterConstants.MEDIA_SLOT_MS
        val pullNow = tick + 5L
        wiring.resolvePlayoutMixSlot(sessionId, tick, pullNow)
        val ranked = wiring.orchestrator(sessionId)!!.selection.currentTopK().members
        assertTrue(ranked.any { it.sourceIdentity == pullable.sourceIdentity })
        val snap = PlayoutTopKDeferral.lastTickSnapshot(sessionId)
        assertNotNull(snap)
        assertTrue(
            "ranked should still contain starved member",
            ranked.any { it.sourceIdentity == starved.sourceIdentity },
        )
        assertTrue(
            "partial pullable path must not mark allTopKUnpullable",
            snap!!.allTopKUnpullableTick == false,
        )
        val resolved =
            wiring.resolvePlayoutMixSlot(sessionId, tick + 20, pullNow + 20)
                ?: error("pullable source should still resolve")
        assertTrue(resolved.perSourceSlots.containsKey(pullable.sourceIdentity))
    }

    private fun installVoiceRemote(
        moduleId: String,
        audioLevel: Int,
    ): MemberBindingFact {
        val remote = SessionMediaWiringHarness.memberBinding(moduleId)
        assertTrue(wiring.installMember(sessionId, remote))
        wiring.orchestrator(sessionId)!!.selection.observeVoice(
            VoiceLevelObservation(
                sourceIdentity = remote.sourceIdentity,
                incarnationId = remote.incarnationId,
                voiceActive = true,
                audioLevel = audioLevel,
            ),
        )
        return remote
    }

    private fun queueAlignedFrames(
        a: MemberBindingFact,
        b: MemberBindingFact,
        packetCount: Int,
    ) {
        for (i in 0 until packetCount) {
            val wall = WALL0 + i * MediaJitterConstants.MEDIA_SLOT_MS
            val slot = baseSlot + i
            assertEquals(
                FrameAdmitDisposition.QUEUED,
                wiring.admitProtectedDatagram(sessionId, tonePacket(a, slot), wall).frameAdmit,
            )
            assertEquals(
                FrameAdmitDisposition.QUEUED,
                wiring.admitProtectedDatagram(sessionId, tonePacket(b, slot), wall).frameAdmit,
            )
        }
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
                audioLevel = 80,
                frequencyHz = 440.0,
            )
        return Phase1MediaHarness.buildToneProtectedPacket(fixture, mediaSlot)
    }

    companion object {
        private const val WALL0 = 1_700_001_000_000L
        private val SIX_DEFERRAL_FIELDS =
            listOf(
                "rankedTopKCount",
                "effectiveTopKCount",
                "deferredSourceCount",
                "pullSucceededSourceCount",
                "mixProducedThisTick",
                "allTopKUnpullableTick",
            )
    }
}
