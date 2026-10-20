package com.talkback.core.conference.aqc

/**
 * Audio Quality Core feature gates. Default off — enable from desk tests or field harness only.
 */
object AqcRuntime {
    /** POST-APM tap + [TxProcessingChain] before Opus encode. */
    @Volatile
    var txProcessingEnabled: Boolean = false

    /** [MembershipStableMixer] instead of per-tick participant count divisor. */
    @Volatile
    var membershipMixEnabled: Boolean = false

    /** PLC/CN PCM synthesis on non-DECODE pulls; comfort noise when resolve skips. */
    @Volatile
    var continuousPlayoutEnabled: Boolean = false

    fun configureForDesk(
        tx: Boolean = true,
        membershipMix: Boolean = true,
        continuousPlayout: Boolean = true,
    ) {
        txProcessingEnabled = tx
        membershipMixEnabled = membershipMix
        continuousPlayoutEnabled = continuousPlayout
    }

    fun resetToProductionDefaults() {
        txProcessingEnabled = false
        membershipMixEnabled = false
        continuousPlayoutEnabled = false
    }

    /**
     * Opt-in field probe only. Reads Android [SystemProperties] when present;
     * unset properties leave defaults (all false). Never enables flags by itself.
     *
     * ```
     * adb shell setprop debug.talkback.aqc.tx 1
     * adb shell setprop debug.talkback.aqc.mix 1
     * adb shell setprop debug.talkback.aqc.playout 1
     * ```
     */
    fun applyOptionalDebugSystemProperties() {
        val tx = debugPropEnabled("debug.talkback.aqc.tx")
        val mix = debugPropEnabled("debug.talkback.aqc.mix")
        val playout = debugPropEnabled("debug.talkback.aqc.playout")
        if (!tx && !mix && !playout) return
        txProcessingEnabled = tx
        membershipMixEnabled = mix
        continuousPlayoutEnabled = playout
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
