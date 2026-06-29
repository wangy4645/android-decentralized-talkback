package com.talkback.core.webrtc

import android.content.Context
import com.talkback.core.util.TalkbackLog
import org.webrtc.AudioTrack
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RTCStats
import org.webrtc.RTCStatsReport
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Real WebRTC engine for LAN talkback.
 */
class RealWebRtcAudioEngine(
    context: Context,
    private val onIceConnectionState: ((String) -> Unit)? = null
) : WebRtcAudioEngine {
    private val appContext = context.applicationContext
    private val peerConnectionFactory: PeerConnectionFactory = WebRtcSharedFactory.acquire(appContext)
    private val peerConnection: PeerConnection
    private val localAudioTrack: AudioTrack
    private val remoteAudioTracks = CopyOnWriteArrayList<AudioTrack>()
    private val pendingRemoteCandidates = CopyOnWriteArrayList<IceCandidate>()
    private val capturing = AtomicBoolean(false)
    @Volatile
    private var remoteDescriptionApplied = false
    private var localIceListener: ((String) -> Unit)? = null
    @Volatile
    private var released = false
    @Volatile
    private var remotePlaybackEnabled = false
    override var playbackDiagnosticTag: String? = null
    override var remoteTrackDiagnosticLogger: ((Boolean) -> Unit)? = null
    @Volatile
    private var inboundLevel = 0f
    @Volatile
    private var outboundLevel = 0f
    @Volatile
    private var programRelayMode = ProgramRelayMode.MICROPHONE
    private var inboundPcmSink: InboundPcmSink? = null
    private var programAudioSource: org.webrtc.AudioSource? = null
    private var programAudioTrack: AudioTrack? = null
    private var programCapturerObserver: Any? = null

    override fun setOnLocalIceCandidate(listener: (String) -> Unit) {
        localIceListener = listener
    }

    init {
        val rtcConfig = PeerConnection.RTCConfiguration(
            listOf(
                PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
            )
        ).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.DISABLED
        }

        peerConnection = requireNotNull(
            peerConnectionFactory.createPeerConnection(
                rtcConfig,
                object : PeerConnection.Observer {
                    override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
                    override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                        TalkbackLog.i("WebRTC ICE -> ${state.name}")
                        onIceConnectionState?.invoke(state.name)
                    }
                    override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
                    override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit
                    override fun onIceCandidate(candidate: IceCandidate) {
                        localIceListener?.invoke(encodeIceCandidate(candidate))
                    }

                    override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
                    override fun onAddStream(stream: org.webrtc.MediaStream) = Unit
                    override fun onRemoveStream(stream: org.webrtc.MediaStream) = Unit
                    override fun onDataChannel(channel: org.webrtc.DataChannel) = Unit
                    override fun onRenegotiationNeeded() = Unit
                    override fun onAddTrack(receiver: RtpReceiver, mediaStreams: Array<out org.webrtc.MediaStream>) {
                        attachRemoteAudioTrack(receiver.track())
                    }

                    override fun onTrack(transceiver: RtpTransceiver) {
                        if (transceiver.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO) {
                            attachRemoteAudioTrack(transceiver.receiver.track())
                        }
                    }
                }
            )
        ) { "Failed to create PeerConnection" }

        localAudioTrack = SharedLocalAudio.acquireLocalTrack(peerConnectionFactory)
        peerConnection.addTrack(
            localAudioTrack,
            listOf("tb_stream")
        )
        localAudioTrack.setEnabled(false)
    }

    override fun createOffer(iceRestart: Boolean): String {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
            if (iceRestart) {
                mandatory.add(MediaConstraints.KeyValuePair("IceRestart", "true"))
            }
        }
        createLocalDescription(
            createAction = { observer -> peerConnection.createOffer(observer, constraints) },
            setAction = { observer, desc -> peerConnection.setLocalDescription(observer, desc) }
        )
        return currentLocalSdp()
    }

    override fun applyRemoteOffer(sdp: String, polite: Boolean): String {
        val remote = SessionDescription(SessionDescription.Type.OFFER, sdp)
        when (peerConnection.signalingState()) {
            PeerConnection.SignalingState.STABLE -> {
                if (!polite) return currentLocalSdpOrEmpty()
                rollbackNegotiation()
            }
            PeerConnection.SignalingState.HAVE_LOCAL_OFFER -> {
                if (!polite) return currentLocalSdpOrEmpty()
                rollbackNegotiation()
            }
            else -> Unit
        }
        awaitSetDescription { observer -> peerConnection.setRemoteDescription(observer, remote) }
        markRemoteDescriptionApplied()
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
        }
        createLocalDescription(
            createAction = { observer -> peerConnection.createAnswer(observer, constraints) },
            setAction = { observer, desc -> peerConnection.setLocalDescription(observer, desc) }
        )
        return currentLocalSdp()
    }

    override fun applyRemoteAnswer(sdp: String, polite: Boolean) {
        when (peerConnection.signalingState()) {
            PeerConnection.SignalingState.STABLE -> {
                if (!polite) return
                if (peerConnection.localDescription?.type == SessionDescription.Type.ANSWER) {
                    return
                }
                rollbackNegotiation()
            }
            PeerConnection.SignalingState.HAVE_LOCAL_OFFER -> Unit
            else -> {
                if (!polite) return
                rollbackNegotiation()
            }
        }
        val remote = SessionDescription(SessionDescription.Type.ANSWER, sdp)
        awaitSetDescription { observer -> peerConnection.setRemoteDescription(observer, remote) }
        markRemoteDescriptionApplied()
    }

    override fun rollbackNegotiation() {
        if (released) return
        if (peerConnection.signalingState() == PeerConnection.SignalingState.STABLE) return
        runCatching {
            val latch = CountDownLatch(1)
            peerConnection.setLocalDescription(object : SdpObserver {
                override fun onCreateSuccess(desc: SessionDescription?) = Unit
                override fun onSetSuccess() {
                    latch.countDown()
                }
                override fun onCreateFailure(error: String?) {
                    latch.countDown()
                }
                override fun onSetFailure(error: String?) {
                    latch.countDown()
                }
            }, SessionDescription(SessionDescription.Type.ROLLBACK, ""))
            latch.await(1, TimeUnit.SECONDS)
        }.onFailure { TalkbackLog.w("WebRTC rollback failed: ${it.message}") }
    }

    override fun addIceCandidate(candidate: String) {
        if (released) return
        val ice = decodeIceCandidate(candidate) ?: return
        if (!remoteDescriptionApplied) {
            pendingRemoteCandidates.add(ice)
            return
        }
        peerConnection.addIceCandidate(ice)
    }

    override fun startCapture() {
        capturing.set(true)
        applyCaptureEnabled(true)
        TalkbackLog.i("WebRTC local capture ON relay=$programRelayMode")
    }

    override fun stopCapture() {
        capturing.set(false)
        applyCaptureEnabled(false)
        TalkbackLog.i("WebRTC local capture OFF")
    }

    override fun isCapturing(): Boolean = capturing.get()

    private fun applyCaptureEnabled(enabled: Boolean) {
        when (programRelayMode) {
            ProgramRelayMode.MICROPHONE -> {
                localAudioTrack.setEnabled(enabled)
                programAudioTrack?.setEnabled(false)
            }
            ProgramRelayMode.PROGRAM -> {
                localAudioTrack.setEnabled(false)
                ensureProgramTrack()
                programAudioTrack?.setEnabled(enabled)
            }
        }
    }

    override fun setMuted(muted: Boolean) {
        if (released) return
        if (muted) {
            localAudioTrack.setEnabled(false)
            programAudioTrack?.setEnabled(false)
        } else if (capturing.get()) {
            applyCaptureEnabled(true)
        }
    }

    override fun setRemotePlaybackEnabled(enabled: Boolean) {
        if (released) return
        remotePlaybackEnabled = enabled
        remoteAudioTracks.forEach { track -> track.setEnabled(enabled) }
    }

    override fun isRemotePlaybackEnabled(): Boolean = remotePlaybackEnabled

    override fun setProgramRelayMode(mode: ProgramRelayMode) {
        programRelayMode = mode
        if (mode == ProgramRelayMode.PROGRAM) {
            ensureProgramTrack()
        }
        if (capturing.get()) {
            applyCaptureEnabled(true)
        }
    }

    override fun setInboundPcmSink(sink: InboundPcmSink?) {
        inboundPcmSink = sink
        remoteAudioTracks.forEach { attachInboundSink(it) }
    }

    override fun feedProgramPcm(
        audioData: ByteBuffer,
        bitsPerSample: Int,
        sampleRate: Int,
        numberOfChannels: Int,
        numberOfFrames: Int
    ) {
        if (released || programRelayMode != ProgramRelayMode.PROGRAM) return
        val observer = programCapturerObserver ?: return
        runCatching {
            val method = observer.javaClass.methods.firstOrNull { method ->
                method.name == "onData" && method.parameterCount == 6
            } ?: return
            method.invoke(
                observer,
                audioData,
                bitsPerSample,
                sampleRate,
                numberOfChannels,
                numberOfFrames,
                System.nanoTime()
            )
        }.onFailure { TalkbackLog.w("Program PCM inject failed: ${it.message}") }
    }

    override fun release() {
        if (released) return
        released = true
        inboundLevel = 0f
        outboundLevel = 0f
        localAudioTrack.setEnabled(false)
        remoteAudioTracks.forEach { it.setEnabled(false) }
        remoteAudioTracks.clear()
        programAudioTrack?.setEnabled(false)
        runCatching { programAudioTrack?.dispose() }
        runCatching { programAudioSource?.dispose() }
        programAudioTrack = null
        programAudioSource = null
        programCapturerObserver = null
        pendingRemoteCandidates.clear()
        runCatching { peerConnection.close() }
        runCatching { peerConnection.dispose() }
        WebRtcSharedFactory.release()
    }

    override fun refreshAudioLevel() {
        if (released) return
        peerConnection.getStats { report -> applyStatsReport(report) }
    }

    override fun inboundAudioLevel(): Float = inboundLevel

    override fun outboundAudioLevel(): Float = outboundLevel

    private fun applyStatsReport(report: RTCStatsReport) {
        var inbound = 0.0
        var outbound = 0.0
        report.statsMap.values.forEach { stat ->
            if (!stat.isAudioKind()) return@forEach
            when (stat.type) {
                "inbound-rtp" -> stat.readAudioLevel()?.let { inbound = maxOf(inbound, it) }
                "outbound-rtp", "media-source" -> stat.readAudioLevel()?.let { outbound = maxOf(outbound, it) }
            }
        }
        inboundLevel = smoothLevel(inboundLevel, inbound.toFloat())
        outboundLevel = smoothLevel(outboundLevel, outbound.toFloat())
    }

    private fun smoothLevel(current: Float, raw: Float): Float {
        val clamped = raw.coerceIn(0f, 1f)
        return (current * 0.6f) + (clamped * 0.4f)
    }

    private fun RTCStats.isAudioKind(): Boolean {
        val kind = members["kind"]?.toString()?.lowercase()
        return kind == null || kind == "audio"
    }

    private fun RTCStats.readAudioLevel(): Double? =
        (members["audioLevel"] as? Number)?.toDouble()?.coerceIn(0.0, 1.0)

    private fun markRemoteDescriptionApplied() {
        remoteDescriptionApplied = true
        pendingRemoteCandidates.forEach { candidate ->
            runCatching { peerConnection.addIceCandidate(candidate) }
        }
        pendingRemoteCandidates.clear()
    }

    private fun attachRemoteAudioTrack(track: MediaStreamTrack?) {
        val audioTrack = track as? AudioTrack ?: return
        if (remoteAudioTracks.any { it.id() == audioTrack.id() }) return
        audioTrack.setEnabled(remotePlaybackEnabled)
        remoteAudioTracks.add(audioTrack)
        attachInboundSink(audioTrack)
        remoteTrackDiagnosticLogger?.invoke(remotePlaybackEnabled)
        TalkbackLog.i(
            "WebRTC remote audio track attached id=${audioTrack.id()} " +
                "tag=${playbackDiagnosticTag ?: "unknown"} playback=$remotePlaybackEnabled"
        )
    }

    private fun attachInboundSink(audioTrack: AudioTrack) {
        val sink = inboundPcmSink ?: return
        audioTrack.addSink { audioData, bitsPerSample, sampleRate, numberOfChannels, numberOfFrames, _ ->
            sink.onPcm(audioData, bitsPerSample, sampleRate, numberOfChannels, numberOfFrames)
        }
    }

    private fun ensureProgramTrack() {
        if (programAudioTrack != null) return
        val source = peerConnectionFactory.createAudioSource(MediaConstraints())
        val track = peerConnectionFactory.createAudioTrack("tb_program_${hashCode()}", source)
        peerConnection.addTrack(track, listOf("tb_program"))
        track.setEnabled(false)
        programAudioSource = source
        programAudioTrack = track
        programCapturerObserver = runCatching {
            source.javaClass.getMethod("getCapturerObserver").invoke(source)
        }.getOrNull() ?: runCatching {
            val field = source.javaClass.getDeclaredField("capturerObserver")
            field.isAccessible = true
            field.get(source)
        }.getOrNull()
    }

    private fun currentLocalSdp(): String {
        return peerConnection.localDescription?.description
            ?: error("Missing local SDP after negotiation")
    }

    private fun currentLocalSdpOrEmpty(): String =
        peerConnection.localDescription?.description ?: ""

    private fun createLocalDescription(
        createAction: (SdpObserver) -> Unit,
        setAction: (SdpObserver, SessionDescription) -> Unit
    ): SessionDescription {
        val created = AtomicReference<SessionDescription>()
        val createLatch = CountDownLatch(1)
        val createError = AtomicReference<String>()

        createAction(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription?) {
                if (desc != null) created.set(desc) else createError.set("SDP create success with null description")
                createLatch.countDown()
            }

            override fun onSetSuccess() = Unit
            override fun onCreateFailure(error: String?) {
                createError.set(error ?: "Unknown create failure")
                createLatch.countDown()
            }

            override fun onSetFailure(error: String?) = Unit
        })

        await(createLatch, "Timed out creating SDP")
        createError.get()?.let { error(it) }
        val desc = created.get() ?: error("Missing SDP after create")

        awaitSetDescription { observer -> setAction(observer, desc) }
        return desc
    }

    private fun awaitSetDescription(action: (SdpObserver) -> Unit) {
        val latch = CountDownLatch(1)
        val setError = AtomicReference<String>()
        action(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription?) = Unit
            override fun onSetSuccess() {
                latch.countDown()
            }

            override fun onCreateFailure(error: String?) = Unit
            override fun onSetFailure(error: String?) {
                setError.set(error ?: "Unknown set failure")
                latch.countDown()
            }
        })
        await(latch, "Timed out setting SDP")
        setError.get()?.let { error(it) }
    }

    private fun await(latch: CountDownLatch, timeoutMessage: String) {
        val ok = latch.await(3, TimeUnit.SECONDS)
        check(ok) { timeoutMessage }
    }

    private fun encodeIceCandidate(candidate: IceCandidate): String {
        return listOf(candidate.sdpMid ?: "", candidate.sdpMLineIndex.toString(), candidate.sdp).joinToString("|")
    }

    private fun decodeIceCandidate(raw: String): IceCandidate? {
        val parts = raw.split("|", limit = 3)
        if (parts.size != 3) return null
        val mid = parts[0].ifEmpty { null }
        val lineIndex = parts[1].toIntOrNull() ?: return null
        val sdp = parts[2]
        return IceCandidate(mid, lineIndex, sdp)
    }
}
