package com.talkback.core.signaling.peer

import com.talkback.core.util.TalkbackLog

/**
 * ADR-0022 INV-SIG projection visibility.
 * Grep: PEER_EDGE_READY, PEER_EDGE_NOT_READY, PEER_EDGE_INVALIDATED
 */
object PeerEdgeSignalingTrace {
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

    fun ready(
        remoteModuleId: String,
        generation: Long,
        previous: Boolean,
        reason: String,
        observedSignal: String? = null,
        lastInboundAtMs: Long? = null
    ) {
        val signalPart = observedSignal?.let { " observedSignal=$it" } ?: ""
        val inboundPart = lastInboundAtMs?.let { " lastInboundAt=$it" } ?: ""
        log(
            "PEER_EDGE_READY remote=$remoteModuleId generation=$generation " +
                "previous=$previous current=true reason=$reason$signalPart$inboundPart " +
                "timestamp=${System.currentTimeMillis()}"
        )
    }

    fun notReady(
        remoteModuleId: String,
        generation: Long,
        previous: Boolean,
        reason: String,
        lastInboundAtMs: Long? = null
    ) {
        val inboundPart = lastInboundAtMs?.let { " lastInboundAt=$it" } ?: ""
        log(
            "PEER_EDGE_NOT_READY remote=$remoteModuleId generation=$generation " +
                "previous=$previous current=false reason=$reason$inboundPart " +
                "timestamp=${System.currentTimeMillis()}"
        )
    }

    fun invalidated(
        remoteModuleId: String,
        oldGeneration: Long,
        newGeneration: Long,
        reason: String = "TRANSPORT_EPOCH_ADVANCED"
    ) {
        log(
            "PEER_EDGE_INVALIDATED remote=$remoteModuleId oldGeneration=$oldGeneration " +
                "newGeneration=$newGeneration reason=$reason " +
                "timestamp=${System.currentTimeMillis()}"
        )
    }
}