package com.talkback.core.conference.aqc

import kotlin.random.Random

/** Low-level comfort noise for continuous playout ticks (not cryptographic). */
object AqcComfortNoise {
    private const val COMFORT_RMS_TARGET = 180

    fun frame20ms(seed: Int = 0): ShortArray {
        val rng = Random(seed xor System.nanoTime().toInt())
        return ShortArray(AqcFrameConstants.FRAME_SAMPLES_20MS) {
            (rng.nextInt(-COMFORT_RMS_TARGET, COMFORT_RMS_TARGET + 1)).toShort()
        }
    }
}
