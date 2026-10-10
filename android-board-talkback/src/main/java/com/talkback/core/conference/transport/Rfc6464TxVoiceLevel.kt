package com.talkback.core.conference.transport

import kotlin.math.ln
import kotlin.math.sqrt

/**
 * RFC 6464 audio-level header extension for Profile01 shadow multicast TX.
 *
 * Lower 7 bits = negative dBov below digital overload (0 = loudest, 127 = quietest).
 * Bit 7 (V) = voice activity for this packet's measurement window.
 */
object Rfc6464TxVoiceLevel {
    const val FULL_SCALE_LINEAR = 32767.0
    const val SILENCE_LEVEL = 127
    const val MIN_ACTIVE_LEVEL = 0

    /**
     * Frame RMS → dBov below overload (0..127, higher = quieter).
     */
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

/**
 * Hangover on V only; level always reflects the current 20 ms PCM window.
 */
class TxVoiceActivityHangover(
    /** Frame is instantaneously active when [Rfc6464TxVoiceLevel.frameLevelDbov] <= this (louder). */
    private val activeWhenLevelAtOrBelow: Int = DEFAULT_ACTIVE_WHEN_LEVEL_AT_OR_BELOW_DBov,
    /** 20 ms frames to keep V=1 after last instantaneously active frame. */
    private val hangoverFrames: Int = 3,
) {
    companion object {
        /**
         * B1 field calibration (b1-s1s2.log, audible window 11:51:41–11:53:11):
         * near-talk INGRESS p90≈58 dBov; quiet V=false p10≈58, p50≈66. Threshold 59
         * bridges 56–58 weak speech without activating post-drop ambient (≤59 ~2.3%).
         */
        const val DEFAULT_ACTIVE_WHEN_LEVEL_AT_OR_BELOW_DBov: Int = 59
    }

    private var hangoverRemaining: Int = 0

    fun isInstantlyActiveFrameLevel(frameLevelDbov: Int): Boolean =
        frameLevelDbov <= activeWhenLevelAtOrBelow.coerceIn(
            Rfc6464TxVoiceLevel.MIN_ACTIVE_LEVEL,
            Rfc6464TxVoiceLevel.SILENCE_LEVEL,
        )

    fun observeFrameLevel(frameLevelDbov: Int): Boolean {
        val instant = isInstantlyActiveFrameLevel(frameLevelDbov)
        if (instant) {
            hangoverRemaining = hangoverFrames.coerceAtLeast(0)
            return true
        }
        if (hangoverRemaining > 0) {
            hangoverRemaining--
            return true
        }
        return false
    }

    fun reset() {
        hangoverRemaining = 0
    }
}
