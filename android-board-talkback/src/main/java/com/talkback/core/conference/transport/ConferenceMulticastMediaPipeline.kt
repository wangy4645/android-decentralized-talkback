package com.talkback.core.conference.transport

import com.talkback.core.conference.runtime.AndroidRuntimeResources
import com.talkback.core.conference.runtime.ConferenceMediaExecutionOrchestrator
import com.talkback.core.conference.runtime.EqualWeightMixer
import com.talkback.core.conference.runtime.FrameAdmitDisposition
import com.talkback.core.conference.runtime.MediaJitterConstants
import com.talkback.core.conference.runtime.MixCycleResult
import com.talkback.core.conference.runtime.OpusPayloadStore
import com.talkback.core.conference.runtime.PcmFrame
import com.talkback.core.conference.runtime.PlayoutMetricsSeam
import com.talkback.core.conference.runtime.SlotPullDisposition
import com.talkback.core.conference.runtime.SourceMixInputKind
import com.talkback.core.conference.runtime.SourceMixInputSnapshot
import com.talkback.core.conference.session.integration.Profile01ShadowRuntimeObservability
import com.talkback.core.conference.runtime.RecordingAudioTrackSeam
import com.talkback.core.conference.wire.WireIngressResult
import com.talkback.core.conference.wire.WireOwningSeam
import com.talkback.core.conference.wire.WireReplayState

/**
 * Phase 1 Slice 2/3 — multicast receive path through Profile 03 execution chain.
 *
 * Slice 3 adds [OpusPayloadStore] staging + [PlayoutMetricsSeam] observability for
 * real Opus decode and AudioTrack playout seams.
 */
data class PipelineReceiveResult(
    val ingress: WireIngressResult,
    val frameAdmit: FrameAdmitDisposition?,
    val mixCycle: MixCycleResult?,
    val playoutObserved: Boolean,
    val packetToPlayoutLatencyUs: Long,
    val jitterDepth: Int,
    val decodeDurationUs: Long = 0L,
    val mixDurationUs: Long = 0L,
)

data class PipelineAdmitResult(
    val ingress: WireIngressResult,
    val frameAdmit: FrameAdmitDisposition?,
    val jitterDepth: Int,
    val mediaSlot: Long?,
    val mediaTimeMs: Long?,
)

data class PipelinePlayoutResult(
    val mixCycle: MixCycleResult,
    val playoutObserved: Boolean,
    val decodeDurationUs: Long,
    val mixDurationUs: Long,
    val playoutWriteDurationUs: Long,
    val playoutBudgetUsedUs: Long,
    val audioTrackUnderrunCount: Long,
)

data class TimedMixCycleResult(
    val mixCycle: MixCycleResult,
    val decodeDurationUs: Long,
    val mixDurationUs: Long,
)

