package com.talkback.core.conference.aqc

import com.talkback.core.conference.runtime.MediaJitterConstants
import com.talkback.core.conference.runtime.PcmFrame
import com.talkback.core.conference.runtime.SlotPullDisposition
import java.util.concurrent.ConcurrentHashMap

/**
 * Bounded PLC / comfort noise PCM when jitter pull is not DECODE_FRAME.
 */
class PlayoutPcmSynthesizer {
    enum class OutputKind {
        REAL,
        PLC_ATTENUATED,
        COMFORT_NOISE,
        SILENCE_GAP,
    }

    data class SynthesizedFrame(
        val frame: PcmFrame,
        val kind: OutputKind,
    )

    private val lastRealBySource = ConcurrentHashMap<String, ShortArray>()
    private val consecutivePlcBySource = ConcurrentHashMap<String, Int>()

    fun rememberReal(sourceIdentity: String, samples: ShortArray) {
        if (samples.size == AqcFrameConstants.FRAME_SAMPLES_20MS) {
            lastRealBySource[sourceIdentity] = samples.copyOf()
        }
        consecutivePlcBySource[sourceIdentity] = 0
    }

    fun synthesizeForPull(
        sourceIdentity: String,
        pullDisposition: SlotPullDisposition?,
    ): SynthesizedFrame? {
        return when (pullDisposition) {
            SlotPullDisposition.PLC_SYNTHESIS -> synthesizePlc(sourceIdentity)
            SlotPullDisposition.SILENCE_GAP ->
                SynthesizedFrame(
                    frame = PcmFrame(AqcComfortNoise.frame20ms(sourceIdentity.hashCode()), usableForMix = false),
                    kind = OutputKind.SILENCE_GAP,
                )
            else -> null
        }
    }

    private fun synthesizePlc(sourceIdentity: String): SynthesizedFrame {
        val prev = consecutivePlcBySource.merge(sourceIdentity, 1) { _, v -> v + 1 } ?: 1
        if (prev > MediaJitterConstants.MAX_CONSECUTIVE_PLC_FRAMES) {
            return SynthesizedFrame(
                frame = PcmFrame(AqcComfortNoise.frame20ms(sourceIdentity.hashCode()), usableForMix = false),
                kind = OutputKind.COMFORT_NOISE,
            )
        }
        val template = lastRealBySource[sourceIdentity]
        val samples =
            if (template != null) {
                val attenuation = 1f - (prev * 0.15f).coerceAtMost(0.75f)
                ShortArray(template.size) { i ->
                    (template[i] * attenuation).toInt().coerceIn(-32768, 32767).toShort()
                }
            } else {
                AqcComfortNoise.frame20ms(sourceIdentity.hashCode())
            }
        return SynthesizedFrame(
            frame = PcmFrame(samples, usableForMix = false),
            kind = if (template != null) OutputKind.PLC_ATTENUATED else OutputKind.COMFORT_NOISE,
        )
    }

    fun continuousFallbackFrame(): SynthesizedFrame =
        SynthesizedFrame(
            frame = PcmFrame(AqcComfortNoise.frame20ms(), usableForMix = false),
            kind = OutputKind.COMFORT_NOISE,
        )
}
