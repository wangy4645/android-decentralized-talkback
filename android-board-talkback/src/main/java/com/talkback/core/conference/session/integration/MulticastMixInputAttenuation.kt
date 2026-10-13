package com.talkback.core.conference.session.integration

import com.talkback.core.conference.runtime.PcmFrame
import kotlin.math.pow

/**
 * Single entry for PCM entering [com.talkback.core.conference.runtime.EqualWeightMixer] on multicast production path.
 */
object MulticastMixInputAttenuation {
    fun applyForMix(pcm: PcmFrame): PcmFrame {
        if (MulticastMixInputHeadroomFieldExperiment.isActive()) {
            return MulticastMixInputHeadroomFieldExperiment.mixPcmFrame(pcm)
        }
        if (!BrightScreenMeetingMixPolicy.productionMixInputHeadroomEnabled) {
            return pcm
        }
        val db = BrightScreenMeetingMixPolicy.productionMixInputHeadroomDb
        if (db >= 0.0) {
            return pcm
        }
        return PcmFrame(
            samples = attenuateDb(pcm.samples, db),
            usableForMix = pcm.usableForMix,
        )
    }

    fun attenuateDb(samples: ShortArray, gainDb: Double): ShortArray {
        val g = 10.0.pow(gainDb / 20.0)
        return ShortArray(samples.size) { i ->
            (samples[i] * g).toInt().coerceIn(-32_768, 32_767).toShort()
        }
    }

    fun effectiveHeadroomDbForTelemetry(): Double? =
        when {
            MulticastMixInputHeadroomFieldExperiment.isActive() ->
                MulticastMixInputHeadroomFieldExperiment.gainDb
            BrightScreenMeetingMixPolicy.productionMixInputHeadroomEnabled ->
                BrightScreenMeetingMixPolicy.productionMixInputHeadroomDb
            else -> null
        }
}
