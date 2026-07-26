package com.talkback.core.signaling.prr

/** Sends signaling reachability re-announcement (PRR_REANNOUNCE). */
fun interface SignalingReannounceSender {
    fun sendReannounce(snapshot: LocalEndpointSnapshot, transportEpoch: Long)
}