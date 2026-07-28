package com.talkback.core.signaling

interface SignalingTransportBinding {
    fun invalidateBinding(reason: String)
    fun rebindBinding(networkId: String, reason: String)
}