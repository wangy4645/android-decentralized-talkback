package com.talkback.core.conference.session.integration

import com.talkback.core.conference.aqc.AqcFrameConstants
import com.talkback.core.conference.aqc.PcmLevelMetrics
import com.talkback.core.conference.runtime.OpusCodecConstants
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.pow

/**
 * Phase B — M01-only gated linear gain before Opus on multicast TX encode path.
 * Default off; opt-in via [M01GatedTxEncodeLevelRuntime.applyOptionalDebugSystemProperties].
 */
object M01GatedTxEncodeLevelRuntime {
    const val PROP_ENABLE = "debug.talkback.b1.m01_gated_send"

    @Volatile
    var enabled: Boolean = false

    fun applyOptionalDebugSystemProperties() {
        enabled = debugPropEnabled(PROP_ENABLE)
    }

    private fun debugPropEnabled(key: String): Boolean =
        try {
            val clazz = Class.forName("android.os.SystemProperties")
            val get =
                clazz.getMethod(
                    "get",
                    String::class.java,
                    String::class.java,
                )
            val value = get.invoke(null, key, "0") as? String ?: "0"
            value == "1" || value.equals("true", ignoreCase = true)
        } catch (_: Throwable) {
            false
        }
}

data class M01GatedTxEncodeLevelConfig(
    val openRmsDbfs: Double = -55.0,
    val closeRmsDbfs: Double = -58.0,
    val hangoverFrames: Int = 10,
    val maxGainDb: Double = 6.0,
    val peakCeilingFs: Double = AqcFrameConstants.OUTPUT_PEAK_ABS_FS,
)

data class M01GatedTxEncodeLevelFrameResult(
    val samples: ShortArray,
    val gateOpen: Boolean,
    val gainApplied: Boolean,
    val limiterApplied: Boolean,
    val limiterScale: Float,
    val inputRmsDbfs: Double,
    val outputRmsDbfs: Double,
)

object M01GatedTxEncodeLevelProcessor {
    private val config = M01GatedTxEncodeLevelConfig()

    private data class GateState(
        var gateOpen: Boolean = false,
        var hangoverRemaining: Int = 0,
    )

    private val gateBySession = ConcurrentHashMap<String, GateState>()

    fun clearSession(sessionId: String) {
        gateBySession.remove(sessionId)
    }

    fun maybeProcess(
        sessionId: String,
        moduleId: String,
        pcmForEncode: ShortArray,
    ): M01GatedTxEncodeLevelFrameResult {
        val inputRms = PcmLevelMetrics.rmsDbfs(pcmForEncode)
        if (!M01GatedTxEncodeLevelRuntime.enabled || moduleId != TARGET_MODULE_ID) {
            return passthrough(pcmForEncode, inputRms, gateOpen = false)
        }
        require(pcmForEncode.size == OpusCodecConstants.FRAME_SAMPLES_20MS)

        val state = gateBySession.getOrPut(sessionId) { GateState() }
        val gateOpen = updateGate(state, inputRms)
        if (!gateOpen) {
            return passthrough(pcmForEncode, inputRms, gateOpen = false)
        }

        val linearGain = 10.0.pow(config.maxGainDb / 20.0)
        val gained =
            ShortArray(pcmForEncode.size) { i ->
                (pcmForEncode[i] * linearGain).toInt().coerceIn(-32768, 32767).toShort()
            }
        val inputPeak = PcmLevelMetrics.peakAbs(gained)
        val ceiling = (config.peakCeilingFs * 32767.0).toFloat()
        val scale =
            if (inputPeak > ceiling && inputPeak > 0) {
                ceiling / inputPeak.toFloat()
            } else {
                1f
            }
        val out =
            if (scale == 1f) {
                gained
            } else {
                ShortArray(gained.size) { i ->
                    (gained[i] * scale).toInt().coerceIn(-32768, 32767).toShort()
                }
            }
        return M01GatedTxEncodeLevelFrameResult(
            samples = out,
            gateOpen = true,
            gainApplied = true,
            limiterApplied = scale < 1f,
            limiterScale = scale,
            inputRmsDbfs = inputRms,
            outputRmsDbfs = PcmLevelMetrics.rmsDbfs(out),
        )
    }

    private fun updateGate(
        state: GateState,
        rmsDbfs: Double,
    ): Boolean {
        if (!rmsDbfs.isFinite()) {
            decayHangover(state)
            return state.gateOpen
        }
        if (rmsDbfs > config.openRmsDbfs) {
            state.gateOpen = true
            state.hangoverRemaining = config.hangoverFrames
            return true
        }
        if (rmsDbfs > config.closeRmsDbfs && state.gateOpen) {
            state.hangoverRemaining = config.hangoverFrames
            return true
        }
        decayHangover(state)
        return state.gateOpen
    }

    private fun decayHangover(state: GateState) {
        if (state.hangoverRemaining > 0) {
            state.hangoverRemaining--
        } else {
            state.gateOpen = false
        }
    }

    private fun passthrough(
        pcm: ShortArray,
        inputRms: Double,
        gateOpen: Boolean,
    ): M01GatedTxEncodeLevelFrameResult =
        M01GatedTxEncodeLevelFrameResult(
            samples = pcm,
            gateOpen = gateOpen,
            gainApplied = false,
            limiterApplied = false,
            limiterScale = 1f,
            inputRmsDbfs = inputRms,
            outputRmsDbfs = inputRms,
        )

    private const val TARGET_MODULE_ID = "M01"
}
