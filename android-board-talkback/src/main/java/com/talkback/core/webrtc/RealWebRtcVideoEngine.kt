package com.talkback.core.webrtc

import android.content.Context
import com.talkback.core.util.TalkbackLog
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.VideoTrack
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

/**
 * Video-only PeerConnection. No microphone, no bulk DataChannel, no audio mix.
 */
class RealWebRtcVideoEngine(
    context: Context,
    private val observedId: String,
    private val role: VideoPcRole,
    private val onIceConnectionState: ((String) -> Unit)? = null,
) : WebRtcAudioEngine {
    private val peerConnectionFactory: PeerConnectionFactory =
        WebRtcSharedFactory.acquire(context.applicationContext)
    private val peerConnection: PeerConnection
    private val pendingRemoteCandidates = CopyOnWriteArrayList<IceCandidate>()
    private val pendingSdpWait = PendingSdpWait()
    private var localIceListener: ((String) -> Unit)? = null
    @Volatile
    private var released = false
    @Volatile
    private var remoteDescriptionApplied = false
    @Volatile
    private var iceConnectionStateName = "NEW"
    @Volatile
    private var remoteVideoTrack: VideoTrack? = null
    @Volatile
    private var onRemoteVideoTrack: ((VideoTrack) -> Unit)? = null
    private var localVideoTrack: VideoTrack? = null
    private val appContext = context.applicationContext

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
                        iceConnectionStateName = state.name
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
                    override fun onAddTrack(
                        receiver: org.webrtc.RtpReceiver,
                        mediaStreams: Array<out org.webrtc.MediaStream>
                    ) {
                        attachRemoteVideo(receiver.track())
                    }
                    override fun onTrack(transceiver: RtpTransceiver) {
                        if (transceiver.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO) {
                            attachRemoteVideo(transceiver.receiver.track())
                        }
                    }
                }
            )
        ) { "Failed to create video PeerConnection" }
        if (role == VideoPcRole.PUBLISHER) {
            val track = SharedLocalVideo.acquireLocalTrack(appContext, peerConnectionFactory)
            if (track != null) {
                localVideoTrack = track
                peerConnection.addTrack(track, listOf("tb_video"))
                SharedLocalVideo.notePeerAttached()
            } else {
                peerConnection.addTransceiver(
                    MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
                    RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY)
                )
            }
        } else {
            peerConnection.addTransceiver(
                MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
                RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY)
            )
        }
    }

    override fun createOffer(iceRestart: Boolean): String {
        if (released) return ""
        pendingSdpWait.begin()
        val constraints = videoConstraints(iceRestart)
        createLocalDescription(
            createAction = { observer -> peerConnection.createOffer(observer, constraints) },
            setAction = { observer, desc -> peerConnection.setLocalDescription(observer, desc) },
        )
        return currentLocalSdp()
    }

    override fun applyRemoteOffer(sdp: String, polite: Boolean): String {
        if (released) return ""
        pendingSdpWait.begin()
        val remote = SessionDescription(SessionDescription.Type.OFFER, sdp)
        awaitSetDescription { observer -> peerConnection.setRemoteDescription(observer, remote) }
        drainPendingIce()
        val constraints = videoConstraints(iceRestart = false)
        createLocalDescription(
            createAction = { observer -> peerConnection.createAnswer(observer, constraints) },
            setAction = { observer, desc -> peerConnection.setLocalDescription(observer, desc) },
        )
        return currentLocalSdp()
    }

    override fun applyRemoteAnswer(sdp: String, polite: Boolean) {
        if (released) return
        pendingSdpWait.begin()
        val remote = SessionDescription(SessionDescription.Type.ANSWER, sdp)
        awaitSetDescription { observer -> peerConnection.setRemoteDescription(observer, remote) }
        drainPendingIce()
    }

    override fun rollbackNegotiation() = Unit

    override fun addIceCandidate(candidate: String) {
        if (released) return
        val ice = decodeIceCandidate(candidate) ?: return
        if (!remoteDescriptionApplied) {
            pendingRemoteCandidates.add(ice)
            return
        }
        runCatching { peerConnection.addIceCandidate(ice) }
    }

    override fun startCapture() {
        if (role != VideoPcRole.PUBLISHER) return
        localVideoTrack?.setEnabled(true)
    }

    override fun stopCapture() {
        localVideoTrack?.setEnabled(false)
    }

    override fun setMuted(muted: Boolean) {
        setVideoSendEnabled(!muted)
    }

    override fun setVideoSendEnabled(enabled: Boolean) {
        localVideoTrack?.setEnabled(enabled)
        SharedLocalVideo.setSendEnabled(enabled)
    }

    override fun setVideoMaxBitrateBps(bps: Int) {
        if (released) return
        peerConnection.senders.forEach { sender ->
            if (sender.track()?.kind() != MediaStreamTrack.VIDEO_TRACK_KIND) return@forEach
            val params = sender.parameters ?: return@forEach
            if (params.encodings.isNullOrEmpty()) return@forEach
            params.encodings.forEach { encoding ->
                encoding.maxBitrateBps = if (bps > 0) bps else null
            }
            runCatching { sender.parameters = params }
        }
    }

    override fun setOnRemoteVideoTrack(listener: ((VideoTrack) -> Unit)?) {
        onRemoteVideoTrack = listener
        remoteVideoTrack?.let { listener?.invoke(it) }
    }
    override fun refreshAudioLevel() = Unit
    override fun inboundAudioLevel(): Float = 0f
    override fun outboundAudioLevel(): Float = 0f
    override fun iceConnectionState(): String = iceConnectionStateName
    override fun diagnosticPeerConnectionHash(): Int = System.identityHashCode(peerConnection)

    override fun release() {
        if (released) return
        released = true
        iceConnectionStateName = "CLOSED"
        pendingSdpWait.abort()
        pendingRemoteCandidates.clear()
        remoteVideoTrack = null
        onRemoteVideoTrack = null
        if (role == VideoPcRole.PUBLISHER && localVideoTrack != null) {
            SharedLocalVideo.notePeerDetached()
        }
        localVideoTrack = null
        runCatching { peerConnection.close() }
        runCatching { peerConnection.dispose() }
        WebRtcSharedFactory.release(observedId)
    }

    private fun attachRemoteVideo(track: MediaStreamTrack?) {
        val video = track as? VideoTrack ?: return
        remoteVideoTrack = video
        TalkbackLog.i("VIDEO_REMOTE_TRACK attached id=${video.id()} observed=$observedId")
        onRemoteVideoTrack?.invoke(video)
    }

    private fun videoConstraints(iceRestart: Boolean): MediaConstraints =
        MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
            mandatory.add(
                MediaConstraints.KeyValuePair(
                    "OfferToReceiveVideo",
                    if (role == VideoPcRole.VIEWER) "true" else "false"
                )
            )
            if (iceRestart) {
                mandatory.add(MediaConstraints.KeyValuePair("IceRestart", "true"))
            }
        }

    private fun drainPendingIce() {
        remoteDescriptionApplied = true
        pendingRemoteCandidates.forEach { runCatching { peerConnection.addIceCandidate(it) } }
        pendingRemoteCandidates.clear()
    }

    private fun currentLocalSdp(): String {
        if (released) return ""
        return peerConnection.localDescription?.description.orEmpty()
    }

    private fun createLocalDescription(
        createAction: (SdpObserver) -> Unit,
        setAction: (SdpObserver, SessionDescription) -> Unit,
    ) {
        val created = AtomicReference<SessionDescription>()
        val createLatch = CountDownLatch(1)
        val createError = AtomicReference<String>()
        createAction(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription?) {
                if (desc != null) created.set(desc) else createError.set("null SDP")
                createLatch.countDown()
            }
            override fun onSetSuccess() = Unit
            override fun onCreateFailure(error: String?) {
                createError.set(error ?: "create failure")
                createLatch.countDown()
            }
            override fun onSetFailure(error: String?) = Unit
        })
        pendingSdpWait.await(createLatch, "Timed out creating video SDP")
        createError.get()?.let { error(it) }
        val desc = created.get() ?: error("Missing video SDP")
        awaitSetDescription { observer -> setAction(observer, desc) }
    }

    private fun awaitSetDescription(action: (SdpObserver) -> Unit) {
        val latch = CountDownLatch(1)
        val setError = AtomicReference<String>()
        val observer = object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription?) = Unit
            override fun onSetSuccess() {
                latch.countDown()
            }
            override fun onCreateFailure(error: String?) = Unit
            override fun onSetFailure(error: String?) {
                setError.set(error ?: "set failure")
                latch.countDown()
            }
        }
        WebRtcSharedFactory.withSrdApplyLock {
            action(observer)
            pendingSdpWait.await(latch, "Timed out setting video SDP")
        }
        setError.get()?.let { error(it) }
    }

    private fun encodeIceCandidate(candidate: IceCandidate): String =
        listOf(candidate.sdpMid ?: "", candidate.sdpMLineIndex.toString(), candidate.sdp).joinToString("|")

    private fun decodeIceCandidate(raw: String): IceCandidate? {
        val parts = raw.split("|", limit = 3)
        if (parts.size != 3) return null
        val lineIndex = parts[1].toIntOrNull() ?: return null
        return IceCandidate(parts[0].ifEmpty { null }, lineIndex, parts[2])
    }
}
