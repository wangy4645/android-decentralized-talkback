package com.talkback.core.conference.aqc

import com.talkback.core.conference.runtime.OpusCodecConstants
import com.talkback.core.conference.runtime.PcmFrame
import com.talkback.core.conference.runtime.SlotPullDisposition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AqcModuleDeskTest {
    @Test
    fun txProcessing_limitsHotFrame() {
        val hot = ShortArray(OpusCodecConstants.FRAME_SAMPLES_20MS) { 32_000 }
        val out = TxProcessingChain.process20ms(hot)
        assertTrue(out.metrics.limiterApplied)
        // Ceiling ≈ 0.89 FS ≈ clip threshold; peak must drop, clip count may stay high at the rail.
        assertTrue(out.metrics.outputPeakAbs < out.metrics.inputPeakAbs)
        assertTrue(out.metrics.outputPeakAbs <= 29_500)
    }

    @Test
    fun membershipMixer_usesSeatDivisorNotActiveCount() {
        val one =
            PcmFrame(
                ShortArray(OpusCodecConstants.FRAME_SAMPLES_20MS) { 16_000 },
                usableForMix = true,
            )
        val equalWeightPeak =
            com.talkback.core.conference.runtime.EqualWeightMixer.mix(listOf(one)).peakAbsFs
        val stable =
            MembershipStableMixer.mix20ms(listOf(one), membershipDivisor = 2).peakAbsFs
        assertTrue(stable < equalWeightPeak)
    }

    @Test
    fun playoutSynthesizer_plcProducesFullFrame() {
        val synth = PlayoutPcmSynthesizer()
        val real = ShortArray(OpusCodecConstants.FRAME_SAMPLES_20MS) { 8_000 }
        synth.rememberReal("M01", real)
        val frame =
            synth.synthesizeForPull("M01", SlotPullDisposition.PLC_SYNTHESIS)
                ?: error("expected plc frame")
        assertEquals(OpusCodecConstants.FRAME_SAMPLES_20MS, frame.frame.samples.size)
        assertEquals(PlayoutPcmSynthesizer.OutputKind.PLC_ATTENUATED, frame.kind)
    }

    @Test
    fun continuousFallback_is960Samples() {
        val frame = PlayoutPcmSynthesizer().continuousFallbackFrame()
        assertEquals(OpusCodecConstants.FRAME_SAMPLES_20MS, frame.frame.samples.size)
    }
}
