package com.talkback.core.signaling

interface SignalingTransportLifecycleReporter {
    fun onSocketCreated(socketId: Long, port: Int)
    fun onSocketClosed(socketId: Long, reason: String)
    fun onSocketBound(socketId: Long, port: Int, boundNetworkId: String)
    fun onSocketRebind(socketId: Long, port: Int, reason: String, boundNetworkId: String)
    fun onReceiveLoopStarted(socketId: Long)
    fun onReceiveLoopStopped(socketId: Long)
}