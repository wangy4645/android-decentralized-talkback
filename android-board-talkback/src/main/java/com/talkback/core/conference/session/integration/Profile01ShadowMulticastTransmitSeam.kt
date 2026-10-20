package com.talkback.core.conference.session.integration

import com.talkback.core.conference.runtime.ConcentusOpusEncoderSeam
import com.talkback.core.conference.runtime.OpusCodecConstants
import com.talkback.core.conference.session.ConferenceSessionMediaBridge
import com.talkback.core.conference.session.ConferenceSessionMediaControlFactRegistry
import com.talkback.core.conference.session.ConferenceSessionMediaFactPort
import com.talkback.core.conference.runtime.MediaGroupEndpointBinding
import com.talkback.core.conference.transport.Phase1MediaHarness
import com.talkback.core.conference.transport.SourceScopedSrtpEgress
import com.talkback.core.webrtc.LocalMicFrameSource
import com.talkback.core.webrtc.LocalOutboundPcmSink
import com.talkback.core.conference.aqc.AqcRuntime
import com.talkback.core.conference.aqc.TxProcessingChain
import com.talkback.core.webrtc.CapturePcmMeasurementPoint
import com.talkback.core.webrtc.PostApmLocalMicFrameSource
import com.talkback.core.webrtc.ProcessLocalMicFrameSource
import com.talkback.core.webrtc.conferenceaudio.ConferencePcmFormat
import com.talkback.core.webrtc.conferenceaudio.PcmFrame
import com.talkback.core.webrtc.conferenceaudio.PcmFrameAssembler
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

/**
 * PR-PA-SR4-TX TX-B — parallel PCM tap → shadow multicast egress (metrics only).
 *
 * Does not own microphone or ADR-0056 WebRTC capture.
 */
