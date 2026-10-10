package com.talkback.core.conference.session.integration

import com.talkback.core.conference.authority.MediaKeyContextFact
import com.talkback.core.conference.authority.SourceAuthorizationFact
import com.talkback.core.conference.runtime.EqualWeightMixer
import com.talkback.core.conference.runtime.MixCycleResult
import com.talkback.core.conference.runtime.OpusCodecConstants
import com.talkback.core.conference.runtime.OpusDecodeSeam
import com.talkback.core.conference.runtime.PcmFrame
import com.talkback.core.conference.runtime.SourceMixInputKind
import com.talkback.core.conference.runtime.SourceMixInputSnapshot
import com.talkback.core.conference.runtime.VoiceLevelObservation
import com.talkback.core.conference.transport.ConferenceMulticastRealMediaAssembly
import java.io.File
import kotlin.math.PI
import kotlin.math.sin
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MulticastMixInputHeadroomFieldExperimentTest {
    @Before
    fun setUp() {
        MulticastMixInputHeadroomFieldExperiment.resetForTest()
        Profile01A3MixInputTelemetry.resetForTest()
        Profile01A3MixOutputTelemetry.resetForTest()
    }

    @After
    fun tearDown() {
        MulticastMixInputHeadroomFieldExperiment.resetForTest()
        Profile01A3MixInputTelemetry.resetForTest()
        Profile01A3MixOutputTelemetry.resetForTest()
    }

    @Test
    fun defaultOff_mixPcmFrame_isIdentityReference() {
        val frame = PcmFrame(shortArrayOf(100, -200, 30_000), usableForMix = true)
        assertSame(frame, MulticastMixInputHeadroomFieldExperiment.mixPcmFrame(frame))
    }

    @Test
    fun whenOn_mixPcmFrame_isAttenuatedCopy_withMinus6dBLinearGain() {
        MulticastMixInputHeadroomFieldExperiment.configureForFieldTest(
            enabled = true,
            gainDb = -6.0,
        )
        val frame = PcmFrame(shortArrayOf(30_000, -20_000, 12_345), usableForMix = true)
        val mixed = MulticastMixInputHeadroomFieldExperiment.mixPcmFrame(frame)
        assertNotSame(frame, mixed)
        val g = MulticastMixInputHeadroomFieldExperiment.linearGain()
        for (i in frame.samples.indices) {
            val expected =
                (frame.samples[i] * g).toInt().coerceIn(-32_768, 32_767).toShort()
            assertEquals(expected.toInt(), mixed.samples[i].toInt())
        }
    }

    @Test
    fun executeTimedMixCycle_whenOff_matchesDirectMixerPath() {
        val assembly = ConferenceMulticastRealMediaAssembly.createHarness()
        val pipeline = assembly.pipeline
        val hot = pcmTone(31_000)
        assembly.orchestrator.setDecodeSeam(
            OpusDecodeSeam { _, _, _, _ -> PcmFrame(hot, usableForMix = true) },
        )
        seedTopK(assembly)
        val off = pipeline.executeTimedMixCycle(nowMs = 1L, slot = 1L, slotMediaTimeMs = 1L)
        val direct = EqualWeightMixer.mix(listOf(PcmFrame(hot, usableForMix = true)))
        assertEquals(direct.peakAbsFs, off.mixCycle.mixedBlock.peakAbsFs, 0.001)
        assertTrue(direct.samples.contentEquals(off.mixCycle.mixedBlock.samples))
        val snap = off.mixCycle.sourceMixInputs["S1"]!!
        assertSame(hot, snap.samples)
        assertEquals(null, snap.mixerInputSamples)
    }

    @Test
    fun executeTimedMixCycle_whenOn_keepsInputSnapshotPreAtten_butLowersMixPeak() {
        MulticastMixInputHeadroomFieldExperiment.configureForFieldTest(
            enabled = true,
            gainDb = -6.0,
        )
        val assembly = ConferenceMulticastRealMediaAssembly.createHarness()
        val pipeline = assembly.pipeline
        val hot = pcmTone(31_000)
        assembly.orchestrator.setDecodeSeam(
            OpusDecodeSeam { _, _, _, _ -> PcmFrame(hot, usableForMix = true) },
        )
        seedTopK(assembly)
        val result = pipeline.executeTimedMixCycle(nowMs = 1L, slot = 1L, slotMediaTimeMs = 1L)
        val snap = result.mixCycle.sourceMixInputs["S1"]!!
        assertSame(hot, snap.samples)
        assertNotSame(hot, snap.mixerInputSamples)
        val mixerInput = snap.mixerInputSamples!!
        val g = MulticastMixInputHeadroomFieldExperiment.linearGain()
        for (i in hot.indices) {
            val expected = (hot[i] * g).toInt().coerceIn(-32_768, 32_767).toShort()
            assertEquals(expected.toInt(), mixerInput[i].toInt())
        }
        val attenuatedMix = EqualWeightMixer.mix(listOf(PcmFrame(mixerInput, usableForMix = true)))
        assertTrue(
            result.mixCycle.mixedBlock.samples.contentEquals(attenuatedMix.samples),
        )
        val unattenuatedMix = EqualWeightMixer.mix(listOf(PcmFrame(hot, usableForMix = true)))
        assertTrue(result.mixCycle.mixedBlock.peakAbsFs <= unattenuatedMix.peakAbsFs)
    }

    @Test
    fun a3InputTelemetry_whenExperimentOn_emitsPostAttenFields_notLoweringRealClipBaseline() {
        MulticastMixInputHeadroomFieldExperiment.configureForFieldTest(
            enabled = true,
            gainDb = -6.0,
        )
        val hot = pcmTone(31_000)
        val mixCycle =
            MixCycleResult(
                topKIdentities = setOf("S1"),
                decodeInvocationIdentities = setOf("S1"),
                mixParticipantIdentities = setOf("S1"),
                mixedBlock = EqualWeightMixer.mix(listOf(PcmFrame(hot, usableForMix = true))),
                sourcePullDispositions = mapOf("S1" to com.talkback.core.conference.runtime.SlotPullDisposition.DECODE_FRAME),
                sourceMixInputs =
                    mapOf(
                        "S1" to
                            SourceMixInputSnapshot(
                                kind = SourceMixInputKind.REAL,
                                samples = hot,
                                mixerInputSamples =
                                    MulticastMixInputHeadroomFieldExperiment.mixPcmFrame(
                                        PcmFrame(hot, usableForMix = true),
                                    ).samples,
                            ),
                    ),
            )
        Profile01A3MixInputTelemetry.testLogSink = { line -> lastInputLog = line }
        repeat(25) {
            Profile01A3MixInputTelemetry.recordCycle(
                sessionId = SESSION,
                owner = Profile01A3MixOutputTelemetry.OWNER_MULTICAST_PRODUCTION,
                mixCycle = mixCycle,
            )
        }
        val log = lastInputLog!!
        assertTrue(log.contains("experimentHeadroom=ACTIVE"))
        assertTrue(log.contains("postAttenClipSamples="))
        assertTrue(log.contains("realClipSamples="))
    }

    @Test
    fun startupFlag_missingFile_failClosedOff() {
        val dir = createTempDir()
        val flag = File(dir, MulticastMixInputHeadroomFieldExperiment.FIELD_FLAG_FILE_NAME)
        MulticastMixInputHeadroomFieldExperiment.applyStartupFromPrivateFlagFile(flag)
        assertEquals(false, MulticastMixInputHeadroomFieldExperiment.enabled)
        assertEquals("flag_file_missing", MulticastMixInputHeadroomFieldExperiment.lastStartupSource)
    }

    @Test
    fun startupFlag_illegalContent_failClosedOff() {
        val dir = createTempDir()
        val flag = File(dir, MulticastMixInputHeadroomFieldExperiment.FIELD_FLAG_FILE_NAME)
        flag.writeText("BANANA\n")
        MulticastMixInputHeadroomFieldExperiment.applyStartupFromPrivateFlagFile(flag)
        assertEquals(false, MulticastMixInputHeadroomFieldExperiment.enabled)
        assertEquals("flag_file_illegal", MulticastMixInputHeadroomFieldExperiment.lastStartupSource)
    }

    @Test
    fun startupFlag_onMinus6_enablesExperiment() {
        val dir = createTempDir()
        val flag = File(dir, MulticastMixInputHeadroomFieldExperiment.FIELD_FLAG_FILE_NAME)
        flag.writeText("ON,-6.0\n")
        MulticastMixInputHeadroomFieldExperiment.applyStartupFromPrivateFlagFile(flag)
        assertEquals(true, MulticastMixInputHeadroomFieldExperiment.enabled)
        assertEquals(-6.0, MulticastMixInputHeadroomFieldExperiment.gainDb, 0.001)
        assertEquals("flag_file_on", MulticastMixInputHeadroomFieldExperiment.lastStartupSource)
    }

    @Test
    fun startupFlag_off_explicit_disables() {
        val dir = createTempDir()
        val flag = File(dir, MulticastMixInputHeadroomFieldExperiment.FIELD_FLAG_FILE_NAME)
        flag.writeText("OFF\n")
        MulticastMixInputHeadroomFieldExperiment.applyStartupFromPrivateFlagFile(flag)
        assertEquals(false, MulticastMixInputHeadroomFieldExperiment.enabled)
        assertEquals("flag_file_off", MulticastMixInputHeadroomFieldExperiment.lastStartupSource)
    }

    private var lastInputLog: String? = null

    private fun seedTopK(assembly: ConferenceMulticastRealMediaAssembly) {
        val store = assembly.orchestrator.authority.store
        store.acceptVerifiedKey(
            MediaKeyContextFact(
                mediaKeyEpoch = 1L,
                masterKey = ByteArray(16) { 1 },
                masterSalt = ByteArray(14) { 2 },
                keyContextHint64 = ByteArray(8) { 3 },
            ),
        )
        store.acceptVerifiedSource(
            SourceAuthorizationFact(
                sourceIdentity = "S1",
                incarnationId = 1L,
                ssrc = 0x11223344,
                sourceAdmissionKey48 = ByteArray(48) { 4 },
                mediaKeyEpoch = 1L,
            ),
        )
        assembly.orchestrator.authority.syncAdmittedToRuntime("S1")
        assertTrue(
            assembly.orchestrator.observeVoice(
                VoiceLevelObservation(
                    sourceIdentity = "S1",
                    incarnationId = 1L,
                    voiceActive = true,
                    audioLevel = 40,
                ),
            ),
        )
    }

    private fun pcmTone(amplitude: Int): ShortArray {
        val n = OpusCodecConstants.FRAME_SAMPLES_20MS
        return ShortArray(n) { i ->
            val t = i.toDouble() / OpusCodecConstants.SAMPLE_RATE_HZ
            (sin(2.0 * PI * 440.0 * t) * amplitude).toInt().coerceIn(-32_767, 32_767).toShort()
        }
    }

    companion object {
        private const val SESSION = "headroom-field-experiment"
    }
}
