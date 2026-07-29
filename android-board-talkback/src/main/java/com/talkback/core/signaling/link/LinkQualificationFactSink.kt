package com.talkback.core.signaling.link

/** Transport-layer facts consumed by [LinkQualificationTracker]. */
interface LinkQualificationFactSink {
    fun onSocketBound(socketId: Long, rebindGeneration: Long, networkId: String)
    fun onReceiveLoopStarted(socketId: Long, rebindGeneration: Long)
    fun onFirstOutboundAfterRebind(socketId: Long, rebindGeneration: Long)
    fun onFirstInboundAfterRebind(socketId: Long, rebindGeneration: Long)
    fun onNetworkLost()
    fun onQualificationTimeout()
}
