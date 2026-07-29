package com.talkback.core.webrtc

import java.nio.ByteBuffer

/**
 * Edge negotiation settling fact (4.3-E Step4-A).
 * Not a timer: set when Answerer remote-offer convergence completes; cleared by
 * ownership transition / next negotiation completion / rollback.
 */
enum class NegotiationSettling {
    NONE,
    ANSWERER_SETTLED
}

interface WebRtcAudioEngine {
    fun setOnLocalIceCandidate(listener: (String) -> Unit)
    fun createOffer(iceRestart: Boolean = false): String
    fun applyRemoteOffer(sdp: String, polite: Boolean = true): String
    fun applyRemoteAnswer(sdp: String, polite: Boolean = true)
    fun rollbackNegotiation()
    fun addIceCandidate(candidate: String)
    fun startCapture()
    fun stopCapture()
    fun isCapturing(): Boolean = false
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

    /** Latest ICE connection state name (PeerConnection.IceConnectionState). */
    fun iceConnectionState(): String = "UNKNOWN"

    /**
     * Read-only PeerConnection negotiation snapshot (4.3-E observation).
     * Must not affect signaling / ICE behavior.
     */
    fun negotiationSnapshot(): NegotiationPcSnapshot = NegotiationPcSnapshot()

    /**
     * Whether this PC just completed Answerer remote-offer convergence.
     * Observation fact for ICE_RESTART_REQUESTED / future stabilization gate.
     */
    fun negotiationSettling(): NegotiationSettling = NegotiationSettling.NONE

    fun justSettledAsAnswerer(): Boolean =
        negotiationSettling() == NegotiationSettling.ANSWERER_SETTLED

    /**
     * Coordinator seam after local Answerer SDP + GROUP_ACCEPT signaling handoff success
     * (INV-NEG-005). Clears [NegotiationSettling.ANSWERER_SETTLED] and returns true when a
     * release fact should be routed. Must not be called from Recovery.
     */
    fun commitAnswererTransaction(): Boolean = false

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
