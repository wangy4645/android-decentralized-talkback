package com.talkback.core.conference.session.integration

import com.talkback.core.conference.runtime.MediaMixConstants
import com.talkback.core.conference.runtime.MixedBlock

/**
 * Keeps AudioTrack on a 20 ms deadline: write the latest unique mix without waiting
 * for this tick's decode. On stall, fade out the last unique frame once, then empty
 * (D1 fills digital silence). Never repeats the previous frame's raw PCM.
 */
internal class ProductionPlayoutDeadlinePump {
    @Volatile
    private var pending: MixedBlock? = null
    private var lastUniquePublished: MixedBlock? = null
    private var lastUniqueSamples: ShortArray? = null
    private var lastUniqueStillLive: Boolean = false
    private var recoverWithFadeIn: Boolean = false

    fun publishPrepared(block: MixedBlock) {
        if (block.samples.isNotEmpty()) {
            pending = block
        }
    }

    fun blockForDeadlineWrite(): MixedBlock {
        val prepared = pending
        if (prepared != null && prepared.samples.isNotEmpty() && prepared !== lastUniquePublished) {
            lastUniquePublished = prepared
            lastUniqueSamples = prepared.samples
            lastUniqueStillLive = true
            return if (recoverWithFadeIn) {
                recoverWithFadeIn = false
                MixedBlock(
                    samples = fadeIn(prepared.samples),
                    mixParticipantCount = prepared.mixParticipantCount,
                    peakAbsFs = prepared.peakAbsFs,
                    blockDiagnostics = prepared.blockDiagnostics,
                )
            } else {
                prepared
            }
        }
        return emitFadeOutOrEmpty()
    }

    private fun emitFadeOutOrEmpty(): MixedBlock {
        val src = lastUniqueSamples
        if (lastUniqueStillLive && src != null && src.isNotEmpty()) {
            lastUniqueStillLive = false
            recoverWithFadeIn = true
            return MixedBlock(
                samples = fadeOut(src),
                mixParticipantCount = 0,
                peakAbsFs = 0.0,
            )
        }
        return EMPTY
    }

    companion object {
        /** 2.5 ms @ 48 kHz. */
        const val FADE_IN_SAMPLES: Int = MediaMixConstants.SAMPLE_RATE_HZ / 400

        val EMPTY: MixedBlock = MixedBlock(shortArrayOf(), 0, 0.0)

        internal fun fadeOut(src: ShortArray): ShortArray {
            val n = src.size
            if (n == 0) return shortArrayOf()
            if (n == 1) return shortArrayOf(0)
            val denom = (n - 1).toFloat()
            return ShortArray(n) { i ->
                val gain = 1f - (i / denom)
                (src[i] * gain).toInt().coerceIn(-32_768, 32_767).toShort()
            }
        }

        internal fun fadeIn(src: ShortArray): ShortArray {
            val n = src.size
            val fade = FADE_IN_SAMPLES.coerceAtMost(n)
            if (fade <= 0) return src.copyOf()
            val out = src.copyOf()
            for (i in 0 until fade) {
                out[i] = (src[i] * i / fade.toFloat()).toInt().coerceIn(-32_768, 32_767).toShort()
            }
            return out
        }
    }
}
