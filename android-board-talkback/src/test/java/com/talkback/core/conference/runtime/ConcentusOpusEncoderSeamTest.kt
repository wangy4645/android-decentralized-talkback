package com.talkback.core.conference.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class ConcentusOpusEncoderSeamTest {
    @Test
    fun consecutiveFrames_reuseEncoder_andDifferFromPerFrameNew() {
        val pcm0 = phaseTone(0)
        val pcm1 = phaseTone(OpusCodecConstants.FRAME_SAMPLES_20MS)
        val persistent = ConcentusOpusEncoderSeam()
        val p0 = persistent.encode(pcm0, sourceGeneration = 1L)
        val p1 = persistent.encode(pcm1, sourceGeneration = 1L)
        assertEquals(1L, persistent.boundGenerationForHarness)
        val stateless0 = OpusTestVectors.encodePcm(pcm0)
        val stateless1 = OpusTestVectors.encodePcm(pcm1)
        assertTrue(p0.contentEquals(stateless0))
        assertFalse(p1.contentEquals(stateless1))
        assertNotEquals(p0.size + p1.size, 0)
        persistent.release()
        assertEquals(null, persistent.boundGenerationForHarness)
    }

    @Test
    fun sourceGenerationChange_recreatesEncoder() {
        val pcm = phaseTone(0)
        val seam = ConcentusOpusEncoderSeam()
        val first = seam.encode(pcm, sourceGeneration = 1L)
        seam.encode(phaseTone(OpusCodecConstants.FRAME_SAMPLES_20MS), sourceGeneration = 1L)
        val afterRotate = seam.encode(pcm, sourceGeneration = 2L)
        assertEquals(2L, seam.boundGenerationForHarness)
        assertTrue(first.contentEquals(afterRotate))
        seam.release()
    }

    @Test
    fun persistentEncode_roundTripsThroughPersistentDecoder() {
        val store = OpusPayloadStore()
        val decoder = ConcentusOpusDecoderSeam(store)
        val encoder = ConcentusOpusEncoderSeam()
        val pcm0 = phaseTone(0)
        val pcm1 = phaseTone(OpusCodecConstants.FRAME_SAMPLES_20MS)
        store.put("S1", 1L, 0L, encoder.encode(pcm0, 1L))
        store.put("S1", 1L, 1L, encoder.encode(pcm1, 1L))
        val d0 = decoder.decode("S1", 1L, 0L, nowMs = 0L)
        val d1 = decoder.decode("S1", 1L, 1L, nowMs = 20L)
        assertTrue(d0 != null && d1 != null)
        assertTrue(energy(d0!!.samples) > 0L)
        assertTrue(energy(d1!!.samples) > 0L)
        encoder.release()
    }

    private fun phaseTone(startSample: Int): ShortArray {
        val pcm = ShortArray(OpusCodecConstants.FRAME_SAMPLES_20MS)
        val freq = 440.0
        val amp = 8_000
        val rate = OpusCodecConstants.SAMPLE_RATE_HZ.toDouble()
        for (i in pcm.indices) {
            val t = (startSample + i).toDouble() / rate
            pcm[i] = (sin(2.0 * PI * freq * t) * amp).toInt().toShort()
        }
        return pcm
    }

    private fun energy(samples: ShortArray): Long {
        var e = 0L
        for (s in samples) e += abs(s.toInt())
        return e
    }
}