class ConferenceMulticastMediaPipeline(
    val orchestrator: ConferenceMediaExecutionOrchestrator,
    val transport: ConferenceMulticastRtpSrtpTransport,
    private val opusPayloadStore: OpusPayloadStore? = null,
    private val playoutMetrics: PlayoutMetricsSeam? = null,
) {
    val observability: MulticastTransportObservability
        get() = transport.observability

    /**
     * Ingress + jitter admit only (Slice 3 batch path for multi-source slots).
     */
    fun admitProtectedDatagram(
        sourceIdentity: String,
        datagram: ByteArray,
        rxWallMs: Long,
        roc: Int = 0,
        replay: WireReplayState? = null,
        mediaTimeline: RelativeMediaTimeline? = null,
        mediaTimeMs: Long? = null,
        playoutReferenceMs: Long? = null,
    ): PipelineAdmitResult {
        val ingress =
            orchestrator.authority.admitWire(
                sourceIdentity = sourceIdentity,
                datagram = datagram,
                roc = roc,
                replay = replay,
            )

        if (ingress !is WireIngressResult.Accepted) {
            observability.recordIngressRejected()
            return PipelineAdmitResult(
                ingress = ingress,
                frameAdmit = null,
                jitterDepth = 0,
                mediaSlot = null,
                mediaTimeMs = null,
            )
        }

        val admitted =
            orchestrator.authority.store.derivedAdmitted(sourceIdentity)
                ?: return PipelineAdmitResult(
                    ingress =
                        WireIngressResult.Rejected(
                            owningSeam = WireOwningSeam.Q3,
                            frozenClass = "PIPELINE_REJECTED",
                            reason = "no derived admitted source",
                        ),
                    frameAdmit = null,
                    jitterDepth = 0,
                    mediaSlot = null,
                    mediaTimeMs = null,
                )

        val incarnationId = admitted.incarnationId
        orchestrator.authority.syncAdmittedToRuntime(sourceIdentity)

        val voice =
            WireIngressMediaMapper.voiceObservationFromHeader(
                sourceIdentity = sourceIdentity,
                incarnationId = incarnationId,
                headerAndHe = ingress.headerAndHe,
            )
        orchestrator.observeVoice(voice)
        orchestrator.selectTopK(playoutReferenceMs ?: rxWallMs)

        val resolvedMediaTimeMs =
            mediaTimeMs
                ?: mediaTimeline?.mediaTimeMs(ingress.sequence, rxWallMs)
        val frame =
            WireIngressMediaMapper.mediaFrame(
                sourceIdentity = sourceIdentity,
                incarnationId = incarnationId,
                accepted = ingress,
                arrivalMs = rxWallMs,
                mediaTimeMs = resolvedMediaTimeMs,
            )
        opusPayloadStore?.put(
            sourceIdentity = sourceIdentity,
            incarnationId = incarnationId,
            mediaSlot = frame.mediaSlot,
            opusPayload = ingress.plaintextPayload,
        )
        val frameAdmit = orchestrator.admitFrame(frame, rxWallMs)
        val jitterDepth = orchestrator.pipeline.jitterSize(sourceIdentity, incarnationId)
        observability.recordIngressAccepted(sourceIdentity, rxWallMs, 0L, jitterDepth)
        Profile01ShadowRuntimeObservability.activeSessionId?.let { sessionId ->
            Profile01ShadowRuntimeObservability.maybeLogIngressFunnel(
                sessionId = sessionId,
                sourceIdentity = sourceIdentity,
                mediaSlot = frame.mediaSlot,
                frameAdmit = frameAdmit,
                jitter = orchestrator.pipeline.jitterSlotDomainSnapshot(sourceIdentity, incarnationId),
            )
        }
        val snap = observability.snapshot()
        Profile01ShadowRuntimeObservability.maybeLogIngressActivity(
            sourceIdentity = sourceIdentity,
            packetsReceived = snap.packetsReceived,
            ingressAccepted = snap.ingressAccepted,
            activeJitterSources = orchestrator.pipeline.activeJitterSourceCount(),
            liveDecoders = orchestrator.decodeMix.decoderPool.liveCount(),
            successfulPlayoutWrites = playoutMetrics?.successfulWrites ?: 0L,
        )

        return PipelineAdmitResult(
            ingress = ingress,
            frameAdmit = frameAdmit,
            jitterDepth = jitterDepth,
            mediaSlot = frame.mediaSlot,
            mediaTimeMs = frame.mediaTimeMs,
        )
    }

    /**
     * Top-K → decode → mix → playout for one media slot (after all sources admitted).
     */
    fun runMixPlayoutCycle(
        nowMs: Long,
        slot: Long,
        slotMediaTimeMs: Long,
    ): PipelinePlayoutResult {
        val cycleStartNs = System.nanoTime()
        val underrunBefore = playoutMetrics?.underrunCount ?: 0L
        val timedMix = executeTimedMixCycle(nowMs, slot, slotMediaTimeMs)
        val playoutObserved = orchestrator.playout(timedMix.mixCycle.mixedBlock, nowMs)
        val playoutWriteDurationUs = playoutMetrics?.lastWriteDurationUs ?: 0L
        val underrunAfter = playoutMetrics?.underrunCount ?: 0L
        val underrunDelta = (underrunAfter - underrunBefore).coerceAtLeast(0L)
        val budgetUs = (System.nanoTime() - cycleStartNs) / 1_000L

        observability.recordDecodeMixPlayout(
            decodeUs = timedMix.decodeDurationUs,
            mixUs = timedMix.mixDurationUs,
            playoutBudgetUsedUs = budgetUs,
            underrun = underrunDelta > 0L,
        )
        if (playoutObserved) {
            Profile01ShadowRuntimeObservability.maybeLogPlayoutActivity(
                playoutMetrics?.successfulWrites ?: 0L,
            )
        }

        return PipelinePlayoutResult(
            mixCycle = timedMix.mixCycle,
            playoutObserved = playoutObserved,
            decodeDurationUs = timedMix.decodeDurationUs,
            mixDurationUs = timedMix.mixDurationUs,
            playoutWriteDurationUs = playoutWriteDurationUs,
            playoutBudgetUsedUs = budgetUs,
            audioTrackUnderrunCount = underrunAfter,
        )
    }

    /**
     * Admit three active sources for one slot, then run real decode/mix/playout cycle.
     */
    fun processThreeSourceSlot(
        fixtures: List<Phase1MediaHarness.SourceFixture>,
        protectedBySource: Map<String, ByteArray>,
        rxWallMs: Long,
        mediaSlot: Int,
    ): PipelinePlayoutResult {
        require(fixtures.size == 3) { "Slice 3 closure expects exactly 3 active sources" }
        for (fixture in fixtures) {
            val datagram =
                protectedBySource[fixture.sourceIdentity]
                    ?: error("missing protected datagram for ${fixture.sourceIdentity}")
            val admit = admitProtectedDatagram(fixture.sourceIdentity, datagram, rxWallMs)
            require(admit.ingress is WireIngressResult.Accepted) {
                "ingress rejected for ${fixture.sourceIdentity}: ${admit.ingress}"
            }
            require(admit.frameAdmit == FrameAdmitDisposition.QUEUED) {
                "frame not queued for ${fixture.sourceIdentity}: ${admit.frameAdmit}"
            }
        }
        val mediaTimeMs = mediaSlot.toLong() * MediaJitterConstants.MEDIA_SLOT_MS
        return runMixPlayoutCycle(
            nowMs = rxWallMs + MediaJitterConstants.MEDIA_SLOT_MS,
            slot = mediaSlot.toLong(),
            slotMediaTimeMs = mediaTimeMs,
        )
    }

    /**
     * Process one already-received protected datagram through ingress → mix.
     */
    fun processProtectedDatagram(
        sourceIdentity: String,
        datagram: ByteArray,
        rxWallMs: Long,
        roc: Int = 0,
        replay: WireReplayState? = null,
    ): PipelineReceiveResult {
        val pipelineStartNs = System.nanoTime()
        val admit = admitProtectedDatagram(sourceIdentity, datagram, rxWallMs, roc, replay)
        if (admit.ingress !is WireIngressResult.Accepted || admit.mediaSlot == null) {
            return PipelineReceiveResult(
                ingress = admit.ingress,
                frameAdmit = admit.frameAdmit,
                mixCycle = null,
                playoutObserved = false,
                packetToPlayoutLatencyUs =
                    (System.nanoTime() - pipelineStartNs) / 1_000L,
                jitterDepth = admit.jitterDepth,
            )
        }

        val playout =
            runMixPlayoutCycle(
                nowMs = rxWallMs + MediaJitterConstants.MEDIA_SLOT_MS,
                slot = admit.mediaSlot,
                slotMediaTimeMs = admit.mediaTimeMs ?: (admit.mediaSlot * MediaJitterConstants.MEDIA_SLOT_MS),
            )

        return PipelineReceiveResult(
            ingress = admit.ingress,
            frameAdmit = admit.frameAdmit,
            mixCycle = playout.mixCycle,
            playoutObserved = playout.playoutObserved,
            packetToPlayoutLatencyUs = (System.nanoTime() - pipelineStartNs) / 1_000L,
            jitterDepth = admit.jitterDepth,
            decodeDurationUs = playout.decodeDurationUs,
            mixDurationUs = playout.mixDurationUs,
        )
    }

    fun receiveAndProcess(
        sourceIdentity: String,
        roc: Int = 0,
        replay: WireReplayState? = null,
    ): PipelineReceiveResult? {
        val receiveStartNs = System.nanoTime()
        val outcome =
            transport.receiveOnce(
                sourceIdentity = null,
                roc = roc,
                replay = replay,
            ) ?: return null

        val rxWallMs =
            when (outcome) {
                is ConferenceMulticastRtpSrtpTransport.ReceiveOutcome.Ingress -> outcome.rxWallMs
                is ConferenceMulticastRtpSrtpTransport.ReceiveOutcome.Raw -> outcome.rxWallMs
            }
        val datagram =
            when (outcome) {
                is ConferenceMulticastRtpSrtpTransport.ReceiveOutcome.Ingress ->
                    error("transport must not run ingress when pipeline owns admission")
                is ConferenceMulticastRtpSrtpTransport.ReceiveOutcome.Raw ->
                    outcome.payload.copyOf(outcome.length)
            }
        val receiveDurationUs = (System.nanoTime() - receiveStartNs) / 1_000L
        observability.recordReceive(sourceIdentity, rxWallMs, receiveDurationUs)
        return processProtectedDatagram(sourceIdentity, datagram, rxWallMs, roc, replay)
    }

    fun executeTimedMixCycle(
        nowMs: Long,
        slot: Long,
        slotMediaTimeMs: Long,
    ): TimedMixCycleResult {
        orchestrator.selectTopK(nowMs)
        val topKMembers = orchestrator.selection.currentTopK().members
        val topKIds = topKMembers.map { it.sourceIdentity }.toSet()

        for (member in topKMembers) {
            orchestrator.pipeline.pullSlot(
                sourceIdentity = member.sourceIdentity,
                incarnationId = member.incarnationId,
                slot = slot,
                slotMediaTimeMs = slotMediaTimeMs,
                nowMs = nowMs,
            )
        }

        val decodeIds = linkedSetOf<String>()
        val mixIds = linkedSetOf<String>()
        val pcmFrames = mutableListOf<PcmFrame>()
        val sourceMixInputs = linkedMapOf<String, SourceMixInputSnapshot>()
        val sourcePullDispositions = linkedMapOf<String, SlotPullDisposition>()

        val decodeStartNs = System.nanoTime()
        for (member in topKMembers) {
            if (!orchestrator.selection.isDecodeEligible(member.sourceIdentity, member.incarnationId)) {
                continue
            }
            val pcm =
                orchestrator.decodeMix.produceMixablePcm(
                    sourceIdentity = member.sourceIdentity,
                    incarnationId = member.incarnationId,
                    mediaSlot = slot,
                    nowMs = nowMs,
                )
            if (pcm != null) {
                decodeIds += member.sourceIdentity
                mixIds += member.sourceIdentity
                pcmFrames += pcm
                sourcePullDispositions[member.sourceIdentity] = SlotPullDisposition.DECODE_FRAME
                sourceMixInputs[member.sourceIdentity] =
                    SourceMixInputSnapshot(
                        kind = SourceMixInputKind.REAL,
                        samples = pcm.samples,
                    )
            }
        }
        val decodeDurationUs = (System.nanoTime() - decodeStartNs) / 1_000L

        val mixStartNs = System.nanoTime()
        val block = EqualWeightMixer.mix(pcmFrames)
        val mixDurationUs = (System.nanoTime() - mixStartNs) / 1_000L

        return TimedMixCycleResult(
            mixCycle =
                MixCycleResult(
                    topKIdentities = topKIds,
                    decodeInvocationIdentities = decodeIds,
                    mixParticipantIdentities = mixIds,
                    mixedBlock = block,
                    sourcePullDispositions = sourcePullDispositions,
                    sourceMixInputs = sourceMixInputs,
                ),
            decodeDurationUs = decodeDurationUs,
            mixDurationUs = mixDurationUs,
        )
    }

    companion object {
        fun createHarness(
            orchestrator: ConferenceMediaExecutionOrchestrator = ConferenceMediaExecutionOrchestrator(),
            resources: AndroidRuntimeResources = AndroidRuntimeResources(),
        ): ConferenceMulticastMediaPipeline =
            ConferenceMulticastMediaPipeline(
                orchestrator = orchestrator,
                transport =
                    ConferenceMulticastRtpSrtpTransport(
                        resources = resources,
                        wiring = null,
                    ),
            )
    }
}
