package com.talkback.core.signaling

import com.talkback.core.util.TalkbackLog

/** R28-L Appendix M-C: discovery transport rebind / self-healing traces. */
object DiscoveryTransportTrace {

    private var logSink: ((String) -> Unit)? = null

    internal fun resetForTest(sink: ((String) -> Unit)? = null) {
        logSink = sink
    }

    private fun log(message: String) {
        val sink = logSink
        if (sink != null) {
            sink(message)
        } else {
            TalkbackLog.i(message)
        }
    }

    fun rebindRequested(port: Int, networkId: String, reason: String, attempt: Int) {
        log(
            "DISCOVERY_REBIND_REQUESTED port=$port networkId=$networkId reason=$reason attempt=$attempt"
        )
    }

    fun rebindSuccess(port: Int, networkId: String, reason: String, attempt: Int) {
        log(
            "DISCOVERY_REBIND_SUCCESS port=$port networkId=$networkId reason=$reason attempt=$attempt"
        )
    }

    fun rebindFailed(port: Int, networkId: String, reason: String, attempt: Int, error: String) {
        log(
            "DISCOVERY_REBIND_FAILED port=$port networkId=$networkId reason=$reason attempt=$attempt " +
                "error=$error"
        )
    }

    fun rebindRetryScheduled(
        port: Int,
        networkId: String,
        reason: String,
        attempt: Int,
        delayMs: Long
    ) {
        log(
            "DISCOVERY_REBIND_RETRY_SCHEDULED port=$port networkId=$networkId reason=$reason " +
                "attempt=$attempt delayMs=$delayMs"
        )
    }

    fun ready(port: Int, networkId: String, reason: String) {
        log("DISCOVERY_READY port=$port networkId=$networkId reason=$reason state=ACTIVE")
    }
}