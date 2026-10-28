package com.talkback.core.conference.session.integration

import com.talkback.core.conference.runtime.OpusCodecConstants
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

internal object Profile01G5VaPcmVectors {
    private const val SAMPLE_RATE_HZ = 48_000

    fun speechPcm960(
        targetDbfs: Double = -26.0,
        frequencyHz: Double = 440.0,
    ): ShortArray {
        val peak = (32_767.0 * 10.0.pow(targetDbfs / 20.0)).roundToInt().coerceAtLeast(512)
        return ShortArray(OpusCodecConstants.FRAME_SAMPLES_20MS) { index ->
            (peak * sin(2.0 * PI * frequencyHz * index / SAMPLE_RATE_HZ)).roundToInt()
                .toShort()
        }
    }

    fun digitalSilencePcm960(): ShortArray =
        ShortArray(OpusCodecConstants.FRAME_SAMPLES_20MS) { 0 }

    /** Low-level broadband noise proxy for desk FP sensitivity checks. */
    fun lowLevelNoisePcm960(
        targetDbfs: Double = -34.0,
        seed: Int = 0x5A5A,
    ): ShortArray {
        val peak = (32_767.0 * 10.0.pow(targetDbfs / 20.0)).roundToInt().coerceAtLeast(64)
        var state = seed
        return ShortArray(OpusCodecConstants.FRAME_SAMPLES_20MS) { index ->
            state = (state * 1_103_515_245 + 12_345) and 0x7FFF
            val noise = (state % (peak * 2 + 1)) - peak
            val tone = (peak / 4) * sin(2.0 * PI * 180.0 * index / SAMPLE_RATE_HZ)
            (noise + tone).roundToInt().coerceIn(-peak, peak).toShort()
        }
    }

    fun repeatFrames(
        frame: ShortArray,
        count: Int,
    ): List<ShortArray> = List(count) { frame.copyOf() }
}
