package com.talkback.core.conference.runtime

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A3-Q3C desk. Current mixer contract, not a gain change.
 * One active speech plus two zero REAL frames is divided by contributor count.
 */
class Profile01A3Q3cMixConservationDeskTest {
    @Test
    fun singleContributorIsCopied() {
        val speech = ShortArray(8) { 8192 }
        val mixed = EqualWeightMixer.mix(listOf(PcmFrame(speech, usableForMix = true)))
        assertEquals(1, mixed.mixParticipantCount)
        assertTrue(mixed.samples.contentEquals(speech))
    }

    @Test
    fun threeContributorsAttenuateSpeechByOneThird() {
        val speech = ShortArray(8) { 8192 }
        val quiet = ShortArray(8) { 0 }
        val mixed =
            EqualWeightMixer.mix(
                listOf(
                    PcmFrame(speech, usableForMix = true),
                    PcmFrame(quiet, usableForMix = true),
                    PcmFrame(quiet, usableForMix = true),
                ),
            )
        assertEquals(3, mixed.mixParticipantCount)
        mixed.samples.forEach { sample ->
            assertEquals(8192 / 3, sample.toInt())
        }
        val ratio = (8192 / 3).toDouble() / 8192.0
        val dropDb = 20.0 * kotlin.math.ln(ratio) / kotlin.math.ln(10.0)
        assertTrue("dropDb=$dropDb", abs(dropDb - (-9.54)) < 0.15)
        assertTrue(mixed.peakAbsFs < MediaMixConstants.OUTPUT_PEAK_ABS_FS)
    }
}
