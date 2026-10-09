package com.talkback.core.conference.session.integration

import com.talkback.core.conference.runtime.ConcentusOpusDecoderSeam
import com.talkback.core.conference.runtime.OpusPayloadStore
import com.talkback.core.conference.runtime.OpusTestVectors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * A3-Q2 — encoded → decoded PCM: Opus decoder state must stay per-source.
 */
class Profile01A3Q2DecoderSourceIsolationDeskTest {
    @Test
    fun twoSources_eachGetsIndependentDecoderState() {
        val store = OpusPayloadStore()
        val seam = ConcentusOpusDecoderSeam(store)
        val opusM01 = OpusTestVectors.encodeTone(440.0)
        val opusM03 = OpusTestVectors.encodeTone(880.0)
        seam.onPoolAllocated("M01", 1L)
        seam.onPoolAllocated("M03", 1L)
        store.put("M01", 1L, 0L, opusM01)
        store.put("M03", 1L, 0L, opusM03)

        seam.decode("M01", 1L, 0L, nowMs = 0L)
        seam.decode("M03", 1L, 0L, nowMs = 0L)

        assertEquals(2, seam.physicalDecoderCount)
    }

    @Test
    fun alternatingMultiSourceDecode_m03PeakStaysNearSoloBaseline() {
        val opusM01 = OpusTestVectors.encodeTone(440.0)
        val opusM03 = OpusTestVectors.encodeTone(880.0)

        val soloStore = OpusPayloadStore()
        val soloSeam = ConcentusOpusDecoderSeam(soloStore)
        soloSeam.onPoolAllocated("M03", 1L)
        for (slot in 0 until FRAMES) {
            soloStore.put("M03", 1L, slot.toLong(), opusM03)
        }
        val soloPeaks =
            (0 until FRAMES).map { slot ->
                peakAbs(soloSeam.decode("M03", 1L, slot.toLong(), nowMs = 0L)!!.samples)
            }
        val soloMax = soloPeaks.maxOrNull()!!

        val altStore = OpusPayloadStore()
        val altSeam = ConcentusOpusDecoderSeam(altStore)
        altSeam.onPoolAllocated("M01", 1L)
        altSeam.onPoolAllocated("M03", 1L)
        for (slot in 0 until FRAMES) {
            altStore.put("M01", 1L, slot.toLong(), opusM01)
            altStore.put("M03", 1L, slot.toLong(), opusM03)
        }
        val interleavedPeaks = mutableListOf<Int>()
        for (slot in 0 until FRAMES) {
            altSeam.decode("M01", 1L, slot.toLong(), nowMs = 0L)
            val pcm = altSeam.decode("M03", 1L, slot.toLong(), nowMs = 0L)!!
            interleavedPeaks += peakAbs(pcm.samples)
        }
        val interleavedMax = interleavedPeaks.maxOrNull()!!

        assertTrue(
            "M03 peak after M01 decode must not explode (solo=$soloMax interleaved=$interleavedMax)",
            interleavedMax <= soloMax * 2 + PEAK_TOLERANCE,
        )
    }

    @Test
    fun incarnationSupersede_replacesDecoderStateWithoutCrossTalk() {
        val store = OpusPayloadStore()
        val seam = ConcentusOpusDecoderSeam(store)
        val opus = OpusTestVectors.encodeTone(440.0)
        seam.onPoolAllocated("M04", 1L)
        store.put("M04", 1L, 0L, opus)
        store.put("M04", 2L, 0L, opus)

        seam.decode("M04", 1L, 0L, nowMs = 0L)
        assertEquals(1, seam.physicalDecoderCount)

        seam.onPoolAllocated("M04", 2L)
        seam.decode("M04", 2L, 0L, nowMs = 0L)
        assertEquals(1, seam.physicalDecoderCount)
    }

    private fun peakAbs(samples: ShortArray): Int = samples.maxOf { abs(it.toInt()) }

    private companion object {
        const val FRAMES = 8
        const val PEAK_TOLERANCE = 500
    }
}
