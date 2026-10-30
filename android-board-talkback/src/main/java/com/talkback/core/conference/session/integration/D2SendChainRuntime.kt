package com.talkback.core.conference.session.integration

/**
 * D2 gates. Production: Pre-APM [ProcessLocalMicFrameSource], no mux, no capture observe.
 */
object D2SendChainRuntime {
    const val PROP_POST_APM_MUX = "debug.talkback.d2.post_apm_mux"
    const val PROP_CAPTURE_OBSERVE = "debug.talkback.d2.capture_observe"
    const val PROP_SEND_HEADROOM = "debug.talkback.d2.send_headroom"

    /** Opt-in Post-APM mux with Pre-APM fallback; default off. */
    @Volatile
    var postApmMuxEnabled: Boolean = false

    /** APM_IN/APM_OUT (A12) + S2 per-second rollup + TX path counters. */
    @Volatile
    var captureChainObserveEnabled: Boolean = false

    @Volatile
    var sendHeadroomEnabled: Boolean = false

    fun applyOptionalDebugSystemProperties() {
        postApmMuxEnabled = debugPropEnabled(PROP_POST_APM_MUX)
        captureChainObserveEnabled = debugPropEnabled(PROP_CAPTURE_OBSERVE)
        sendHeadroomEnabled = debugPropEnabled(PROP_SEND_HEADROOM)
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
