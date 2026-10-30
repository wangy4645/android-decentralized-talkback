package com.talkback.core.conference.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConcentusOpusDecoderSeamTest {
    @Test
    fun realOpusRoundTrip_produces960SamplePcm() {
        val store = OpusPayloadStore()
        val seam = ConcentusOpusDecoderSeam(store)
        val opus = OpusTestVectors.encodeTone(440.0)
        store.put("S1", 1L, 100L, opus)

        val pcm = seam.decode("S1", 1L, 100L, nowMs = 0L)
        assertTrue(pcm != null)
        assertEquals(OpusCodecConstants.FRAME_SAMPLES_20MS, pcm!!.samples.size)
        assertTrue(pcm.usableForMix)
        assertEquals(1L, seam.decodeSuccessCount)
        assertTrue(seam.lastDecodeDurationUs >= 0L)
        var energy = 0L
        for (sample in pcm.samples) {
            energy += kotlin.math.abs(sample.toInt())
        }
        assertTrue(energy > 0L)
    }
}
