package com.talkback.core.conference.session.integration

import com.talkback.core.conference.runtime.ConcentusOpusEncoderSeam
import com.talkback.core.conference.runtime.OpusCodecConstants
import com.talkback.core.conference.session.ConferenceSessionMediaBridge
import com.talkback.core.conference.session.ConferenceSessionMediaControlFactRegistry
import com.talkback.core.conference.session.ConferenceSessionMediaFactPort
import com.talkback.core.conference.runtime.MediaGroupEndpointBinding
import com.talkback.core.conference.transport.Phase1MediaHarness
import com.talkback.core.conference.transport.Rfc6464TxVoiceLevel
import com.talkback.core.conference.transport.SourceScopedSrtpEgress
import com.talkback.core.conference.transport.TxVoiceActivityHangover
import com.talkback.core.webrtc.LocalMicFrameSource
import com.talkback.core.webrtc.LocalOutboundPcmSink
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
        val voiceHangover: TxVoiceActivityHangover = TxVoiceActivityHangover(),
        var halfFrame: ShortArray? = null,
        var packetsEncoded: Long = 0L,
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
        val release =
            frameSource.acquire(
                LocalOutboundPcmSink { buffer, bits, rate, channels, frames ->
                    onMicPcm(sessionId, buffer, bits, rate, channels, frames, assembler)
                },
            )
        armed[sessionId] = ArmedSession(releaseMic = release, assembler = assembler)
    }

    private fun disarmCaptureTap(sessionId: String) {
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
        assembler: PcmFrameAssembler,
    ) {
        if (!armed.containsKey(sessionId)) return
        try {
            assembler.append(audioData, bitsPerSample, sampleRate, numberOfChannels, numberOfFrames) { frame ->
                onCanonicalPcmFrame(sessionId, frame)
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
    ) {
        val state = armed[sessionId] ?: return
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

        val context = resolveEgressContext(sessionId) ?: return
        val transport = ConferenceSessionMediaBridge.wiring?.transport(sessionId) ?: return
        try {
            val frameLevelDbov = Rfc6464TxVoiceLevel.frameLevelDbov(merged)
            val instantVoiceActive = state.voiceHangover.isInstantlyActiveFrameLevel(frameLevelDbov)
            val voiceActive = state.voiceHangover.observeFrameLevel(frameLevelDbov)
            val voiceOctet =
                Rfc6464TxVoiceLevel.toWireByte(
                    voiceActive = voiceActive,
                    frameLevelDbov = frameLevelDbov,
                )
            context.egress.setVoiceActiveAudioLevel(voiceOctet)
            val opusPayload = state.opusEncoder.encode(merged, context.sourceGeneration)
            state.packetsEncoded++
            Profile01ShadowRuntimeObservability.maybeLogShadowTxVoiceLevel(
                sessionId = sessionId,
                moduleId = localModuleId(),
                packetOrdinal = state.packetsEncoded,
                instantVoiceActive = instantVoiceActive,
                voiceActive = voiceActive,
                audioLevel = frameLevelDbov,
                wireOctet = voiceOctet,
            )
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
                        voiceActiveAudioLevel = Rfc6464TxVoiceLevel.SILENCE_LEVEL,
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

    companion object {
        private const val SHADOW_TX_INITIAL_SEQ = 0x5000
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