class Profile01ShadowMulticastTransmitSeam(
    private val factPort: ConferenceSessionMediaFactPort,
    private val registry: ConferenceSessionMediaControlFactRegistry,
    private val sessionIndex: MeetingProfile01ConferenceSessionIndex,
    private val localSourceAuthority: Profile01LocalConferenceSourceIdentityAuthority,
    private val hostProjection: Profile01HostLocalSessionFactProjection,
    private val localModuleId: () -> String,
    private val frameSource: LocalMicFrameSource = ProcessLocalMicFrameSource,
) {
    private data class ArmedSession(
        val releaseMic: () -> Unit,
        val assembler: PcmFrameAssembler,
        val opusEncoder: ConcentusOpusEncoderSeam = ConcentusOpusEncoderSeam(),
        val mergeLock: Any = Any(),
        var halfFrame: ShortArray? = null,
    )

    private data class EgressContext(
        val endpoint: MediaGroupEndpointBinding,
        val egress: SourceScopedSrtpEgress,
        val mediaKeyEpoch: Long,
        val sourceGeneration: Long,
    )

    private val armed = ConcurrentHashMap<String, ArmedSession>()
    private val egressBySession = ConcurrentHashMap<String, EgressContext>()

    fun onProductionCaptureStart(sessionId: String) {
        if (!MeetingProductMediaShadow.enabled) return
        try {
            hostProjection.maybeProjectHostLocalSourceMember(sessionId)
            armCaptureTap(sessionId)
        } catch (t: Throwable) {
            Profile01ShadowRuntimeObservability.logShadowTxFailed(
                sessionId = sessionId,
                reason = t.javaClass.simpleName,
                detail = t.message,
            )
        }
    }

    fun onProductionCaptureStop(sessionId: String) {
        if (!MeetingProductMediaShadow.enabled) return
        try {
            disarmCaptureTap(sessionId)
        } catch (t: Throwable) {
            Profile01ShadowRuntimeObservability.logShadowTxFailed(
                sessionId = sessionId,
                reason = t.javaClass.simpleName,
                detail = t.message,
            )
        }
    }

    fun onSessionStopped(sessionId: String) {
        disarmCaptureTap(sessionId)
        egressBySession.remove(sessionId)
    }

    /** Invalidate cached SRTP/header context after wiring media-key epoch rotation. */
    fun invalidateEgressCache(sessionId: String) {
        egressBySession.remove(sessionId)
    }

    internal fun isArmed(sessionId: String): Boolean = armed.containsKey(sessionId)

    internal fun armedSessionIds(): Set<String> = armed.keys.toSet()

    private fun armCaptureTap(sessionId: String) {
        if (armed.containsKey(sessionId)) return
        val assembler = PcmFrameAssembler()
        val sink =
            LocalOutboundPcmSink { buffer, bits, rate, channels, frames ->
                onMicPcm(sessionId, buffer, bits, rate, channels, frames)
            }
        val release =
            if (D2SendChainRuntime.postApmMuxEnabled) {
                D2ConferenceMicFrameSource.acquireForSession(sessionId, sink)
            } else {
                resolveProductionMicFrameSource().acquire(sink)
            }
        armed[sessionId] = ArmedSession(releaseMic = release, assembler = assembler)
    }

    private fun disarmCaptureTap(sessionId: String) {
        ShadowTxEncodePcmFieldCapture.onSessionCaptureEnded(sessionId)
        M01GatedTxEncodeLevelProcessor.clearSession(sessionId)
        D2S2EncodePcmObserver.clearSession(sessionId)
        D2TxPathTelemetry.clearSession(sessionId)
        armed.remove(sessionId)?.let { session ->
            session.releaseMic.invoke()
            session.opusEncoder.release()
        }
    }

    private fun onMicPcm(
        sessionId: String,
        audioData: ByteBuffer,
        bitsPerSample: Int,
        sampleRate: Int,
        numberOfChannels: Int,
        numberOfFrames: Int,
    ) {
        val state = armed[sessionId] ?: return
        if (D2SendChainRuntime.captureChainObserveEnabled && !D2SendChainRuntime.postApmMuxEnabled) {
            if (D2ConferenceMicFrameSource.isCanonicalChunk(bitsPerSample, sampleRate, numberOfChannels, numberOfFrames)) {
                D2TxPathTelemetry.record(sessionId, D2TxPathTelemetry.Event.PRE_APM_FORWARD)
            } else {
                D2TxPathTelemetry.record(sessionId, D2TxPathTelemetry.Event.PRE_APM_FORMAT_REJECT)
            }
        }
        try {
            synchronized(state.mergeLock) {
                state.assembler.append(audioData, bitsPerSample, sampleRate, numberOfChannels, numberOfFrames) { frame ->
                    onCanonicalPcmFrame(sessionId, frame, state)
                }
            }
        } catch (t: Throwable) {
            Profile01ShadowRuntimeObservability.logShadowTxFailed(
                sessionId = sessionId,
                reason = t.javaClass.simpleName,
                detail = t.message,
            )
        }
    }

    private fun onCanonicalPcmFrame(
        sessionId: String,
        frame: PcmFrame,
        state: ArmedSession,
    ) {
        if (frame.format != ConferencePcmFormat.CANONICAL) return

        val merged =
            if (state.halfFrame == null) {
                state.halfFrame = frame.samples.copyOf()
                return
            } else {
                val pcm20 =
                    ShortArray(OpusCodecConstants.FRAME_SAMPLES_20MS).also { out ->
                        state.halfFrame!!.copyInto(out, 0, 0, state.halfFrame!!.size)
                        frame.samples.copyInto(out, state.halfFrame!!.size, 0, frame.samples.size)
                    }
                state.halfFrame = null
                pcm20
            }

        val d2Chain = D2SendChainProcessor.processS1ToS2(merged)
        Profile01ShadowRuntimeObservability.maybeLogD2SendChainTick(
            sessionId = sessionId,
            moduleId = localModuleId(),
            result = d2Chain,
        )

        val pcmBeforeM01Gate =
            if (AqcRuntime.txProcessingEnabled) {
                val processed =
                    TxProcessingChain.process20ms(
                        d2Chain.samples,
                        tapPoint = TxProcessingChain.TapPoint.POST_APM,
                    )
                Profile01ShadowRuntimeObservability.maybeLogAqcTxMetrics(
                    sessionId = sessionId,
                    moduleId = localModuleId(),
                    metrics = processed.metrics,
                )
                processed.samples
            } else {
                d2Chain.samples
            }

        val gated =
            M01GatedTxEncodeLevelProcessor.maybeProcess(
                sessionId = sessionId,
                moduleId = localModuleId(),
                pcmForEncode = pcmBeforeM01Gate,
            )
        val pcmForEncode = gated.samples
        Profile01ShadowRuntimeObservability.maybeLogM01GatedTxEncodeLevel(
            sessionId = sessionId,
            moduleId = localModuleId(),
            result = gated,
        )
        val moduleId = localModuleId()
        D2S2EncodePcmObserver.onEncodePcmFrame(sessionId, moduleId, pcmForEncode)
        Profile01ShadowRuntimeObservability.maybeLogShadowTxEncodePcmLevel(
            sessionId = sessionId,
            moduleId = moduleId,
            pcmForEncode = pcmForEncode,
        )
        ShadowTxEncodePcmFieldCapture.maybeCaptureFrame(
            sessionId = sessionId,
            moduleId = localModuleId(),
            pcmForEncode = pcmForEncode,
        )

        val context = resolveEgressContext(sessionId) ?: return
        val transport = ConferenceSessionMediaBridge.wiring?.transport(sessionId) ?: return
        try {
            val opusPayload = state.opusEncoder.encode(pcmForEncode, context.sourceGeneration)
            if (transport.sendFromSource(context.egress, opusPayload, context.endpoint)) {
                val snap = transport.observability.snapshot()
                Profile01ShadowRuntimeObservability.maybeLogShadowTxActivity(
                    sessionId = sessionId,
                    moduleId = localModuleId(),
                    packetsSent = snap.packetsSent,
                    protectedSent = snap.packetsSent,
                    sendErrors = snap.sendErrors,
                )
            }
        } catch (t: Throwable) {
            Profile01ShadowRuntimeObservability.logShadowTxFailed(
                sessionId = sessionId,
                reason = t.javaClass.simpleName,
                detail = t.message,
            )
        }
    }

    private fun resolveEgressContext(sessionId: String): EgressContext? {
        val binding = sessionIndex.bindingForSession(sessionId) ?: return fence(sessionId, "NO_SESSION_BINDING")
        val conferenceId =
            sessionIndex.conferenceIdForSession(sessionId)
                ?: return fence(sessionId, "NO_CONFERENCE_ID")
        val sessionControl = registry.session(conferenceId) ?: return fence(sessionId, "NO_SESSION_FACT")
        val wiring = ConferenceSessionMediaBridge.wiring
        if (wiring == null || !wiring.hasSession(sessionId)) {
            return fence(sessionId, "NO_SHADOW_SESSION")
        }
        val snap = wiring.runtimeSnapshot(sessionId)
        if (snap == null || !snap.transportScopeActive || snap.ingressBlocked) {
            return fence(sessionId, "TRANSPORT_NOT_READY")
        }

        val moduleId = localModuleId()
        val memberBinding =
            factPort.memberBinding(sessionId, moduleId)
                ?: return fence(sessionId, "NO_MEMBER_BINDING")
        val registryMember =
            registry.member(conferenceId, moduleId)
                ?: return fence(sessionId, "NO_REGISTRY_MEMBER")
        val commitment =
            localSourceAuthority.currentCommitment(sessionId, moduleId)
                ?: return fence(sessionId, "NO_LOCAL_SOURCE")

        if (memberBinding.mediaKeyEpoch != sessionControl.mediaKeyEpoch) {
            return fence(sessionId, "MEDIA_KEY_EPOCH_MISMATCH")
        }
        if (registryMember.mediaKeyEpoch != sessionControl.mediaKeyEpoch) {
            return fence(sessionId, "REGISTRY_EPOCH_MISMATCH")
        }
        if (registryMember.membershipIncarnationId != commitment.sourceGeneration) {
            return fence(sessionId, "SOURCE_GENERATION_MISMATCH")
        }
        if (!armed.containsKey(sessionId)) {
            return null
        }

        val cached = egressBySession[sessionId]
        if (cached != null &&
            cached.mediaKeyEpoch == sessionControl.mediaKeyEpoch &&
            cached.sourceGeneration == commitment.sourceGeneration
        ) {
            return cached
        }

        val sessionFact =
            factPort.sessionFact(sessionId, binding.channelId, sessionControl.membershipVersion)
                ?: return fence(sessionId, "SESSION_FACT_PORT_MISS")
        val egress =
            SourceScopedSrtpEgress(
                sourceIdentity = memberBinding.moduleId,
                masterKey = sessionFact.masterKey.copyOf(),
                masterSalt = sessionFact.masterSalt.copyOf(),
                ssrc = memberBinding.ssrc,
                roc = 0,
                initialSeq = SHADOW_TX_INITIAL_SEQ,
                headerHeTemplate =
                    Phase1MediaHarness.buildHeaderHe(
                        ssrc = memberBinding.ssrc,
                        seq = SHADOW_TX_INITIAL_SEQ,
                        sourceAdmissionKey48 = memberBinding.sourceAdmissionKey48.copyOf(),
                        voiceActiveAudioLevel = SHADOW_TX_VOICE_ACTIVE_AUDIO_LEVEL,
                    ),
            )
        val context =
            EgressContext(
                endpoint = sessionFact.endpoint,
                egress = egress,
                mediaKeyEpoch = sessionControl.mediaKeyEpoch,
                sourceGeneration = commitment.sourceGeneration,
            )
        egressBySession[sessionId] = context
        return context
    }

    internal fun resolveProductionMicFrameSource(): LocalMicFrameSource =
        if (AqcRuntime.txProcessingEnabled) {
            PostApmLocalMicFrameSource
        } else {
            frameSource
        }

    companion object {
        private const val SHADOW_TX_INITIAL_SEQ = 0x5000
        private const val SHADOW_TX_VOICE_ACTIVE_AUDIO_LEVEL = 0x80

        fun targetWireAuthorityMeasurementPoint(): CapturePcmMeasurementPoint =
            CapturePcmMeasurementPoint.POST_SOFTWARE_APM_PRE_ENCODER

        fun currentWireAuthorityMeasurementPoint(): CapturePcmMeasurementPoint =
            when {
                D2SendChainRuntime.postApmMuxEnabled && D2TxPathTelemetry.lastForwardedPath == "POST_APM" ->
                    CapturePcmMeasurementPoint.POST_SOFTWARE_APM_PRE_ENCODER
                else -> CapturePcmMeasurementPoint.POST_HW_PRE_WEBRTC_APM
            }

        fun usesPreApmMicFrameSource(): Boolean =
            !D2SendChainRuntime.postApmMuxEnabled ||
                D2TxPathTelemetry.lastForwardedPath == "PRE_APM"
    }

    private fun fence(
        sessionId: String,
        reason: String,
    ): EgressContext? {
        egressBySession.remove(sessionId)
        Profile01ShadowRuntimeObservability.logShadowTxFenced(sessionId, reason)
        return null
    }
}
