package com.talkback.core.conference.transport

import kotlin.math.ln
import kotlin.math.sqrt

/**
 * RFC 6464 audio-level semantics for PCM frame measurement (B1 observe / future wire).
 *
 * Lower 7 bits = negative dBov below digital overload (0 = loudest, 127 = quietest).
 */
object Rfc6464TxVoiceLevel {
    const val FULL_SCALE_LINEAR = 32767.0
    const val SILENCE_LEVEL = 127
    const val MIN_ACTIVE_LEVEL = 0

    /** Frame RMS → dBov below overload (0..127, higher = quieter). */
    fun frameLevelDbov(samples: ShortArray): Int {
        if (samples.isEmpty()) return SILENCE_LEVEL
        var sumSq = 0.0
        for (sample in samples) {
            val v = sample.toDouble()
            sumSq += v * v
        }
        val rms = sqrt(sumSq / samples.size)
        if (rms <= 1.0) return SILENCE_LEVEL
        val dbov = -20.0 * ln(rms / FULL_SCALE_LINEAR) / ln(10.0)
        return dbov.coerceIn(0.0, 127.0).toInt()
    }

    fun toWireByte(
        voiceActive: Boolean,
        frameLevelDbov: Int,
    ): Int {
        val level = frameLevelDbov.coerceIn(0, 127)
        return if (voiceActive) {
            0x80 or level
        } else {
            level and 0x7F
        }
    }

    fun parseWireByte(octet: Int): Pair<Boolean, Int> {
        val v = (octet and 0x80) != 0
        val level = octet and 0x7F
        return v to level
    }
}
