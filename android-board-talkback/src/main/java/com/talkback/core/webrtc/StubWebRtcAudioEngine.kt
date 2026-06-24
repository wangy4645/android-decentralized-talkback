package com.talkback.core.webrtc

import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Development stub for LAN talkback bring-up.
 * Replace with org.webrtc backed engine in integration phase.
 */
class StubWebRtcAudioEngine : WebRtcAudioEngine {
    private val capturing = AtomicBoolean(false)
    private var remoteOffer: String? = null
    private var remoteAnswer: String? = null
    private var iceListener: ((String) -> Unit)? = null
    @Volatile
    private var remotePlaybackEnabled = false
    override var playbackDiagnosticTag: String? = null
    override var remoteTrackDiagnosticLogger: ((Boolean) -> Unit)? = null

    override fun setOnLocalIceCandidate(listener: (String) -> Unit) {
        iceListener = listener
    }

    override fun createOffer(iceRestart: Boolean): String =
        "stub-offer-${if (iceRestart) "restart-" else ""}${UUID.randomUUID()}"

    override fun applyRemoteOffer(sdp: String, polite: Boolean): String {
        remoteOffer = sdp
        return "stub-answer-${UUID.randomUUID()}"
    }

    override fun applyRemoteAnswer(sdp: String, polite: Boolean) {
        remoteAnswer = sdp
    }

    override fun rollbackNegotiation() = Unit

    override fun addIceCandidate(candidate: String) = Unit

    override fun startCapture() {
        capturing.set(true)
    }

    override fun stopCapture() {
        capturing.set(false)
    }

    override fun setMuted(muted: Boolean) = Unit

    override fun setRemotePlaybackEnabled(enabled: Boolean) {
        remotePlaybackEnabled = enabled
    }

    override fun isRemotePlaybackEnabled(): Boolean = remotePlaybackEnabled

    override fun release() {
        capturing.set(false)
        remoteOffer = null
        remoteAnswer = null
    }

    override fun refreshAudioLevel() = Unit

    override fun inboundAudioLevel(): Float = 0f

    override fun outboundAudioLevel(): Float = 0f

    fun isCapturing(): Boolean = capturing.get()
}
