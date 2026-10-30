package com.talkback.core.conference.session.integration

import com.talkback.core.conference.runtime.EqualWeightMixer
import com.talkback.core.conference.runtime.MediaMixConstants
import com.talkback.core.conference.runtime.PcmFrame
import kotlin.math.abs
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MeetingProductionMixCombineDeskTest {
    @Before
    fun setUp() {
        BrightScreenMeetingMixPolicy.resetForTest()
        MixOutputMakeupFieldExperiment.resetForTest()
        MeetingProductionMixer.clearSession("desk")
    }

    @After
    fun tearDown() {
        BrightScreenMeetingMixPolicy.resetForTest()
        MixOutputMakeupFieldExperiment.resetForTest()
        MeetingProductionMixer.clearSession("desk")
    }

    @Test
    fun sumMode_louderThanAverage_forThreeEqualTones() {
        val frames = listOf(tone(), tone(), tone())
        BrightScreenMeetingMixPolicy.productionMixCombineMode =
            BrightScreenMeetingMixPolicy.MixCombineMode.EQUAL_WEIGHT_AVERAGE
        val avg = MeetingProductionMixer.mix("desk", frames)
        BrightScreenMeetingMixPolicy.productionMixCombineMode =
            BrightScreenMeetingMixPolicy.MixCombineMode.LINEAR_SUM_SOFT_LIMIT
        MeetingProductionMixer.clearSession("desk")
        val sum = MeetingProductionMixer.mix("desk", frames)
        val avgPeak = avg.samples.maxOf { abs(it.toInt()) }
        val sumPeak = sum.samples.maxOf { abs(it.toInt()) }
        assertTrue("sum peak $sumPeak vs avg $avgPeak", sumPeak > avgPeak * 2)
        assertTrue(sum.peakAbsFs <= MediaMixConstants.OUTPUT_PEAK_ABS_FS + 1e-9)
        assertTrue(avg.peakAbsFs <= MediaMixConstants.OUTPUT_PEAK_ABS_FS + 1e-9)
    }

    @Test
    fun averageMode_matchesEqualWeightMixer() {
        val frames = listOf(tone(12_000), tone(8_000))
        BrightScreenMeetingMixPolicy.productionMixCombineMode =
            BrightScreenMeetingMixPolicy.MixCombineMode.EQUAL_WEIGHT_AVERAGE
        val viaProduction = MeetingProductionMixer.mix("desk", frames)
        val direct = EqualWeightMixer.mix(frames)
        assertTrue(viaProduction.samples.contentEquals(direct.samples))
    }

    @Test
    fun activeSpeechMode_louderThanAverage_whenOnlyOneSourceIsSpeech() {
        val hot = tone(12_000)
        val quiet = PcmFrame(ShortArray(320) { 0 }, true)
        val frames = listOf(hot, quiet, quiet)
        BrightScreenMeetingMixPolicy.productionMixCombineMode =
            BrightScreenMeetingMixPolicy.MixCombineMode.EQUAL_WEIGHT_AVERAGE
        val avg = MeetingProductionMixer.mix("desk", frames)
        BrightScreenMeetingMixPolicy.productionMixCombineMode =
            BrightScreenMeetingMixPolicy.MixCombineMode.ACTIVE_SPEECH_EQUAL_WEIGHT
        val active = MeetingProductionMixer.mix("desk2", frames)
        val avgPeak = avg.samples.maxOf { abs(it.toInt()) }
        val activePeak = active.samples.maxOf { abs(it.toInt()) }
        assertTrue("active $activePeak vs avg $avgPeak", activePeak > avgPeak * 2)
        assertTrue(active.peakAbsFs <= MediaMixConstants.OUTPUT_PEAK_ABS_FS + 1e-9)
    }

    @Test
    fun productionDefault_isActiveSpeechEqualWeight_andIgnoresSilentMembers() {
        // resetForTest() restores the production default; three silent members must not inflate the divisor.
        val hot = tone(12_000)
        val silent = PcmFrame(ShortArray(320) { 0 }, true)
        val block = MeetingProductionMixer.mix("desk", listOf(hot, silent, silent, silent))
        val diag = block.blockDiagnostics!!
        assertTrue(diag.combineMode == "ACTIVE_SPEECH_EQUAL_WEIGHT")
        assertTrue("divisor=${diag.mixBlendDivisor}", diag.mixBlendDivisor == 1)
        assertTrue(diag.mixActiveSpeechCount == 1)
        val hotPeak = hot.samples.maxOf { abs(it.toInt()) }
        val outPeak = block.samples.maxOf { abs(it.toInt()) }
        assertTrue("out $outPeak vs in $hotPeak", abs(outPeak - hotPeak) <= 1)
    }

    @Test
    fun productionDefault_fourLoudSpeakers_staysUnderPeakCap_noClipping() {
        val loud = ShortArray(320) { 30_000 }
        val frames = List(4) { PcmFrame(loud, true) }
        val block = MeetingProductionMixer.mix("desk", frames)
        val diag = block.blockDiagnostics!!
        assertTrue(diag.mixBlendDivisor == 4)
        assertTrue(block.peakAbsFs <= MediaMixConstants.OUTPUT_PEAK_ABS_FS + 1e-9)
        assertTrue(block.samples.none { it == Short.MAX_VALUE || it == Short.MIN_VALUE })
    }

    @Test
    fun outputMakeup_increasesAvgLevel_respectsPeakCap() {
        val frames = listOf(tone(), tone(), tone())
        BrightScreenMeetingMixPolicy.productionMixCombineMode =
            BrightScreenMeetingMixPolicy.MixCombineMode.EQUAL_WEIGHT_AVERAGE
        val baseline = MeetingProductionMixer.mix("desk", frames)
        MixOutputMakeupFieldExperiment.configureForFieldTest(enabled = true, gainDb = 6.0)
        val boosted = MeetingProductionMixer.mix("desk2", frames)
        val basePeak = baseline.samples.maxOf { abs(it.toInt()) }
        val boostPeak = boosted.samples.maxOf { abs(it.toInt()) }
        assertTrue("boost $boostPeak vs base $basePeak", boostPeak > basePeak)
        assertTrue(boosted.peakAbsFs <= MediaMixConstants.OUTPUT_PEAK_ABS_FS + 1e-9)
        assertTrue(boosted.blockDiagnostics!!.mixOutputMakeupGainDb == 6.0)
    }

    @Test
    fun sumMode_reportsDiagnosticsWhenLimiterEngages() {
        val hot = ShortArray(320) { 28_000 }
        val frames = listOf(PcmFrame(hot, true), PcmFrame(hot, true), PcmFrame(hot, true))
        BrightScreenMeetingMixPolicy.productionMixCombineMode =
            BrightScreenMeetingMixPolicy.MixCombineMode.LINEAR_SUM_SOFT_LIMIT
        val block = MeetingProductionMixer.mix("desk", frames)
        val diag = block.blockDiagnostics
        assertTrue(diag != null)
        assertTrue(diag!!.combineMode.contains("LINEAR_SUM"))
        assertTrue(diag.preLimitPeakAbsFs > MediaMixConstants.OUTPUT_PEAK_ABS_FS)
    }

    private fun tone(amplitude: Int = 10_000): PcmFrame {
        val n = 320
        val samples =
            ShortArray(n) { i ->
                val t = i.toDouble() / n
                (amplitude * kotlin.math.sin(2 * kotlin.math.PI * t * 6)).toInt().toShort()
            }
        return PcmFrame(samples, usableForMix = true)
    }
}
