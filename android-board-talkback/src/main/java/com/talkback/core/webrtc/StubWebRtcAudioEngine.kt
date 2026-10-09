package com.talkback.core.webrtc

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import com.talkback.core.webrtc.conferenceaudio.ConferencePcmFormat

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
    @Volatile
    private var iceConnectionStateName = "NEW"
    @Volatile
    private var negotiationSettlingState = NegotiationSettling.NONE
    @Volatile
    private var inboundPcmSink: InboundPcmSink? = null
    @Volatile
    var programRelayMode: ProgramRelayMode = ProgramRelayMode.MICROPHONE
        private set
    val injectedProgramFrames = mutableListOf<ByteArray>()
    override var playbackDiagnosticTag: String? = null
    override var remoteTrackDiagnosticLogger: ((Boolean) -> Unit)? = null

    override fun setOnLocalIceCandidate(listener: (String) -> Unit) {
        iceListener = listener
    }

    override fun createOffer(iceRestart: Boolean): String {
        // INV-NEG-001: do not clear Answerer settling from createOffer.
        return "stub-offer-${if (iceRestart) "restart-" else ""}${UUID.randomUUID()}"
    }

    override fun applyRemoteOffer(sdp: String, polite: Boolean): String {
        remoteOffer = sdp
        remoteAnswer = null
        negotiationSettlingState = NegotiationSettling.ANSWERER_SETTLED
        return "stub-answer-${UUID.randomUUID()}"
    }

    override fun applyRemoteAnswer(sdp: String, polite: Boolean) {
        remoteAnswer = sdp
        negotiationSettlingState = NegotiationSettling.NONE
    }

    override fun rollbackNegotiation() {
        negotiationSettlingState = NegotiationSettling.NONE
    }

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

    override fun setInboundPcmSink(sink: InboundPcmSink?) {
        inboundPcmSink = sink
    }

    fun simulateInboundPcm(
        frames: Int = ConferencePcmFormat.CANONICAL.samplesPerFrame,
        sampleRate: Int = ConferencePcmFormat.CANONICAL.sampleRateHz,
        channels: Int = ConferencePcmFormat.CANONICAL.channels,
        bitsPerSample: Int = ConferencePcmFormat.CANONICAL.bitsPerSample,
        fill: Short = 1_000
    ) {
        val bytesPerFrame = bitsPerSample / 8 * channels
        val buffer = ByteBuffer.allocate(bytesPerFrame * frames).order(ByteOrder.LITTLE_ENDIAN)
        repeat(frames) { buffer.putShort(fill) }
        buffer.flip()
        inboundPcmSink?.onPcm(buffer, bitsPerSample, sampleRate, channels, frames)
    }

    override fun setProgramRelayMode(mode: ProgramRelayMode) {
        programRelayMode = mode
    }

    override fun feedProgramPcm(
        audioData: ByteBuffer,
        bitsPerSample: Int,
        sampleRate: Int,
        numberOfChannels: Int,
        numberOfFrames: Int
    ) {
        val bytes = ByteArray(audioData.remaining())
        audioData.duplicate().get(bytes)
        injectedProgramFrames.add(bytes)
    }

    override fun programSenderSnapshot(): ProgramSenderSnapshot {
        val programId = "stub-program"
        val currentId = if (programRelayMode == ProgramRelayMode.PROGRAM) programId else "stub-mic"
        return ProgramSenderSnapshot(
            senderId = "stub-sender",
            currentTrackId = currentId,
            expectedTrackId = programId,
            enabled = capturing.get(),
            readyState = "LIVE",
            lastReplaceAt = "none"
        )
    }

    override fun release() {
        capturing.set(false)
        remoteOffer = null
        remoteAnswer = null
        iceConnectionStateName = "CLOSED"
        negotiationSettlingState = NegotiationSettling.NONE
        inboundPcmSink = null
    }

    override fun iceConnectionState(): String = iceConnectionStateName

    override fun negotiationSettling(): NegotiationSettling = negotiationSettlingState

    override fun commitAnswererTransaction(): Boolean {
        if (negotiationSettlingState != NegotiationSettling.ANSWERER_SETTLED) return false
        negotiationSettlingState = NegotiationSettling.NONE
        return true
    }

    override fun negotiationSnapshot(): NegotiationPcSnapshot =
        NegotiationPcSnapshot(
            signalingState = "STABLE",
            iceConnectionState = iceConnectionStateName,
            connectionState = if (iceConnectionStateName == "CONNECTED") "CONNECTED" else "NEW",
            localDescriptionType = when {
                remoteAnswer != null -> "OFFER"
                remoteOffer != null -> "ANSWER"
                else -> null
            },
            remoteDescriptionType = when {
                remoteAnswer != null -> "ANSWER"
                remoteOffer != null -> "OFFER"
                else -> null
            }
        )

    fun simulateIceState(state: String) {
        iceConnectionStateName = state
    }

    override fun refreshAudioLevel() = Unit

    override fun inboundAudioLevel(): Float = 0f

    override fun outboundAudioLevel(): Float = 0f

    override fun isCapturing(): Boolean = capturing.get()
}
