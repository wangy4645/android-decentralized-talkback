package com.talkback.core.webrtc

import android.content.Context
import com.talkback.core.session.ConferenceRealizationLineage
import com.talkback.core.session.ConferenceSrdNativeObservability
import com.talkback.core.media.MediaObservabilityLog
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
    private val observedModuleId: String? = null,
    private val onIceConnectionState: ((String) -> Unit)? = null
) : WebRtcAudioEngine {
    private val appContext = context.applicationContext
    private val peerConnectionFactory: PeerConnectionFactory = WebRtcSharedFactory.acquire(appContext)
    private val peerConnection: PeerConnection
    private val localAudioTrack: AudioTrack
    private val remoteAudioTracks = CopyOnWriteArrayList<AudioTrack>()
    private val pendingRemoteCandidates = CopyOnWriteArrayList<IceCandidate>()
    private val capturing = AtomicBoolean(false)
    private val pendingSdpWait = PendingSdpWait()
    private val signalingTxn = PcSignalingTransaction()
    private val iceIngressDuringSrd = AtomicBoolean(false)
    @Volatile
    private var outstandingOfferStartedAtNs: Long? = null
    @Volatile
    private var outstandingOfferLineageId: String? = null
    @Volatile
    private var lastAppliedRemoteIceUfrag: String? = null
    @Volatile
    private var remoteDescriptionApplied = false
    private var localIceListener: ((String) -> Unit)? = null
    @Volatile
    private var released = false
    @Volatile
    private var remotePlaybackEnabled = false
    override var playbackDiagnosticTag: String? = null
    override var remoteTrackDiagnosticLogger: ((Boolean) -> Unit)? = null
    override var transportDiagnosticOfferLineageId: String? = null
    override var transportDiagnosticPcGeneration: Long? = null
    @Volatile
    private var inboundLevel = 0f
    @Volatile
    private var outboundLevel = 0f
    @Volatile
    private var iceConnectionStateName = "NEW"
    @Volatile
    private var lastIceStatsBoundary: String? = null
    @Volatile
    private var negotiationSettlingState = NegotiationSettling.NONE
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
                    override fun onSignalingChange(state: PeerConnection.SignalingState) {
                        logNegotiation(
                            "op=SIGNALING_STATE signalingState=${state.name} " +
                                "iceConnectionState=$iceConnectionStateName"
                        )
                    }
                    override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                        iceConnectionStateName = state.name
                        if (state.name in ICE_STATS_BOUNDARIES && lastIceStatsBoundary != state.name) {
                            lastIceStatsBoundary = state.name
                            captureIceTransportStatsAtBoundary(state.name)
                        }
                        onIceConnectionState?.invoke(state.name)
                    }
                    override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
                    override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit
                    override fun onIceCandidate(candidate: IceCandidate) {
                        val wire = encodeIceCandidate(candidate)
                        logLocalCandidateGenerated(wire)
                        localIceListener?.invoke(wire)
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
        SharedLocalAudio.notePeerAttached()
        localAudioTrack.setEnabled(false)
    }

    private fun warnJni(op: String) {
        WebRtcJniThreadGuard.warnIfCoordinator(op)
    }

    private fun diagnosticEdgeKey(): String {
        val tag = playbackDiagnosticTag ?: return observedModuleId ?: "unknown"
        return ConferenceSrdNativeObservability.edgeKeyFromDiagnosticTag(tag) ?: tag
    }

    /**
     * Runs [block] inside this PeerConnection's signaling transaction while holding the
     * shared-factory fence, so the whole transaction — not just the SRD native call — is the
     * unit of mutual exclusion across edges.
     */
    private fun <T> inSignalingTransaction(
        op: SignalingOp,
        onRejected: () -> T,
        block: () -> T
    ): T {
        val observation = NativeSignalingOverlapObserver.observeRequest(
            op = op,
            edgeKey = diagnosticEdgeKey(),
            pcHash = System.identityHashCode(peerConnection),
            pcGeneration = transportDiagnosticPcGeneration,
            transaction = signalingTxn,
        )
        return signalingTxn.runOrReject(
            op = op,
            onRejected = { admission ->
                observation.rejected(admission)
                onRejected()
            },
        ) {
            WebRtcSharedFactory.withSrdApplyLock {
                observation.admitted()
                block()
            }
        }
    }

    override fun createOffer(iceRestart: Boolean): String = inSignalingTransaction(
        op = SignalingOp.CREATE_OFFER,
        onRejected = { currentLocalSdpOrEmpty() },
    ) { createOfferInTransaction(iceRestart) }

    private fun createOfferInTransaction(iceRestart: Boolean): String {
        warnJni("createOffer")
        pendingSdpWait.begin()
        // INV-NEG-001: must NOT clear Answerer settling here (createOffer self-lock / skip commit).
        // Settling clears only via commitAnswererTransaction / rollback / offerer answer path.
        val before = negotiationSnapshot()
        logNegotiation(
            "op=ROLE role=OFFERER reason=createOffer iceRestart=$iceRestart " +
                before.formatFields()
        )
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
            if (iceRestart) {
                mandatory.add(MediaConstraints.KeyValuePair("IceRestart", "true"))
            }
        }
        createLocalDescription(
            createAction = { observer -> peerConnection.createOffer(observer, constraints) },
            setAction = { observer, desc -> peerConnection.setLocalDescription(observer, desc) },
            setOp = "SLD"
        )
        outstandingOfferStartedAtNs = System.nanoTime()
        outstandingOfferLineageId = transportDiagnosticOfferLineageId
        iceIngressDuringSrd.set(false)
        return currentLocalSdp()
    }

    override fun applyRemoteOffer(sdp: String, polite: Boolean): String = inSignalingTransaction(
        op = SignalingOp.APPLY_REMOTE_OFFER,
        onRejected = { currentLocalSdpOrEmpty() },
    ) { applyRemoteOfferInTransaction(sdp, polite) }

    private fun applyRemoteOfferInTransaction(sdp: String, polite: Boolean): String {
        warnJni("applyRemoteOffer")
        pendingSdpWait.begin()
        val before = negotiationSnapshot()
        logNegotiation(
            "op=ROLE role=ANSWERER reason=applyRemoteOffer polite=$polite " +
                before.formatFields()
        )
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
        awaitSetDescription(
            op = "SRD",
            type = "OFFER"
        ) { observer -> peerConnection.setRemoteDescription(observer, remote) }
        markRemoteDescriptionApplied()
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
        }
        createLocalDescription(
            createAction = { observer -> peerConnection.createAnswer(observer, constraints) },
            setAction = { observer, desc -> peerConnection.setLocalDescription(observer, desc) },
            setOp = "SLD"
        )
        // State fact: Answerer remote-offer convergence completed (SRD OFFER + SLD ANSWER).
        markNegotiationSettledAsAnswerer()
        return currentLocalSdp()
    }

    override fun applyRemoteAnswer(sdp: String, polite: Boolean) = inSignalingTransaction(
        op = SignalingOp.APPLY_REMOTE_ANSWER,
        onRejected = { },
    ) { applyRemoteAnswerInTransaction(sdp, polite) }

    private fun applyRemoteAnswerInTransaction(sdp: String, polite: Boolean) {
        warnJni("applyRemoteAnswer")
        pendingSdpWait.begin()
        val before = negotiationSnapshot()
        logNegotiation(
            "op=ROLE role=OFFERER reason=applyRemoteAnswer polite=$polite " +
                before.formatFields()
        )
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
        awaitSetDescription(
            op = "SRD",
            type = "ANSWER",
            incomingSdp = sdp
        ) { observer -> peerConnection.setRemoteDescription(observer, remote) }
        markRemoteDescriptionApplied()
        // Next stable negotiation completion as Offerer clears Answerer settling.
        clearNegotiationSettling()
    }

    override fun abortPendingNegotiation() {
        pendingSdpWait.abort()
    }

    override fun rollbackNegotiation() = inSignalingTransaction(
        op = SignalingOp.ROLLBACK,
        onRejected = { },
    ) { rollbackNegotiationInTransaction() }

    private fun rollbackNegotiationInTransaction() {
        warnJni("rollbackNegotiation")
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
        // Explicit rollback clears Answerer settling fact.
        clearNegotiationSettling()
    }

    override fun addIceCandidate(candidate: String) {
        warnJni("addIceCandidate")
        if (released) return
        val ice = decodeIceCandidate(candidate) ?: return
        if (signalingTxn.wouldQueue()) {
            iceIngressDuringSrd.set(true)
        }
        // The applied/queued branch is decided inside the transaction: outside it, the drain
        // could flip remoteDescriptionApplied between the check and the enqueue and strand
        // this candidate forever.
        inSignalingTransaction(
            op = SignalingOp.ADD_ICE_CANDIDATE,
            onRejected = { logCandidateApplied(candidate, queued = false, rejected = true) },
        ) {
            if (!remoteDescriptionApplied) {
                pendingRemoteCandidates.add(ice)
                logCandidateApplied(candidate, queued = true)
            } else {
                logCandidateApplied(candidate, queued = false)
                peerConnection.addIceCandidate(ice)
            }
        }
    }

    override fun startCapture() {
        warnJni("startCapture")
        capturing.set(true)
        applyCaptureEnabled(true)
    }

    override fun stopCapture() {
        warnJni("stopCapture")
        capturing.set(false)
        applyCaptureEnabled(false)
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
        warnJni("setMuted")
        if (released) return
        if (muted) {
            localAudioTrack.setEnabled(false)
            programAudioTrack?.setEnabled(false)
        } else if (capturing.get()) {
            applyCaptureEnabled(true)
        }
    }

    override fun setRemotePlaybackEnabled(enabled: Boolean) {
        warnJni("setRemotePlaybackEnabled")
        if (released) return
        remotePlaybackEnabled = enabled
        remoteAudioTracks.forEach { track -> track.setEnabled(enabled) }
    }

    override fun isRemotePlaybackEnabled(): Boolean = remotePlaybackEnabled

    override fun setProgramRelayMode(mode: ProgramRelayMode) {
        warnJni("setProgramRelayMode")
        programRelayMode = mode
        if (mode == ProgramRelayMode.PROGRAM) {
            ensureProgramTrack()
        }
        if (capturing.get()) {
            applyCaptureEnabled(true)
        }
    }

    override fun setInboundPcmSink(sink: InboundPcmSink?) {
        warnJni("setInboundPcmSink")
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
        warnJni("feedProgramPcm")
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

    override fun programSenderSnapshot(): ProgramSenderSnapshot? {
        warnJni("programSenderSnapshot")
        if (released) return ProgramSenderSnapshot.NONE
        return runCatching {
            val programId = programAudioTrack?.id() ?: "none"
            val micId = localAudioTrack.id()
            val audioSenders = peerConnection.senders.filter { sender ->
                sender.track()?.kind() == MediaStreamTrack.AUDIO_TRACK_KIND
            }
            val sender = audioSenders.firstOrNull { it.track()?.id() == programId }
                ?: audioSenders.firstOrNull { it.track()?.id() == micId }
                ?: audioSenders.firstOrNull()
            val track = sender?.track()
            ProgramSenderSnapshot(
                senderId = sender?.id() ?: "none",
                currentTrackId = track?.id() ?: "none",
                expectedTrackId = programId,
                enabled = track?.enabled() == true,
                readyState = track?.state()?.name ?: "none",
                lastReplaceAt = "none"
            )
        }.getOrElse { ProgramSenderSnapshot.NONE }
    }

    override fun release() {
        warnJni("release")
        if (released) {
            MediaObservabilityLog.pcCloseSkipped(observedModuleId ?: "unknown", "alreadyReleased")
            return
        }
        // Abort first: the close fence waits for the in-flight transaction to drain, and an SRD
        // blocked on its native callback must not hold destruction for the full SDP timeout.
        pendingSdpWait.abort()
        signalingTxn.close {
            WebRtcSharedFactory.withSrdApplyLock { releaseInTransaction() }
        }
    }

    private fun releaseInTransaction() {
        if (released) {
            MediaObservabilityLog.pcCloseSkipped(observedModuleId ?: "unknown", "alreadyReleased")
            return
        }
        released = true
        val moduleTag = observedModuleId ?: "unknown"
        iceConnectionStateName = "CLOSED"
        clearNegotiationSettling()
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
        MediaObservabilityLog.pcCloseEnter(moduleTag)
        runCatching { peerConnection.close() }
        runCatching { peerConnection.dispose() }
        MediaObservabilityLog.pcCloseExit(moduleTag)
        SharedLocalAudio.notePeerDetached()
        WebRtcSharedFactory.release(moduleTag)
    }

    override fun negotiationSettling(): NegotiationSettling = negotiationSettlingState

    override fun commitAnswererTransaction(): Boolean {
        if (negotiationSettlingState != NegotiationSettling.ANSWERER_SETTLED) return false
        negotiationSettlingState = NegotiationSettling.NONE
        logNegotiation("op=SETTLING state=NONE reason=ANSWERER_TRANSACTION_COMMITTED")
        return true
    }

    private fun markNegotiationSettledAsAnswerer() {
        negotiationSettlingState = NegotiationSettling.ANSWERER_SETTLED
        logNegotiation("op=SETTLING state=ANSWERER_SETTLED reason=applyRemoteOfferComplete")
    }

    private fun clearNegotiationSettling() {
        if (negotiationSettlingState == NegotiationSettling.NONE) return
        negotiationSettlingState = NegotiationSettling.NONE
        logNegotiation("op=SETTLING state=NONE reason=cleared")
    }

    override fun refreshAudioLevel() {
        warnJni("refreshAudioLevel")
        if (released) return
        val edgeKey = diagnosticEdgeKey()
        val pcHash = System.identityHashCode(peerConnection)
        if (NativeSignalingOverlapObserver.shouldDeferGetStats()) {
            NativeSignalingOverlapObserver.logGetStatsDeferred(
                edgeKey = edgeKey,
                pcHash = pcHash,
                pcGeneration = transportDiagnosticPcGeneration,
            )
            return
        }
        peerConnection.getStats { report -> applyStatsReport(report) }
    }

    override fun inboundAudioLevel(): Float = inboundLevel

    override fun outboundAudioLevel(): Float = outboundLevel

    override fun iceConnectionState(): String = iceConnectionStateName

    override fun diagnosticPeerConnectionHash(): Int? =
        if (released) null else System.identityHashCode(peerConnection)

    override fun diagnosticIceUfrags(): Pair<String?, String?> = credentialUfrags()

    override fun negotiationSnapshot(): NegotiationPcSnapshot {
        warnJni("negotiationSnapshot")
        if (released) {
            return NegotiationPcSnapshot(
                signalingState = "CLOSED",
                iceConnectionState = iceConnectionStateName,
                connectionState = "CLOSED"
            )
        }
        return NegotiationPcSnapshot(
            signalingState = peerConnection.signalingState()?.name ?: "UNKNOWN",
            iceConnectionState = iceConnectionStateName,
            connectionState = runCatching { peerConnection.connectionState()?.name }.getOrNull()
                ?: "UNKNOWN",
            localDescriptionType = peerConnection.localDescription?.type?.name,
            remoteDescriptionType = peerConnection.remoteDescription?.type?.name
        )
    }

    private fun logNegotiation(fields: String) {
        val tag = playbackDiagnosticTag ?: "unknown"
        TalkbackLog.i("WEBRTC_NEGOTIATION tag=$tag $fields${ConferenceRealizationLineage.negotiationSuffix()}")
    }

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

    /**
     * Must run inside the SRD transaction: flipping the flag and draining the candidates it
     * unblocks is a single step, otherwise a concurrent addIceCandidate sees the flag already
     * true while the drain has not reached its entry yet.
     */
    private fun markRemoteDescriptionApplied() {
        check(signalingTxn.isActive()) { "ICE drain must run inside the signaling transaction" }
        remoteDescriptionApplied = true
        lastAppliedRemoteIceUfrag = credentialUfrags().second
        val drained = pendingRemoteCandidates.size
        pendingRemoteCandidates.forEach { candidate ->
            runCatching { peerConnection.addIceCandidate(candidate) }
        }
        pendingRemoteCandidates.clear()
        TalkbackLog.i(
            "SRD_ICE_DRAIN edgeKey=${diagnosticEdgeKey()} drained=$drained " +
                "pcHash=${System.identityHashCode(peerConnection)} " +
                "transactionOwner=${signalingTxn.transactionOwner() ?: "NONE"}"
        )
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
        // Production AAR lacks AudioTrack.nativeWrapSink; uncaught ULE on signaling_thread
        // aborts WebRTC via JNI ExceptionCheck (M01 field: SIGABRT during SRD/onTrack).
        try {
            audioTrack.addSink { audioData, bitsPerSample, sampleRate, numberOfChannels, numberOfFrames, _ ->
                sink.onPcm(audioData, bitsPerSample, sampleRate, numberOfChannels, numberOfFrames)
            }
        } catch (e: UnsatisfiedLinkError) {
            TalkbackLog.w(
                "INBOUND_SINK_UNAVAILABLE track=${audioTrack.id()} " +
                    "tag=${playbackDiagnosticTag ?: "unknown"} reason=${e.message}"
            )
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
        if (released) error("PeerConnection released; no local SDP")
        return peerConnection.localDescription?.description
            ?: error("Missing local SDP after negotiation")
    }

    /**
     * Never touch the native PC once released: getLocalDescription on a closed PC is a
     * use-after-free (field SIGSEGV/SIGBUS 2026-09-24, fault addr == "PC_CLOSE").
     * Reached via inSignalingTransaction onRejected when admission == REJECTED_CLOSED.
     */
    private fun currentLocalSdpOrEmpty(): String {
        if (released) return ""
        return runCatching { peerConnection.localDescription?.description }.getOrNull() ?: ""
    }

    private fun createLocalDescription(
        createAction: (SdpObserver) -> Unit,
        setAction: (SdpObserver, SessionDescription) -> Unit,
        setOp: String
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

        pendingSdpWait.await(createLatch, "Timed out creating SDP")
        createError.get()?.let { error(it) }
        val desc = created.get() ?: error("Missing SDP after create")

        awaitSetDescription(
            op = setOp,
            type = desc.type.name
        ) { observer -> setAction(observer, desc) }
        return desc
    }

    private fun awaitSetDescription(
        op: String,
        type: String,
        incomingSdp: String? = null,
        action: (SdpObserver) -> Unit
    ) {
        val before = negotiationSnapshot()
        logNegotiation(
            "op=$op type=$type phase=BEFORE ${before.formatFields()}"
        )
        if (op == "SRD" && type == "ANSWER") {
            logSrdBoundary("SRD_ENTER", type)
        }
        val latch = CountDownLatch(1)
        val setError = AtomicReference<String>()
        val observerHash = AtomicReference<Int>()
        val observer = object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription?) = Unit
            override fun onSetSuccess() {
                if (op == "SRD" && type == "ANSWER") {
                    logSrdBoundary(
                        "SRD_CALLBACK_ENTER",
                        type,
                        observerHash = System.identityHashCode(this)
                    )
                    logSrdBoundary("SRD_EXIT", type, result = "SUCCESS")
                }
                latch.countDown()
            }

            override fun onCreateFailure(error: String?) = Unit
            override fun onSetFailure(error: String?) {
                if (op == "SRD" && type == "ANSWER") {
                    logSrdBoundary(
                        "SRD_CALLBACK_FAILURE",
                        type,
                        observerHash = System.identityHashCode(this),
                        error = error ?: "Unknown set failure"
                    )
                    logSrdBoundary("SRD_EXIT", type, result = "FAILURE")
                }
                setError.set(error ?: "Unknown set failure")
                latch.countDown()
            }
        }
        observerHash.set(System.identityHashCode(observer))
        if (op == "SRD" && type == "ANSWER") {
            logSrdBoundary("SRD_MUTEX_WAIT_BEGIN", type)
            val waitStartNs = System.nanoTime()
            WebRtcSharedFactory.withSrdApplyLock {
                val acquiredNs = System.nanoTime()
                logSrdBoundary(
                    "SRD_MUTEX_ACQUIRED",
                    type,
                    waitMs = TimeUnit.NANOSECONDS.toMillis(acquiredNs - waitStartNs),
                )
                try {
                    val nativeStartNs = System.nanoTime()
                    val admission = evaluateSrdPreNativeSnapshot(type, incomingSdp)
                    if (!admission.admit) {
                        val detail = admission.violations.joinToString(",")
                        logSrdBoundary(
                            "SRD_NATIVE_REJECTED",
                            type,
                            error = detail,
                        )
                        setError.set("SRD_ADMISSION_REJECTED:$detail")
                        latch.countDown()
                    } else {
                        NativeSignalingOverlapObserver.beginSrd(
                            diagnosticEdgeKey(),
                            System.identityHashCode(peerConnection),
                        )
                        logSrdBoundary("SRD_NATIVE_CALL_ENTER", type)
                        try {
                            action(observer)
                            val nativeElapsedMs =
                                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - nativeStartNs)
                            logSrdBoundary("SRD_NATIVE_CALL_EXIT", type, elapsedMs = nativeElapsedMs)
                            pendingSdpWait.await(latch, "Timed out setting SDP")
                            logSrdBoundary(
                                "SRD_JNI_RETURN",
                                type,
                                observerHash = observerHash.get(),
                            )
                        } finally {
                            NativeSignalingOverlapObserver.endSrd(
                                System.identityHashCode(peerConnection),
                            )
                        }
                    }
                } finally {
                    logSrdBoundary(
                        "SRD_MUTEX_RELEASED",
                        type,
                        holdMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - acquiredNs),
                    )
                }
            }
        } else {
            invokeNativeSet(op, type) { action(observer) }
            pendingSdpWait.await(latch, "Timed out setting SDP")
        }
        setError.get()?.let { error(it) }
        val after = negotiationSnapshot()
        logNegotiation(
            "op=$op type=$type phase=AFTER ${after.formatFields()}"
        )
        logCredentialBoundary(op, type)
    }

    /**
     * Non-answer SDP paths take no fence of their own: they already run inside the enclosing
     * signaling transaction, which holds both the per-PC queue and the shared-factory fence.
     */
    private fun invokeNativeSet(op: String, type: String, nativeCall: () -> Unit) {
        nativeCall()
    }

    /**
     * Step 2b probe A+B: last observation before native setRemoteDescription(ANSWER).
     * Returns the admission decision; caller MUST NOT enter native when [SrdAdmissionDecision.admit]
     * is false (MC4 field: STALE_OR_MISMATCHED_ANSWER → SIGABRT).
     */
    private fun evaluateSrdPreNativeSnapshot(
        type: String,
        incomingSdp: String?,
    ): SrdAdmissionDecision {
        val tag = playbackDiagnosticTag ?: "unknown"
        val snapshot = negotiationSnapshot()
        val thread = Thread.currentThread()
        val localCreds = IceTransportDiagnostic.extractIceCredentials(
            peerConnection.localDescription?.description
        )
        val answerCreds = IceTransportDiagnostic.extractIceCredentials(incomingSdp)
        val offerAgeMs = outstandingOfferStartedAtNs
            ?.let { TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - it) } ?: -1L
        val decision = SrdAdmissionDecision.evaluate(
            SrdAdmissionInput(
                signalingState = snapshot.signalingState,
                localDescriptionType = snapshot.localDescriptionType,
                remoteDescriptionType = snapshot.remoteDescriptionType,
                expectedPcGeneration = transportDiagnosticPcGeneration,
                actualPcGeneration = transportDiagnosticPcGeneration,
                outstandingOfferLineageId = outstandingOfferLineageId,
                answerOfferLineageId = transportDiagnosticOfferLineageId,
                latestAdmittedTaskId = null,
                taskId = null,
                localOfferIceUfrag = localCreds.iceUfrag,
                answerIceUfrag = answerCreds.iceUfrag,
                answerIcePwdFingerprint = answerCreds.icePwdFingerprint,
                previouslyAppliedRemoteIceUfrag = lastAppliedRemoteIceUfrag,
                remoteDescriptionAlreadyApplied = remoteDescriptionApplied,
                outstandingOfferAgeMs = offerAgeMs,
                queuedIceCount = pendingRemoteCandidates.size,
                iceIngressDuringSrd = iceIngressDuringSrd.get(),
            )
        )
        TalkbackLog.i(
            "SRD_PRE_NATIVE_SNAPSHOT type=$type " +
                ConferenceSrdNativeObservability.correlationFieldsFromTag(tag) + " " +
                snapshot.formatFields() + " " +
                "pcHash=${System.identityHashCode(peerConnection)} " +
                "factoryHash=${System.identityHashCode(peerConnectionFactory)} " +
                "attachedPcCount=${SharedLocalAudio.attachedPcCount()} " +
                "transactionOwner=${signalingTxn.transactionOwner() ?: "NONE"} " +
                "transactionState=${signalingTxn.lifecycleState().name} " +
                "foreignSrdInFlight=${NativeSignalingOverlapObserver.foreignSrdInFlight()?.edgeKey ?: "NONE"} " +
                "localIceUfrag=${localCreds.iceUfrag ?: "NONE"} " +
                "answerIceUfrag=${answerCreds.iceUfrag ?: "NONE"} " +
                "answerIcePwdFingerprint=${answerCreds.icePwdFingerprint ?: "NONE"} " +
                "thread=${thread.name} tid=${thread.id} " +
                decision.formatFields() +
                ConferenceRealizationLineage.negotiationSuffix()
        )
        return decision
    }

    private fun logSrdBoundary(
        event: String,
        type: String,
        result: String? = null,
        observerHash: Int? = null,
        error: String? = null,
        waitMs: Long? = null,
        holdMs: Long? = null,
        elapsedMs: Long? = null
    ) {
        val thread = Thread.currentThread()
        val tag = playbackDiagnosticTag ?: "unknown"
        val parts = tag.split("|", limit = 2)
        val conferenceId = parts.getOrNull(0) ?: "unknown"
        val remote = parts.getOrNull(1) ?: "unknown"
        ConferenceSrdNativeObservability.recordFromTag(tag, event)
        if (event == "SRD_NATIVE_CALL_EXIT" && elapsedMs != null) {
            ConferenceSrdNativeObservability.edgeKeyFromDiagnosticTag(tag)?.let { edgeKey ->
                ConferenceSrdNativeObservability.recordNativeCallExit(edgeKey, elapsedMs)
            }
        }
        val snapshot = if (released) {
            NegotiationPcSnapshot(signalingState = "CLOSED")
        } else {
            negotiationSnapshot()
        }
        val pcHash = System.identityHashCode(peerConnection)
        val resultField = result?.let { " result=$it" } ?: ""
        val observerField = observerHash?.let { " observerHash=$it" } ?: ""
        val errorField = error?.let { " error=$it" } ?: ""
        val waitField = waitMs?.let { " waitMs=$it" } ?: ""
        val holdField = holdMs?.let { " holdMs=$it" } ?: ""
        val elapsedField = elapsedMs?.let { " elapsedMs=$it" } ?: ""
        TalkbackLog.i(
            "$event type=$type " +
                ConferenceSrdNativeObservability.correlationFieldsFromTag(tag) + " " +
                snapshot.formatFields() + " " +
                "pcHash=$pcHash " +
                "factoryHash=${System.identityHashCode(peerConnectionFactory)} " +
                "thread=${thread.name} tid=${thread.id}$observerField$resultField$errorField" +
                "$waitField$holdField$elapsedField " +
                "trackId=${SharedLocalAudio.trackId()} " +
                "attachedPcCount=${SharedLocalAudio.attachedPcCount()}" +
                ConferenceRealizationLineage.negotiationSuffix()
        )
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

    private fun logLocalCandidateGenerated(wire: String) {
        val peer = observedModuleId ?: diagnosticPeerFromTag()
        val (localUfrag, remoteUfrag) = credentialUfrags()
        IceTransportDiagnostic.logCandidate(
            seam = IceTransportDiagnostic.CandidateSeam.GENERATED,
            peer = peer,
            offerLineageId = transportDiagnosticOfferLineageId,
            pcGeneration = transportDiagnosticPcGeneration,
            pcHash = diagnosticPeerConnectionHash(),
            wire = wire,
            localUfrag = localUfrag,
            remoteUfrag = remoteUfrag,
        )
    }

    private fun logCandidateApplied(wire: String, queued: Boolean, rejected: Boolean = false) {
        if (rejected) {
            TalkbackLog.w(
                "ICE_CANDIDATE_REJECTED_CLOSING edgeKey=${diagnosticEdgeKey()} " +
                    "state=${signalingTxn.lifecycleState().name}"
            )
            return
        }
        val peer = observedModuleId ?: diagnosticPeerFromTag()
        val (localUfrag, remoteUfrag) = credentialUfrags()
        IceTransportDiagnostic.logCandidate(
            seam = IceTransportDiagnostic.CandidateSeam.APPLY,
            peer = peer,
            offerLineageId = transportDiagnosticOfferLineageId,
            pcGeneration = transportDiagnosticPcGeneration,
            pcHash = diagnosticPeerConnectionHash(),
            wire = wire,
            queued = queued,
            localUfrag = localUfrag,
            remoteUfrag = remoteUfrag,
        )
    }

    private fun logCredentialBoundary(op: String, descriptionType: String) {
        val sdp =
            when (op) {
                "SLD" -> peerConnection.localDescription?.description
                "SRD" ->
                    when (descriptionType) {
                        "OFFER" -> peerConnection.remoteDescription?.description
                        "ANSWER" -> peerConnection.remoteDescription?.description
                        else -> peerConnection.remoteDescription?.description
                    }
                else -> null
            }
        IceTransportDiagnostic.logCredentialBoundary(
            op = op,
            descriptionType = descriptionType,
            peer = observedModuleId ?: diagnosticPeerFromTag(),
            offerLineageId = transportDiagnosticOfferLineageId,
            pcGeneration = transportDiagnosticPcGeneration,
            pcHash = diagnosticPeerConnectionHash(),
            sdp = sdp,
        )
    }

    private fun captureIceTransportStatsAtBoundary(boundary: String) {
        if (NativeSignalingOverlapObserver.shouldDeferGetStats()) return
        if (released) return
        IceTransportDiagnostic.captureStatsAtIceBoundary(
            peerConnection,
            playbackDiagnosticTag,
            boundary,
        )
    }

    private fun diagnosticPeerFromTag(): String =
        playbackDiagnosticTag?.split("|")?.getOrNull(1) ?: "unknown"

    private fun credentialUfrags(): Pair<String?, String?> {
        val local = peerConnection.localDescription?.description
            ?.let { IceTransportDiagnostic.extractIceCredentials(it).iceUfrag }
        val remote = peerConnection.remoteDescription?.description
            ?.let { IceTransportDiagnostic.extractIceCredentials(it).iceUfrag }
        return local to remote
    }

    companion object {
        private val ICE_STATS_BOUNDARIES =
            setOf("CHECKING", "CONNECTED", "COMPLETED", "FAILED")
    }
}
