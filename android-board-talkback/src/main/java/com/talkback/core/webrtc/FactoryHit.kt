package com.talkback.core.webrtc

enum class FactoryHit {
    NEW,
    REUSE
}

data class EngineFactoryProvision(
    val engine: WebRtcAudioEngine,
    val factoryHit: FactoryHit
)
