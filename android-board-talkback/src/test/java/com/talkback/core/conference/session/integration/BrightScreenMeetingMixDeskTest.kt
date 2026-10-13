package com.talkback.core.conference.session.integration

import com.talkback.core.conference.runtime.EqualWeightMixer
import com.talkback.core.conference.runtime.MediaMixConstants
import com.talkback.core.conference.runtime.PcmFrame
import kotlin.math.abs
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Bright-screen fix — mix input headroom reduces post-mix peak vs raw hot input. */
class BrightScreenMeetingMixDeskTest {
    @Before
    fun setUp() {
        BrightScreenMeetingMixPolicy.resetForTest()
        MulticastMixInputHeadroomFieldExperiment.resetForTest()
    }

    @After
    fun tearDown() {
        BrightScreenMeetingMixPolicy.resetForTest()
        MulticastMixInputHeadroomFieldExperiment.resetForTest()
    }

    @Test
    fun productionHeadroom_lowersMixedPeak_forHotSingleSource() {
        val hot = fullScaleTone()
        val rawMixed = EqualWeightMixer.mix(listOf(PcmFrame(hot, usableForMix = true)))
        val attenuated = MulticastMixInputAttenuation.applyForMix(PcmFrame(hot, usableForMix = true))
        val headroomMixed = EqualWeightMixer.mix(listOf(attenuated))
        assertTrue(
            "headroom peak ${headroomMixed.peakAbsFs} should be below raw ${rawMixed.peakAbsFs}",
            headroomMixed.peakAbsFs < rawMixed.peakAbsFs,
        )
        assertTrue(headroomMixed.peakAbsFs <= MediaMixConstants.OUTPUT_PEAK_ABS_FS + 1e-9)
    }

    @Test
    fun productionHeadroom_disabled_matchesBypass() {
        BrightScreenMeetingMixPolicy.productionMixInputHeadroomEnabled = false
        val hot = fullScaleTone()
        val frame = PcmFrame(hot, usableForMix = true)
        val out = MulticastMixInputAttenuation.applyForMix(frame)
        assertTrue(out.samples.contentEquals(hot))
    }

    @Test
    fun fieldExperiment_overridesProductionHeadroom() {
        MulticastMixInputHeadroomFieldExperiment.configureForFieldTest(enabled = true, gainDb = -12.0)
        val hot = fullScaleTone()
        val out = MulticastMixInputAttenuation.applyForMix(PcmFrame(hot, usableForMix = true))
        val peak = out.samples.maxOf { abs(it.toInt()) }
        val rawPeak = hot.maxOf { abs(it.toInt()) }
        assertTrue(peak < rawPeak / 2)
    }

    private fun fullScaleTone(): ShortArray {
        val n = 320
        return ShortArray(n) { i ->
            val t = i.toDouble() / n
            (32_000 * kotlin.math.sin(2 * kotlin.math.PI * t * 8)).toInt().toShort()
        }
    }
}
