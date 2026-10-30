package com.talkback.core.conference.aqc

import com.talkback.core.conference.runtime.EqualWeightMixer
import com.talkback.core.conference.runtime.OpusCodecConstants
import com.talkback.core.conference.runtime.PcmFrame
import com.talkback.core.conference.runtime.SlotPullDisposition
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.abs

/**
 * Phase 2 desk gates: flag defaults, isolation semantics, fixed PCM vectors.
 * Does not claim product SLO PASS.
 */
class AqcPhase2DeskTest {
    @Before
    fun setUp() {
        AqcRuntime.resetToProductionDefaults()
    }

    @After
    fun tearDown() {
        AqcRuntime.resetToProductionDefaults()
    }

    @Test
    fun d0_defaultsAreOff() {
        assertFalse(AqcRuntime.txProcessingEnabled)
        assertFalse(AqcRuntime.membershipMixEnabled)
        assertFalse(AqcRuntime.continuousPlayoutEnabled)
    }

    @Test
    fun d1_txLimiterActsOnEncodeAuthorityFrame() {
        val hot = ShortArray(OpusCodecConstants.FRAME_SAMPLES_20MS) { 32_000 }
        val processed = TxProcessingChain.process20ms(hot, TxProcessingChain.TapPoint.POST_APM)
        assertEquals(TxProcessingChain.TapPoint.POST_APM, processed.metrics.tapPoint)
        assertTrue(processed.metrics.limiterApplied)
        assertTrue(processed.metrics.outputPeakAbs < processed.metrics.inputPeakAbs)
        assertTrue(processed.metrics.outputPeakAbs <= 29_500)
        assertEquals(OpusCodecConstants.FRAME_SAMPLES_20MS, processed.samples.size)
    }

    @Test
    fun d2_membershipDivisorRemovesActiveCountJump() {
        val full =
            PcmFrame(
                ShortArray(OpusCodecConstants.FRAME_SAMPLES_20MS) { 16_000 },
                usableForMix = true,
            )
        val equalOne = EqualWeightMixer.mix(listOf(full)).peakAbsFs
        val seatTwo = MembershipStableMixer.mix20ms(listOf(full), membershipDivisor = 2).peakAbsFs
        assertTrue(
            "seat divisor=2 must be quieter than equal-weight n=1: seat=$seatTwo equal=$equalOne",
            seatTwo < equalOne * 0.75,
        )
    }

    @Test
    fun d3_plcSynthesisIsBoundedAndDistinguishable() {
        val synth = PlayoutPcmSynthesizer()
        synth.rememberReal("M01", ShortArray(OpusCodecConstants.FRAME_SAMPLES_20MS) { 10_000 })
        val plc =
            synth.synthesizeForPull("M01", SlotPullDisposition.PLC_SYNTHESIS)
                ?: error("plc")
        assertEquals(OpusCodecConstants.FRAME_SAMPLES_20MS, plc.frame.samples.size)
        assertEquals(PlayoutPcmSynthesizer.OutputKind.PLC_ATTENUATED, plc.kind)
        assertTrue(abs(plc.frame.samples[0].toInt()) < 10_000)
    }

    @Test
    fun d4_continuousFallbackAlways960() {
        val fb = PlayoutPcmSynthesizer().continuousFallbackFrame()
        assertEquals(OpusCodecConstants.FRAME_SAMPLES_20MS, fb.frame.samples.size)
        assertEquals(PlayoutPcmSynthesizer.OutputKind.COMFORT_NOISE, fb.kind)
    }

    @Test
    fun d5_fixedInput_oneVsTwoSeatAmplitude() {
        val tone =
            PcmFrame(
                ShortArray(OpusCodecConstants.FRAME_SAMPLES_20MS) { i ->
                    if (i % 2 == 0) 12_000 else (-12_000).toShort()
                },
                usableForMix = true,
            )
        val oneSeat = MembershipStableMixer.mix20ms(listOf(tone), membershipDivisor = 1)
        val twoSeat = MembershipStableMixer.mix20ms(listOf(tone), membershipDivisor = 2)
        assertTrue(twoSeat.peakAbsFs < oneSeat.peakAbsFs * 0.6)
    }

    @Test
    fun configureForDesk_enablesAllThree() {
        AqcRuntime.configureForDesk()
        assertTrue(AqcRuntime.txProcessingEnabled)
        assertTrue(AqcRuntime.membershipMixEnabled)
        assertTrue(AqcRuntime.continuousPlayoutEnabled)
    }

    @Test
    fun optionalDebugProps_unset_leavesDefaults() {
        AqcRuntime.applyOptionalDebugSystemProperties()
        // JVM unit test has no Android SystemProperties → no-op, stay off.
        assertFalse(AqcRuntime.txProcessingEnabled)
        assertFalse(AqcRuntime.membershipMixEnabled)
        assertFalse(AqcRuntime.continuousPlayoutEnabled)
    }
}
