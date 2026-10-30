package com.talkback.core.conference.session.integration

import android.util.Log
import com.talkback.core.conference.runtime.MediaMixConstants
import com.talkback.core.conference.runtime.MixedBlock
import com.talkback.core.conference.runtime.MixBlockDiagnostics
import java.io.File
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.pow

/**
 * L2 — optional post-AVG makeup on mix output only (field flag). Default OFF.
 * Peak re-clamped to [MediaMixConstants.OUTPUT_PEAK_ABS_FS]. Does not apply to SUM path.
 */
object MixOutputMakeupFieldExperiment {
    const val LOG_TAG = "TalkbackMixOutputMakeup"
    const val FIELD_FLAG_FILE_NAME = "mix_output_makeup_field.flag"
    const val MAX_GAIN_DB: Double = 9.0

    @Volatile
    var enabled: Boolean = false

    @Volatile
    var gainDb: Double = 0.0

    fun resetForTest() {
        enabled = false
        gainDb = 0.0
    }

    fun configureForFieldTest(enabled: Boolean, gainDb: Double) {
        this.enabled = enabled
        this.gainDb = sanitizeGainDb(gainDb)
    }

    fun applyFromPrivateFlagFile(flagFile: File) {
        if (!flagFile.isFile) {
            Log.i(LOG_TAG, "flag missing path=${flagFile.absolutePath} makeup=off")
            enabled = false
            gainDb = 0.0
            return
        }
        val line =
            flagFile
                .readText()
                .lineSequence()
                .map { it.trim() }
                .firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
        if (line == null) {
            Log.w(LOG_TAG, "empty flag ${flagFile.absolutePath}")
            enabled = false
            gainDb = 0.0
            return
        }
        when (line.uppercase()) {
            "OFF", "0", "DISABLE" -> {
                enabled = false
                gainDb = 0.0
                Log.i(LOG_TAG, "applied makeup=off from ${flagFile.absolutePath}")
            }
            else -> {
                val db = parseGainDb(line)
                if (db == null) {
                    Log.w(LOG_TAG, "unknown line '$line' in ${flagFile.absolutePath}")
                    enabled = false
                    gainDb = 0.0
                    return
                }
                enabled = true
                gainDb = db
                Log.i(LOG_TAG, "applied makeupGainDb=$gainDb from ${flagFile.absolutePath}")
            }
        }
    }

    fun applyIfActive(block: MixedBlock): MixedBlock {
        if (!enabled || gainDb <= 0.0 || block.samples.isEmpty()) {
            return block
        }
        val linear = 10.0.pow(gainDb / 20.0).toFloat()
        val len = block.samples.size
        val scaled = FloatArray(len)
        var peak = 0f
        for (i in 0 until len) {
            val v = block.samples[i].toFloat() * linear
            scaled[i] = v
            peak = maxOf(peak, abs(v))
        }
        val ceiling = (MediaMixConstants.OUTPUT_PEAK_ABS_FS * MediaMixConstants.S16_FULL_SCALE).toFloat()
        val scale =
            if (peak > ceiling && peak > 0f) {
                ceiling / peak
            } else {
                1f
            }
        val out = ShortArray(len)
        var outPeak = 0.0
        for (i in 0 until len) {
            val v = (scaled[i] * scale).toInt().coerceIn(-32_768, 32_767)
            out[i] = v.toShort()
            outPeak = maxOf(outPeak, abs(v).toDouble() / MediaMixConstants.S16_FULL_SCALE)
        }
        val maxAllowed = MediaMixConstants.OUTPUT_PEAK_ABS_FS
        if (outPeak > maxAllowed) {
            val fix = (maxAllowed / outPeak).toFloat()
            outPeak = 0.0
            for (i in 0 until len) {
                val v = (out[i] * fix).toInt().coerceIn(-32_768, 32_767)
                out[i] = v.toShort()
                outPeak = maxOf(outPeak, abs(v).toDouble() / MediaMixConstants.S16_FULL_SCALE)
            }
        }
        val diag = block.blockDiagnostics
        return MixedBlock(
            samples = out,
            mixParticipantCount = block.mixParticipantCount,
            peakAbsFs = min(outPeak, maxAllowed),
            blockDiagnostics =
                diag?.copy(
                    limiterGain = diag.limiterGain * scale,
                    limiterActive = diag.limiterActive || scale < 0.999f,
                    mixOutputMakeupGainDb = gainDb,
                ) ?: MixBlockDiagnostics(
                    combineMode = "UNKNOWN",
                    preLimitPeakAbsFs = outPeak,
                    limiterGain = scale.toDouble(),
                    limiterActive = scale < 0.999f,
                    mixOutputMakeupGainDb = gainDb,
                ),
        )
    }

    internal fun parseGainDb(line: String): Double? {
        val t = line.trim().uppercase().removePrefix("+")
        val num = t.removeSuffix("DB").trim()
        return num.toDoubleOrNull()?.let { sanitizeGainDb(it) }
    }

    private fun sanitizeGainDb(db: Double): Double = db.coerceIn(0.0, MAX_GAIN_DB)
}
