package com.talkback.core.conference.session.integration

import android.util.Log
import com.talkback.core.conference.runtime.PcmFrame
import java.io.File
import kotlin.math.pow

/**
 * LQ-A1 field experiment — fixed linear attenuation on multicast mix inputs only.
 *
 * Default OFF: no change to decode PCM, [com.talkback.core.conference.runtime.EqualWeightMixer]
 * inputs, or [Profile01A3MixInputTelemetry] pre-decode sample semantics.
 *
 * Field test: [applyStartupFromPrivateFlagFile] reads [FIELD_FLAG_FILE_NAME] under app
 * `filesDir` at cold start. Missing / unreadable / illegal content → fail-closed OFF.
 */
object MulticastMixInputHeadroomFieldExperiment {
    const val LOG_TAG = "TalkbackA1Headroom"
    const val FIELD_FLAG_FILE_NAME = "a1_mix_headroom_field.flag"
    const val DEFAULT_GAIN_DB: Double = -6.0

    @Volatile
    var enabled: Boolean = false

    @Volatile
    var gainDb: Double = DEFAULT_GAIN_DB

    @Volatile
    var lastStartupSource: String = "default_off"

    @Volatile
    var lastStartupFlagPath: String? = null

    fun configureForFieldTest(
        enabled: Boolean,
        gainDb: Double = DEFAULT_GAIN_DB,
        source: String = "configureForFieldTest",
    ) {
        applyConfig(enabled, sanitizeGainDb(gainDb), source)
    }

    fun resetForTest() {
        enabled = false
        gainDb = DEFAULT_GAIN_DB
        lastStartupSource = "default_off"
        lastStartupFlagPath = null
    }

    fun isActive(): Boolean = enabled

    fun applyStartupFromPrivateFlagFile(flagFile: File) {
        lastStartupFlagPath = flagFile.absolutePath
        val parsed =
            try {
                if (!flagFile.isFile) {
                    Parsed.ParsedOff("flag_file_missing")
                } else {
                    parseFlagContent(flagFile.readText())
                }
            } catch (_: Throwable) {
                Parsed.ParsedOff("flag_file_read_error")
            }
        when (parsed) {
            is Parsed.ParsedOff -> applyConfig(false, DEFAULT_GAIN_DB, parsed.source)
            is Parsed.ParsedOn -> applyConfig(true, parsed.gainDb, parsed.source)
        }
    }

    internal fun parseFlagContent(content: String): Parsed =
        try {
            val line =
                content
                    .lineSequence()
                    .map { it.trim() }
                    .firstOrNull { it.isNotEmpty() }
            if (line == null) {
                Parsed.ParsedOff("flag_file_empty")
            } else {
                parseFlagLine(line)
            }
        } catch (_: Throwable) {
            Parsed.ParsedOff("flag_file_parse_error")
        }

    internal fun parseFlagLine(line: String): Parsed {
        val tokens =
            line.split(',', ' ', '\t')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return Parsed.ParsedOff("flag_file_illegal")
        return when (tokens[0].uppercase()) {
            "OFF" -> Parsed.ParsedOff("flag_file_off")
            "ON" -> {
                val gain =
                    if (tokens.size >= 2) {
                        tokens[1].toDoubleOrNull() ?: return Parsed.ParsedOff("flag_file_illegal_gain")
                    } else {
                        DEFAULT_GAIN_DB
                    }
                if (!isLegalGainDb(gain)) {
                    Parsed.ParsedOff("flag_file_illegal_gain")
                } else {
                    Parsed.ParsedOn(gain, "flag_file_on")
                }
            }
            else -> Parsed.ParsedOff("flag_file_illegal")
        }
    }

    fun mixPcmFrame(frame: PcmFrame): PcmFrame {
        if (!enabled) return frame
        return PcmFrame(
            samples = attenuateSamples(frame.samples),
            usableForMix = frame.usableForMix,
        )
    }

    fun attenuateSamples(samples: ShortArray): ShortArray {
        if (!enabled) return samples
        val g = linearGain()
        return ShortArray(samples.size) { i ->
            (samples[i] * g).toInt().coerceIn(-32_768, 32_767).toShort()
        }
    }

    fun linearGain(): Double = 10.0.pow(gainDb / 20.0)

    internal fun sanitizeGainDb(gainDb: Double): Double =
        if (!isLegalGainDb(gainDb)) DEFAULT_GAIN_DB else gainDb

    internal fun isLegalGainDb(gainDb: Double): Boolean =
        !gainDb.isNaN() && !gainDb.isInfinite() && gainDb <= 0.0

    private fun applyConfig(
        enabled: Boolean,
        gainDb: Double,
        source: String,
    ) {
        this.enabled = enabled
        this.gainDb = gainDb
        this.lastStartupSource = source
        try {
            Log.i(
                LOG_TAG,
                "headroom enabled=$enabled gainDb=${this.gainDb} source=$source " +
                    "flagPath=${lastStartupFlagPath ?: "n/a"} active=${isActive()}",
            )
        } catch (_: Throwable) {
            // JVM unit tests — android.util.Log not mocked
        }
    }

    internal sealed class Parsed {
        data class ParsedOff(val source: String) : Parsed()

        data class ParsedOn(
            val gainDb: Double,
            val source: String,
        ) : Parsed()
    }
}
