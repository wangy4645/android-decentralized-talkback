package com.talkback.core.conference.session.integration

import com.talkback.core.conference.runtime.ConcentusOpusEncoderSeam
import com.talkback.core.conference.runtime.OpusCodecConstants
import com.talkback.core.conference.session.ConferenceSessionMediaBridge
import com.talkback.core.conference.session.ConferenceSessionMediaControlFactRegistry
import com.talkback.core.conference.session.ConferenceSessionMediaFactAdapter
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
import android.os.Process
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

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
    private data class EncodeJob(
        val pcm: ShortArray,
        val enqueuedNs: Long,
        val sourceGeneration: Long,
    )

    private class ArmedSession(
        val releaseMic: () -> Unit,
        val assembler: PcmFrameAssembler,
        val opusEncoder: ConcentusOpusEncoderSeam = ConcentusOpusEncoderSeam(),
        val mergeLock: Any = Any(),
        var halfFrame: ShortArray? = null,
        val running: AtomicBoolean = AtomicBoolean(true),
        val queue: ArrayBlockingQueue<EncodeJob> = ArrayBlockingQueue(ENCODE_QUEUE_CAPACITY),
        val overflow: AtomicLong = AtomicLong(0),
        val overflowDropOldest: AtomicLong = AtomicLong(0),
        val maxDepth: AtomicInteger = AtomicInteger(0),
        val droppedOnStop: AtomicLong = AtomicLong(0),
        val pending: AtomicInteger = AtomicInteger(0),
        val warmed: CountDownLatch = CountDownLatch(1),
        var worker: Thread? = null,
    )

    private data class EgressContext(
        val endpoint: MediaGroupEndpointBinding,
        val egress: SourceScopedSrtpEgress,
        val mediaKeyEpoch: Long,
        val sourceGeneration: Long,
    )

    private val armed = ConcurrentHashMap<String, ArmedSession>()
    private val egressBySession = ConcurrentHashMap<String, EgressContext>()
    /** [0]=encodeNs, [1]=sendNs, [2]=encodedFlag on the AudioRecord callback thread. */
    private val captureCallbackCost = ThreadLocal.withInitial { LongArray(3) }

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
        val session = ArmedSession(releaseMic = release, assembler = assembler)
        session.worker =
            Thread(
                { encodeSendLoop(sessionId, session) },
                "tb-shadow-tx-encode-${sessionId.take(8)}",
            ).apply {
                isDaemon = true
                start()
            }
        runCatching { session.warmed.await(ENCODE_WARMUP_WAIT_MS, TimeUnit.MILLISECONDS) }
        armed[sessionId] = session
    }

    private fun disarmCaptureTap(sessionId: String) {
        ShadowTxEncodePcmFieldCapture.onSessionCaptureEnded(sessionId)
        M01GatedTxEncodeLevelProcessor.clearSession(sessionId)
        D2S2EncodePcmObserver.clearSession(sessionId)
        D2TxPathTelemetry.clearSession(sessionId)
        val session = armed[sessionId] ?: return
        session.running.set(false)
        session.worker?.join(ENCODE_WORKER_JOIN_MS)
        if (session.worker?.isAlive == true) {
            session.worker?.interrupt()
            session.worker?.join(50L)
        }
        var leftover = 0
        while (session.queue.poll() != null) leftover++
        if (leftover > 0) session.droppedOnStop.addAndGet(leftover.toLong())
        armed.remove(sessionId, session)
        session.releaseMic.invoke()
        session.opusEncoder.release()
    }

    internal fun awaitPendingEncode(sessionId: String, timeoutMs: Long = 1_000L): Boolean {
        val session = armed[sessionId] ?: return true
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (session.pending.get() > 0) {
            if (System.nanoTime() >= deadline) return false
            Thread.sleep(5L)
        }
        return true
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
        if (!state.running.get()) return
        val enterNs = System.nanoTime()
        val intervalNs = Profile01ShadowRuntimeObservability.markShadowTxCaptureEnterNs(sessionId)
        val cost = captureCallbackCost.get()
        cost[0] = 0L
        cost[1] = 0L
        cost[2] = 0L
        Profile01ShadowRuntimeObservability.maybeLogShadowTxCapturePcm(
            sessionId = sessionId,
            moduleId = localModuleId(),
            bitsPerSample = bitsPerSample,
            sampleRate = sampleRate,
            numberOfChannels = numberOfChannels,
            numberOfFrames = numberOfFrames,
        )
        if (D2SendChainRuntime.captureChainObserveEnabled && !D2SendChainRuntime.postApmMuxEnabled) {
            if (D2ConferenceMicFrameSource.isCanonicalChunk(bitsPerSample, sampleRate, numberOfChannels, numberOfFrames)) {
                D2TxPathTelemetry.record(sessionId, D2TxPathTelemetry.Event.PRE_APM_FORWARD)
            } else {
                D2TxPathTelemetry.record(sessionId, D2TxPathTelemetry.Event.PRE_APM_FORMAT_REJECT)
            }
        }
        val preNs = System.nanoTime() - enterNs
        val assembleStartNs = System.nanoTime()
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
        val assembleWallNs = System.nanoTime() - assembleStartNs
        val encodeNs = cost[0]
        val sendNs = cost[1]
        val assembleNs = (assembleWallNs - encodeNs - sendNs).coerceAtLeast(0L)
        val totalNs = System.nanoTime() - enterNs
        Profile01ShadowRuntimeObservability.recordShadowTxCaptureCallbackCost(
            sessionId = sessionId,
            moduleId = localModuleId(),
            intervalNs = intervalNs,
            preUs = preNs / 1000L,
            assembleUs = assembleNs / 1000L,
            encodeUs = encodeNs / 1000L,
            sendUs = sendNs / 1000L,
            totalUs = totalNs / 1000L,
            encoded = cost[2] != 0L,
        )
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
        offerEncodeJob(sessionId, state, merged)
    }

    private fun offerEncodeJob(
        sessionId: String,
        state: ArmedSession,
        pcmCopy: ShortArray,
    ) {
        if (!state.running.get()) return
        val generation = egressBySession[sessionId]?.sourceGeneration ?: 0L
        val job = EncodeJob(pcm = pcmCopy, enqueuedNs = System.nanoTime(), sourceGeneration = generation)
        var accepted = state.queue.offer(job)
        var droppedOldest = false
        if (!accepted) {
            val evicted = state.queue.poll()
            if (evicted != null) {
                state.overflowDropOldest.incrementAndGet()
                state.overflow.incrementAndGet()
                state.pending.updateAndGet { value -> if (value > 0) value - 1 else 0 }
                droppedOldest = true
            }
            accepted = state.queue.offer(job)
        }
        val depth = state.queue.size
        if (depth > state.maxDepth.get()) state.maxDepth.set(depth)
        if (accepted) {
            state.pending.incrementAndGet()
        }
        Profile01ShadowRuntimeObservability.maybeLogShadowTxEncodeQueue(
            sessionId = sessionId,
            moduleId = localModuleId(),
            depth = depth,
            maxDepth = state.maxDepth.get(),
            overflow = state.overflow.get(),
            overflowDropOldest = state.overflowDropOldest.get(),
            droppedOnStop = state.droppedOnStop.get(),
            queuedUs = 0L,
            accepted = accepted,
            droppedOldest = droppedOldest,
        )
    }

    private fun encodeSendLoop(
        sessionId: String,
        state: ArmedSession,
    ) {
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) }
        runCatching { state.opusEncoder.warmUp() }
        state.warmed.countDown()
        while (true) {
            val job =
                try {
                    state.queue.poll(20L, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    break
                }
            if (job != null) {
                processEncodeJob(sessionId, state, job)
                continue
            }
            if (!state.running.get()) {
                while (true) {
                    val rest = state.queue.poll() ?: break
                    processEncodeJob(sessionId, state, rest)
                }
                break
            }
        }
    }

    private fun processEncodeJob(
        sessionId: String,
        state: ArmedSession,
        job: EncodeJob,
    ) {
        try {
            if (armed[sessionId] !== state) return
            val queuedUs = (System.nanoTime() - job.enqueuedNs) / 1000L
            Profile01ShadowRuntimeObservability.maybeLogShadowTxEncodeDequeued(
                sessionId = sessionId,
                moduleId = localModuleId(),
                queuedUs = queuedUs,
                depth = state.queue.size,
                overflow = state.overflow.get(),
            )
            val context = resolveEgressContext(sessionId) ?: return
            if (job.sourceGeneration != 0L && job.sourceGeneration != context.sourceGeneration) return
            val transport = ConferenceSessionMediaBridge.wiring?.transport(sessionId) ?: return
            val pcmForEncode = processTxPcmForEncode(sessionId, job.pcm)
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
        } finally {
            state.pending.updateAndGet { value -> if (value > 0) value - 1 else 0 }
        }
    }

    private fun processTxPcmForEncode(
        sessionId: String,
        merged: ShortArray,
    ): ShortArray {
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
        return pcmForEncode
    }

    private fun resolveEgressContext(sessionId: String): EgressContext? {
        val binding = sessionIndex.bindingForSession(sessionId) ?: return fence(sessionId, "NO_SESSION_BINDING")
        val conferenceId =
            sessionIndex.conferenceIdForSession(sessionId)
                ?: return fence(sessionId, "NO_CONFERENCE_ID")
        val sessionControl = registry.session(conferenceId) ?: return fence(sessionId, "NO_SESSION_FACT", conferenceId)
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
        var registryMember =
            registry.member(conferenceId, moduleId)
                ?: return fence(sessionId, "NO_REGISTRY_MEMBER")
        if (!ConferenceSessionMediaFactAdapter.memberMatchesSession(sessionControl, registryMember)) {
            if (moduleId == localModuleId()) {
                hostProjection.maybeProjectHostLocalSourceMember(sessionId)
                registryMember = registry.member(conferenceId, moduleId) ?: return fence(sessionId, "NO_REGISTRY_MEMBER")
            }
            if (!ConferenceSessionMediaFactAdapter.memberMatchesSession(sessionControl, registryMember)) {
                return fence(sessionId, "REGISTRY_MEMBER_STALE")
            }
        }
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
            if (moduleId == localModuleId()) {
                hostProjection.maybeProjectHostLocalSourceMember(sessionId)
                val refreshed = registry.member(conferenceId, moduleId)
                if (refreshed != null &&
                    ConferenceSessionMediaFactAdapter.memberMatchesSession(sessionControl, refreshed) &&
                    refreshed.membershipIncarnationId == commitment.sourceGeneration
                ) {
                    registryMember = refreshed
                } else {
                    return fence(sessionId, "SOURCE_GENERATION_MISMATCH")
                }
            } else {
                return fence(sessionId, "SOURCE_GENERATION_MISMATCH")
            }
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
        private const val ENCODE_QUEUE_CAPACITY = 4
        private const val ENCODE_WORKER_JOIN_MS = 250L
        private const val ENCODE_WARMUP_WAIT_MS = 100L

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
        conferenceIdHex: String? = null,
    ): EgressContext? {
        egressBySession.remove(sessionId)
        if (reason == "NO_SESSION_FACT" && conferenceIdHex != null) {
            Profile01ControlPlaneDiagnosticTrace.logShadowTxNoSessionFact(
                sessionId = sessionId,
                conferenceIdHex = conferenceIdHex,
                registrySessionPresent = registry.session(conferenceIdHex) != null,
            )
        } else {
            Profile01ShadowRuntimeObservability.logShadowTxFenced(sessionId, reason)
        }
        return null
    }
}
