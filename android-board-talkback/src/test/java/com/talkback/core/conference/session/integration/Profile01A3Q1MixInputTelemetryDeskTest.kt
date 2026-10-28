package com.talkback.core.conference.session.integration

import com.talkback.core.conference.runtime.EqualWeightMixer
import com.talkback.core.conference.runtime.MediaMixConstants
import com.talkback.core.conference.runtime.MixCycleResult
import com.talkback.core.conference.runtime.MixedBlock
import com.talkback.core.conference.runtime.SourceMixInputKind
import com.talkback.core.conference.runtime.SourceMixInputSnapshot
import com.talkback.core.conference.runtime.OpusCodecConstants
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * A3-Q1 — mix-input PCM amplitude telemetry fidelity (desk only).
 */
class Profile01A3Q1MixInputTelemetryDeskTest {
    private val telemetryLines = mutableListOf<String>()

    @Before
    fun setUp() {
        telemetryLines.clear()
        Profile01A3MixInputTelemetry.resetForTest()
        Profile01A3MixInputTelemetry.testLogSink = { telemetryLines += it }
    }

    @After
    fun tearDown() {
        Profile01A3MixInputTelemetry.resetForTest()
    }

    @Test
    fun analyzePcm_knownTone_rmsAndPeakWithinTolerance() {
        val tone = toneSamples(amplitude = 8_000, frequencyHz = 440.0)
        val stats = Profile01A3MixInputTelemetry.analyzePcm(tone)
        assertEquals(tone.size, stats.frames)
        assertTrue(stats.rmsDbfs!! in -20.0..-10.0)
        assertTrue(stats.peakDbfs!! in -14.0..-8.0)
        assertEquals(0, stats.clipSamples)
    }

    @Test
    fun recordCycle_realAndPlcBucketsStaySeparate() {
        val realTone = toneSamples(amplitude = 8_000)
        val plcNoise = toneSamples(amplitude = 20_000, frequencyHz = 2_000.0)
        repeat(WINDOW_CYCLES) {
            Profile01A3MixInputTelemetry.recordCycle(
                sessionId = SESSION,
                owner = Profile01A3MixInputTelemetry.OWNER_MULTICAST_PRODUCTION,
                mixCycle =
                    MixCycleResult(
                        topKIdentities = setOf("M01", "M03"),
                        decodeInvocationIdentities = setOf("M01"),
                        mixParticipantIdentities = setOf("M01"),
                        mixedBlock = MixedBlock(realTone, mixParticipantCount = 1, peakAbsFs = 0.5),
                        sourceMixInputs =
                            mapOf(
                                "M01" to
                                    SourceMixInputSnapshot(
                                        kind = SourceMixInputKind.REAL,
                                        samples = realTone,
                                    ),
                                "M03" to
                                    SourceMixInputSnapshot(
                                        kind = SourceMixInputKind.PLC,
                                        samples = plcNoise,
                                    ),
                            ),
                    ),
            )
        }
        val summary = latestSummary()
        assertTrue(summary.m01RealFrames > 0L)
        assertTrue(summary.m03PlcFrames > 0L)
        assertTrue(summary.m01RealRms in -20.0..-10.0)
        assertTrue(summary.m03PlcPeak > summary.m01RealPeak + 6.0)
        assertEquals(0, summary.m01PlcFrames)
        assertEquals(0, summary.m03RealFrames)
    }

    @Test
    fun recordCycle_mixedStatsMatchEqualWeightMixerOutput() {
        val m01 = toneSamples(amplitude = 6_000)
        val m03 = toneSamples(amplitude = 6_000, frequencyHz = 880.0)
        val mixed =
            EqualWeightMixer.mix(
                listOf(
                    com.talkback.core.conference.runtime.PcmFrame(m01, usableForMix = true),
                    com.talkback.core.conference.runtime.PcmFrame(m03, usableForMix = true),
                ),
            )
        val expected = Profile01A3MixOutputTelemetry.analyzeMixedPcm(mixed.samples)
        repeat(WINDOW_CYCLES) {
            Profile01A3MixInputTelemetry.recordCycle(
                sessionId = SESSION,
                owner = Profile01A3MixInputTelemetry.OWNER_MULTICAST_PRODUCTION,
                mixCycle =
                    MixCycleResult(
                        topKIdentities = setOf("M01", "M03"),
                        decodeInvocationIdentities = setOf("M01", "M03"),
                        mixParticipantIdentities = setOf("M01", "M03"),
                        mixedBlock = mixed,
                        sourceMixInputs =
                            mapOf(
                                "M01" to
                                    SourceMixInputSnapshot(SourceMixInputKind.REAL, samples = m01),
                                "M03" to
                                    SourceMixInputSnapshot(SourceMixInputKind.REAL, samples = m03),
                            ),
                    ),
            )
        }
        val summary = latestSummary()
        assertEquals(expected.outputFrames.toLong() * WINDOW_CYCLES, summary.mixedFrames)
        assertEquals(
            expected.peakDbfs!!,
            summary.mixedPeak,
            0.5,
        )
        assertEquals(
            expected.rmsDbfs!!,
            summary.mixedRms,
            1.0,
        )
    }

