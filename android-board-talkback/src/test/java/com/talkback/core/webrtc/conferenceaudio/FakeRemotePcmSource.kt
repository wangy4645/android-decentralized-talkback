package com.talkback.core.webrtc.conferenceaudio

/** Phase 1a-2 harness — deterministic remote PCM producer (no WebRTC / Coordinator). */
class FakeRemotePcmSource(
    val sourceId: String,
    private val format: ConferencePcmFormat = ConferencePcmFormat.CANONICAL,
    private var level: Short = 4_000
) {
    var admitted: Boolean = false
        private set

    fun admit() {
        admitted = true
    }

    fun revoke() {
        admitted = false
    }

    fun produceFrame(): PcmFrame {
        require(admitted) { "source not admitted: $sourceId" }
        return PcmFrame.constantLevel(level, format)
    }

    fun setLevel(newLevel: Short) {
        level = newLevel
    }
}
