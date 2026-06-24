package com.talkback.core.webrtc

import java.nio.ByteBuffer

interface WebRtcAudioEngine {
    fun setOnLocalIceCandidate(listener: (String) -> Unit)
    fun createOffer(iceRestart: Boolean = false): String
    fun applyRemoteOffer(sdp: String, polite: Boolean = true): String
    fun applyRemoteAnswer(sdp: String, polite: Boolean = true)
    fun rollbackNegotiation()
    fun addIceCandidate(candidate: String)
    fun startCapture()
    fun stopCapture()
    fun setMuted(muted: Boolean)
    /** Optional trace tag set by coordinator (session id). */
    var playbackDiagnosticTag: String?
        get() = null
        set(_) = Unit

    /** Coordinator hook when a remote audio track is first attached. */
    var remoteTrackDiagnosticLogger: ((Boolean) -> Unit)?
        get() = null
        set(_) = Unit

    /** Enable/disable playback of all remote audio tracks attached to this peer connection. */
    fun setRemotePlaybackEnabled(enabled: Boolean) = Unit

    /** Whether remote playback is currently enabled on this peer connection. */
    fun isRemotePlaybackEnabled(): Boolean = false
    fun release()

    /** Poll WebRTC stats; call shortly before reading levels. */
    fun refreshAudioLevel()

    /** Linear 0..1 level of audio received from the remote peer. */
    fun inboundAudioLevel(): Float

    /** Linear 0..1 level of audio sent from the local microphone. */
    fun outboundAudioLevel(): Float

    /** Anchor relay: switch outbound between microphone and program track. */
    fun setProgramRelayMode(mode: ProgramRelayMode) = Unit

    /** Anchor relay: subscribe to inbound PCM for floor-holder tapping. */
    fun setInboundPcmSink(sink: InboundPcmSink?) = Unit

    /** Anchor relay: inject PCM into the program send track. */
    fun feedProgramPcm(
        audioData: ByteBuffer,
        bitsPerSample: Int,
        sampleRate: Int,
        numberOfChannels: Int,
        numberOfFrames: Int
    ) = Unit
}