    private fun toneSamples(
        amplitude: Short,
        frequencyHz: Double = 440.0,
    ): ShortArray {
        val pcm = ShortArray(OpusCodecConstants.FRAME_SAMPLES_20MS)
        for (i in pcm.indices) {
            val t = i.toDouble() / OpusCodecConstants.SAMPLE_RATE_HZ
            pcm[i] = (amplitude * sin(2.0 * PI * frequencyHz * t)).toInt().toShort()
        }
        return pcm
    }

    private data class ParsedSummary(
        val sourceInput: String,
        val mixedFrames: Long,
        val mixedRms: Double,
        val mixedPeak: Double,
        val m01RealFrames: Long,
        val m01PlcFrames: Long,
        val m01RealPeak: Double,
        val m01RealRms: Double,
        val m03RealFrames: Long,
        val m03PlcFrames: Long,
        val m03PlcPeak: Double,
    )

    private fun parseLogFields(line: String): Map<String, String> {
        val body = line.removePrefix(Profile01A3MixInputTelemetry.PHASE).trim()
        val keys =
            listOf(
                "owner",
                "cycles",
                "sourceInput",
                "mixedFrames",
                "mixedRmsDbfs",
                "mixedPeakDbfs",
                "mixedClipSamples",
                "mixedSilenceFrames",
            )
        val fields = linkedMapOf<String, String>()
        var rest = body
        for (i in keys.indices) {
            val key = keys[i]
            val prefix = "$key="
            val start = rest.indexOf(prefix)
            if (start < 0) continue
            rest = rest.substring(start + prefix.length)
            val nextPrefixes =
                keys.drop(i + 1).map { next -> " $next=" }
            val end =
                nextPrefixes
                    .map { rest.indexOf(it) }
                    .filter { it >= 0 }
                    .minOrNull()
                    ?: rest.length
            fields[key] = rest.substring(0, end)
        }
        return fields
    }

    private fun sourceBlock(
        fields: Map<String, String>,
        id: String,
    ): Map<String, String> {
        val block =
            fields["sourceInput"]
                ?.split('|')
                ?.firstOrNull { it.startsWith("$id:") }
                ?: error("missing source $id")
        return block.substringAfter(':')
            .split(',')
            .associate { part ->
                val eq = part.indexOf('=')
                part.substring(0, eq) to part.substring(eq + 1)
            }
    }

    private fun latestSummary(): ParsedSummary {
        Profile01A3MixInputTelemetry.flushForTest(SESSION)
        val line =
            telemetryLines.lastOrNull { it.startsWith(Profile01A3MixInputTelemetry.PHASE) }
                ?: error("missing ${Profile01A3MixInputTelemetry.PHASE}")
        val fields = parseLogFields(line)
        val m01 = sourceBlock(fields, "M01")
        val m03 = sourceBlock(fields, "M03")
        return ParsedSummary(
            sourceInput = fields["sourceInput"] ?: error("missing sourceInput"),
            mixedFrames = fields["mixedFrames"]!!.toLong(),
            mixedRms = fields["mixedRmsDbfs"]!!.toDouble(),
            mixedPeak = fields["mixedPeakDbfs"]!!.toDouble(),
            m01RealFrames = m01["realFrames"]!!.toLong(),
            m01PlcFrames = m01["plcFrames"]!!.toLong(),
            m01RealPeak = m01["realPeakDbfs"]!!.toDouble(),
            m01RealRms = m01["realRmsDbfs"]!!.toDouble(),
            m03RealFrames = m03["realFrames"]!!.toLong(),
            m03PlcFrames = m03["plcFrames"]!!.toLong(),
            m03PlcPeak =
                m03["plcPeakDbfs"]!!.let { if (it == "na") Double.NEGATIVE_INFINITY else it.toDouble() },
        )
    }

    companion object {
        private const val SESSION = "a3q1-mix-input"
        private const val WINDOW_CYCLES = 25
    }
}
