package com.talkback.core.conference.session.integration

import com.talkback.core.conference.runtime.EqualWeightMixer
import com.talkback.core.conference.runtime.PcmFrame
import com.talkback.core.conference.runtime.OpusCodecConstants
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LQ-A1 desk A/B — fixed attenuation on multicast mix **inputs only** (offline).
 *
 * Telemetry sampling position (production, behavior-neutral):
 * - Input stats: [Profile01A3MixInputTelemetry.recordCycle] → [analyzePcm] on each
 *   [com.talkback.core.conference.runtime.SourceMixInputSnapshot.samples] taken from
 *   [com.talkback.core.conference.runtime.MixCycleResult.sourceMixInputs] **after decode**,
 *   **immediately before** [EqualWeightMixer.mix].
 * - Output stats: same mixed [com.talkback.core.conference.runtime.MixedBlock.samples] written
 *   toward AudioTrack (see [Profile01A3MixOutputTelemetry]).
 *
 * Not wired to production paths; does not change WebRTC PTT / TX / mixer algorithm.
 */
class Profile01A3MixInputHeadroomDeskExperimentTest {
    @Test
    fun telemetry_peakZeroDbfs_meansFullScaleSample_notAloneClipProof() {
        val hot = shortArrayOf(32_767, 1_000, -500)
        val stats = Profile01A3MixInputTelemetry.analyzePcm(hot)
        assertEquals(0.0, stats.peakDbfs!!, 0.05)
        assertEquals(1, stats.clipSamples)
        val warm = shortArrayOf(20_000, 1_000)
        val warmStats = Profile01A3MixInputTelemetry.analyzePcm(warm)
        assertTrue(warmStats.peakDbfs!! < -2.0)
        assertEquals(0, warmStats.clipSamples)
    }

    @Test
    fun ab_fixedAttenuation_onHotDecodePcm_reducesPreMixClip_andMixerCeilingHits() {
        val hotPcm = pcmTone(amplitude = 30_000)
        val sources =
            listOf(
                labeled("M01", hotPcm),
                labeled("M02", pcmTone(amplitude = 28_000, phase = 0.3)),
                labeled("M03", pcmTone(amplitude = 29_500, phase = 0.7)),
            )
        val baseline = evaluateMixWindow(sources, gainDb = 0.0)
        val attenuated = evaluateMixWindow(sources, gainDb = ATTENUATION_DB)
        assertTrue(
            "pre-mix clip should drop with attenuation: ${baseline.preMixClipSum} -> ${attenuated.preMixClipSum}",
            attenuated.preMixClipSum < baseline.preMixClipSum,
        )
        assertTrue(
            "mixer ceiling clipFrames should not increase: ${baseline.mixedClipFrames} -> ${attenuated.mixedClipFrames}",
            attenuated.mixedClipFrames <= baseline.mixedClipFrames,
        )
        assertTrue(attenuated.mixedPeakDbfs!! <= baseline.mixedPeakDbfs!! + 0.1)
    }

    @Test
    fun ab_attenuation_lowersLevel_butDoesNotUndoHardClippedWaveform() {
        val clean = pcmTone(amplitude = 31_000)
        val hardLimited =
            ShortArray(clean.size) { i ->
                clean[i].toInt().coerceIn(-8_000, 8_000).toShort()
            }
        val ref = applyGainDb(clean, ATTENUATION_DB)
        val attenuatedLimited = applyGainDb(hardLimited, ATTENUATION_DB)
        assertTrue(
            "attenuated limited PCM still diverges from attenuated clean reference",
            mseToReference(attenuatedLimited, ref) > 1.0,
        )
    }

    @Test
    fun ab_quietSpeechWindow_attenuationFurtherReducesRms_withoutInventingClip() {
        val quiet = pcmTone(amplitude = 3_000)
        val sources = listOf(labeled("M01", quiet), labeled("M02", pcmTone(amplitude = 2_500, phase = 0.2)))
        val baseline = evaluateMixWindow(sources, gainDb = 0.0)
        val attenuated = evaluateMixWindow(sources, gainDb = ATTENUATION_DB)
        assertTrue(attenuated.preMixRmsDbfs!! < baseline.preMixRmsDbfs!!)
        assertEquals(0, baseline.mixedClipFrames)
        assertEquals(0, attenuated.mixedClipFrames)
    }

    private data class SourcePcm(
        val id: String,
        val samples: ShortArray,
    )

    private data class MixWindowMetrics(
        val preMixClipSum: Int,
        val preMixRmsDbfs: Double?,
        val mixedClipFrames: Int,
        val mixedPeakDbfs: Double?,
        val mixedRmsDbfs: Double?,
    )

    private fun labeled(
        id: String,
        samples: ShortArray,
    ): SourcePcm = SourcePcm(id, samples)

    private fun evaluateMixWindow(
        sources: List<SourcePcm>,
        gainDb: Double,
    ): MixWindowMetrics {
        var clipSum = 0
        var sumSq = 0.0
        var sampleCount = 0
        val pcmFrames =
            sources.map { src ->
                val scaled = if (gainDb == 0.0) src.samples else applyGainDb(src.samples, gainDb)
                val stats = Profile01A3MixInputTelemetry.analyzePcm(scaled)
                clipSum += stats.clipSamples
                for (sample in scaled) {
                    val v = sample.toDouble()
                    sumSq += v * v
                    sampleCount++
                }
                PcmFrame(samples = scaled, usableForMix = true)
            }
        val mixed = EqualWeightMixer.mix(pcmFrames)
        val mixedStats = Profile01A3MixOutputTelemetry.analyzeMixedPcm(mixed.samples)
        val aggRms =
            if (sampleCount > 0) {
                Profile01A3MixInputTelemetry.linearToDbfs(kotlin.math.sqrt(sumSq / sampleCount))
            } else {
                null
            }
        return MixWindowMetrics(
            preMixClipSum = clipSum,
            preMixRmsDbfs = aggRms,
            mixedClipFrames = mixedStats.clipFrames,
            mixedPeakDbfs = mixedStats.peakDbfs,
            mixedRmsDbfs = mixedStats.rmsDbfs,
        )
    }

    private fun pcmTone(
        amplitude: Int,
        phase: Double = 0.0,
    ): ShortArray {
        val n = OpusCodecConstants.FRAME_SAMPLES_20MS
        return ShortArray(n) { i ->
            val t = (i.toDouble() / OpusCodecConstants.SAMPLE_RATE_HZ) + phase
            (sin(2.0 * PI * 440.0 * t) * amplitude).toInt().coerceIn(-32_767, 32_767).toShort()
        }
    }

    private fun applyGainDb(
        samples: ShortArray,
        gainDb: Double,
    ): ShortArray {
        val g = 10.0.pow(gainDb / 20.0)
        return ShortArray(samples.size) { i ->
            (samples[i] * g).toInt().coerceIn(-32_768, 32_767).toShort()
        }
    }

    private fun mseToReference(
        samples: ShortArray,
        reference: ShortArray,
    ): Double {
        val n = minOf(samples.size, reference.size)
        if (n == 0) return 0.0
        var sum = 0.0
        for (i in 0 until n) {
            val d = samples[i].toDouble() - reference[i].toDouble()
            sum += d * d
        }
        return sum / n
    }

    companion object {
        /** Desk experiment only — not a production target. */
        private const val ATTENUATION_DB = -6.0
    }
}
