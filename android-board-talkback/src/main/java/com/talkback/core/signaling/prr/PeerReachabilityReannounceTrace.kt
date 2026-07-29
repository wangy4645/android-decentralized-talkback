package com.talkback.core.signaling.prr

import com.talkback.core.util.TalkbackLog

/**
 * R28-PRR: peer reachability re-announcement facts (emit-only).
 * Grep: PRR_EPISODE_STARTED, PRR_EPISODE_SKIPPED, PRR_HELLO_SENT, PRR_ENDPOINT_REANNOUNCED,
 *       PRR_FACT_OBSERVED
 */
object PeerReachabilityReannounceTrace {

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

    fun episodeStarted(
        transportEpoch: Long,
        socketId: Long,
        reason: String,
        networkId: String
    ) {
        log(
            "PRR_EPISODE_STARTED transportEpoch=$transportEpoch socketId=$socketId " +
                "reason=$reason networkId=$networkId timestamp=${System.currentTimeMillis()}"
        )
    }

    fun episodeSkipped(transportEpoch: Long, reason: String) {
        log(
            "PRR_EPISODE_SKIPPED transportEpoch=$transportEpoch reason=$reason " +
                "timestamp=${System.currentTimeMillis()}"
        )
    }

    fun helloSent(transportEpoch: Long, socketId: Long, networkId: String) {
        log(
            "PRR_HELLO_SENT transportEpoch=$transportEpoch socketId=$socketId " +
                "networkId=$networkId timestamp=${System.currentTimeMillis()}"
        )
    }

    fun endpointReannounced(transportEpoch: Long, socketId: Long, networkId: String) {
        log(
            "PRR_ENDPOINT_REANNOUNCED transportEpoch=$transportEpoch socketId=$socketId " +
                "networkId=$networkId timestamp=${System.currentTimeMillis()}"
        )
    }

    fun factObserved(
        localEpoch: Long,
        remoteModuleId: String,
        fact: String,
        remoteEpoch: Long,
        src: String,
        socketId: Long
    ) {
        val remoteEpochPart = if (remoteEpoch > 0L) " remoteEpoch=$remoteEpoch" else ""
        log(
            "PRR_FACT_OBSERVED localEpoch=$localEpoch remoteModuleId=$remoteModuleId " +
                "fact=$fact$remoteEpochPart src=$src socketId=$socketId " +
                "timestamp=${System.currentTimeMillis()}"
        )
    }
}