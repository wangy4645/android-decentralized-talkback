package com.talkback.core.conference.transport

import android.content.Context
import android.os.PowerManager
import android.util.Log
import com.talkback.core.conference.runtime.AdmittedMediaFrame
import com.talkback.core.conference.runtime.AndroidRuntimeResources
import com.talkback.core.conference.runtime.FrameAdmitDisposition
import com.talkback.core.conference.runtime.MediaGroupEndpointBinding
import com.talkback.core.conference.runtime.MediaJitterConstants
import com.talkback.core.conference.runtime.MediaMixConstants
import com.talkback.core.conference.runtime.MediaRuntimeConstants
import com.talkback.core.conference.runtime.MulticastSocketTransportRebindSeam
import com.talkback.core.conference.runtime.OpusTestVectors
import com.talkback.core.conference.runtime.TransportHandle
import com.talkback.core.conference.wire.WireIngressResult
import java.io.File
import java.net.NetworkInterface
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

enum class ReceiverScaling4SourceRole {
    SENDER,
    RECEIVER,
}

data class ReceiverScaling4SourceConfig(
    val runId: String,
    val role: ReceiverScaling4SourceRole,
    val durationSec: Int,
    val sourceIdentity: String? = null,
    val baseSeq: Int = ReceiverScaling4SourceConstants.DEFAULT_BASE_SEQ,
    val epochWallMs: Long = 0L,
    val multicastAddress: String = Slice4MulticastNetworkConstants.DEFAULT_MULTICAST_ADDRESS,
    val mediaPort: Int = Slice4MulticastNetworkConstants.DEFAULT_MEDIA_PORT,
    val networkInterfaceName: String = Slice4MulticastNetworkConstants.DEFAULT_IFACE,
    val deviceLabel: String = "unknown",
    val nominalPps: Int = Slice4MulticastNetworkConstants.NOMINAL_PPS,
)

/**
 * Phase 1 — 4-source receiver scaling observation.
 *
 * ```text
 * RECEIVER SCALING OBSERVATION
 * 3 real network sources + 1 synthetic source (S4)
 * NOT 4-device field evidence
 * ```
 */
class ReceiverScaling4SourceRunner(
    private val context: Context,
) {
    fun run(config: ReceiverScaling4SourceConfig): File {
        val wakeLock = acquireWakeLock(config.durationSec)
        return try {
            when (config.role) {
                ReceiverScaling4SourceRole.SENDER -> runSender(config)
                ReceiverScaling4SourceRole.RECEIVER -> runReceiver(config)
            }
        } finally {
            releaseWakeLock(wakeLock)
        }
    }

    private fun runSender(config: ReceiverScaling4SourceConfig): File {
        val startedAtMs = System.currentTimeMillis()
        val endMs = startedAtMs + config.durationSec * 1000L
        val sourceIdentity =
            config.sourceIdentity
                ?: ReceiverScaling4SourceConstants.SENDER_SOURCE_BY_DEVICE[config.deviceLabel]
                ?: error("sender requires sourceIdentity or known deviceLabel")
        val fixture =
            Phase1MediaHarness.threeSourceFixtures.first { it.sourceIdentity == sourceIdentity }
        val endpoint =
            MediaGroupEndpointBinding(
                multicastAddress = config.multicastAddress,
                mediaPort = config.mediaPort,
                underlayScopeId = "rx-scaling4-${config.runId}",
            )
        val handle =
            TransportHandle(
                id = "rx-scaling4-sender-$sourceIdentity",
                endpoint = endpoint,
                networkBinding =
                    ConferenceMulticastNetworkBinding.fromInterfaceName(config.networkInterfaceName),
                multicastLockPolicy = MulticastLockPolicy.WIFI_MULTICAST_LOCK,
            )
        val resources = AndroidRuntimeResources.forAndroidProduct(context)
        val transport = ConferenceMulticastRtpSrtpTransport(resources = resources, wiring = null)

        log("SENDER_START source=$sourceIdentity soakSec=${config.durationSec}")

        if (!transport.beginScope(handle, startedAtMs)) {
            return writeReport(config, startedAtMs, System.currentTimeMillis(), mapOf("error" to "TRANSPORT_SCOPE_FAILED"))
        }
        val rebind = resources.rebindSeam as MulticastSocketTransportRebindSeam
        val socket = rebind.currentSocket
        val bind = rebind.lastSocketBindResult
        if (socket == null || socket.isClosed) {
            transport.endScope()
            return writeReport(
                config,
                startedAtMs,
                System.currentTimeMillis(),
                mapOf("error" to "SOCKET_NULL", "bind" to bindFact(bind, rebind)),
            )
        }
        try {
            NetworkInterface.getByName(config.networkInterfaceName)?.let { socket.networkInterface = it }
            socket.timeToLive = 32
            socket.loopbackMode = false
        } catch (e: Exception) {
            log("SENDER_IFACE ${e.message}")
        }

        val opusPayload = OpusTestVectors.encodeTone(fixture.frequencyHz)
        var packetsSent = 0L
        var sendErrors = 0L
        var nextSeq = config.baseSeq
        val intervalNs = 1_000_000_000L / config.nominalPps
        var nextSendNs = System.nanoTime()

        while (System.currentTimeMillis() < endMs) {
            val nowNs = System.nanoTime()
            if (nowNs < nextSendNs) {
                val sleepMs = ((nextSendNs - nowNs) / 1_000_000L).coerceAtMost(5L)
                if (sleepMs > 0) Thread.sleep(sleepMs)
                continue
            }
            val mediaSlot = nextSeq
            nextSeq += 1
            val artifact =
                Phase1MediaHarness.buildProtectedPacket(
                    fixture = fixture,
                    mediaSlot = mediaSlot,
                    opusPayload = opusPayload,
                )
            if (transport.sendProtectedArtifact(artifact, endpoint)) {
                packetsSent += 1
            } else {
                sendErrors += 1
            }
            nextSendNs += intervalNs
        }

        val snap = transport.observability.snapshot()
        transport.endScope()
        return writeReport(
            config,
            startedAtMs,
            System.currentTimeMillis(),
            mapOf(
                "sourceIdentity" to sourceIdentity,
                "packetsSent" to packetsSent,
                "sendErrors" to sendErrors,
                "lastMediaSlot" to (nextSeq - 1),
                "baseSeq" to config.baseSeq,
                "epochWallMs" to config.epochWallMs,
                "seqMode" to "MONOTONIC",
                "bind" to bindFact(bind, rebind),
                "transportObservability" to obsJson(snap),
            ),
        )
    }

    private fun runReceiver(config: ReceiverScaling4SourceConfig): File {
        val startedAtMs = System.currentTimeMillis()
        val endMs = startedAtMs + config.durationSec * 1000L
        val fixtures = Phase1MediaHarness.fourSourceScalingFixtures
        val networkFixtures =
            fixtures.filter { it.sourceIdentity in ReceiverScaling4SourceConstants.NETWORK_SOURCES }
        val syntheticFixture = Phase1MediaHarness.syntheticSource4Fixture
        val assembly = ConferenceMulticastRealMediaAssembly.create(context)
        Phase1MediaHarness.installAuthority(assembly.orchestrator.authority.store, fixtures)
        assembly.orchestrator.authority.syncAllAdmittedToRuntime()

        val endpoint =
            MediaGroupEndpointBinding(
                multicastAddress = config.multicastAddress,
                mediaPort = config.mediaPort,
                underlayScopeId = "rx-scaling4-${config.runId}",
            )
        val handle =
            TransportHandle(
                id = "rx-scaling4-receiver",
                endpoint = endpoint,
                networkBinding =
                    ConferenceMulticastNetworkBinding.fromInterfaceName(config.networkInterfaceName),
                multicastLockPolicy = MulticastLockPolicy.WIFI_MULTICAST_LOCK,
            )

        log(
            "RECEIVER_START soakSec=${config.durationSec} " +
                "topology=${ReceiverScaling4SourceConstants.TOPOLOGY_NOTE} " +
                "network=${ReceiverScaling4SourceConstants.NETWORK_SOURCES} " +
                "synthetic=${ReceiverScaling4SourceConstants.SYNTHETIC_SOURCE}",
        )
        val soak = SoakMetricsCollector()
        assembly.startPlayout()
        val pipelineLock = Any()
        var mixCycles = 0L
        var mixCyclesWithFour = 0L
        var playoutClock: AbsoluteMediaPlayoutClock? = null
        val syntheticStop = AtomicBoolean(false)
        var syntheticThread: Thread? = null
        try {
            val transport = assembly.pipeline.transport
            if (!transport.beginScope(handle, startedAtMs)) {
                return writeReport(config, startedAtMs, System.currentTimeMillis(), mapOf("error" to "TRANSPORT_SCOPE_FAILED"))
            }
            val bind = transport.lastSocketBindResult()
            if (bind == null || !bind.bindSucceeded) {
                transport.endScope()
                return writeReport(
                    config,
                    startedAtMs,
                    System.currentTimeMillis(),
                    mapOf("error" to "BIND_FAILED", "bindError" to bind?.error),
                )
            }
            transport.configureNetworkInterface(config.networkInterfaceName)

            val sourceIds = ReceiverScaling4SourceConstants.RECEIVER_SOURCES
            val ingressAcceptedBySource = sourceIds.associateWith { 0L }.toMutableMap()
            val ingressRejectedBySource = sourceIds.associateWith { 0L }.toMutableMap()
            val decodeSuccessBySource = sourceIds.associateWith { 0L }.toMutableMap()
            val perSourceJitterDepth = mutableMapOf<String, Int>()
            var datagramsReceived = 0L
            var ingressRejected = 0L
            var framesQueued = 0L
            var framesNotQueued = 0L
            val frameAdmitByKind = mutableMapOf<String, Long>()
            var liveEdgeResyncCount = 0L
            var packetsDiscardedOnResync = 0L
            var reorderCount = 0L
            var consecutiveNullSocket = 0
            var lastRejectReason: String? = null
            var syntheticPacketsInjected = 0L
            var syntheticInjectErrors = 0L

            playoutClock =
                AbsoluteMediaPlayoutClock(
                    anchorMs = startedAtMs,
                    threadName = "rx-scaling4-playout",
                ) { tickMediaTimeMs ->
                    val waitStartNs = System.nanoTime()
                    val tickProduct =
                        synchronized(pipelineLock) {
                            val waitUs = (System.nanoTime() - waitStartNs) / 1_000L
                            val holdStartNs = System.nanoTime()
                            if (System.currentTimeMillis() >= endMs) return@AbsoluteMediaPlayoutClock
                            val product =
                                runScalingPlayoutTick(
                                    assembly = assembly,
                                    fixtures = fixtures,
                                    nowMs = tickMediaTimeMs,
                                    decodeSuccessBySource = decodeSuccessBySource,
                                )
                            soak.recordLockSample(
                                waitUs = waitUs,
                                holdUs = (System.nanoTime() - holdStartNs) / 1_000L,
                            )
                            product
                        }
                    tickProduct?.let { product ->
                        val writeStartNs = System.nanoTime()
                        PipelineLockPlayoutRefinement.writeAudioTrackOutsideLock(
                            assembly.orchestrator,
                            product.mixedBlock,
                            tickMediaTimeMs,
                        )
                        val writeUs = (System.nanoTime() - writeStartNs) / 1_000L
                        soak.recordAudioTrackWrite(writeUs)
                        soak.recordCycle(
                            decodeDurationUs = product.decodeDurationUs,
                            mixDurationUs = product.mixDurationUs,
                            playoutBudgetUsedUs = product.decodeDurationUs + product.mixDurationUs,
                            topKCount = product.topKCount,
                            liveDecoders = product.liveDecoders,
                            underrunCount = assembly.playoutMetrics.underrunCount,
                            writeWallMs = System.currentTimeMillis(),
                        )
                        mixCycles += 1
                        if (product.mixParticipantCount >= 4) mixCyclesWithFour += 1
                    }
                }
            playoutClock.start()

            val syntheticOpus = OpusTestVectors.encodeTone(syntheticFixture.frequencyHz)
            var syntheticSeq = ReceiverScaling4SourceConstants.SYNTHETIC_BASE_SEQ
            val syntheticIntervalNs = 1_000_000_000L / config.nominalPps
            syntheticThread =
                Thread(
                    {
                        var nextSendNs = System.nanoTime()
                        while (!syntheticStop.get() && System.currentTimeMillis() < endMs) {
                            val nowNs = System.nanoTime()
                            if (nowNs < nextSendNs) {
                                val sleepMs = ((nextSendNs - nowNs) / 1_000_000L).coerceAtMost(5L)
                                if (sleepMs > 0) Thread.sleep(sleepMs)
                                continue
                            }
                            val mediaSlot = syntheticSeq
                            syntheticSeq += 1
                            val rxWallMs = System.currentTimeMillis()
                            val payload =
                                Phase1MediaHarness.buildProtectedPacket(
                                    fixture = syntheticFixture,
                                    mediaSlot = mediaSlot,
                                    opusPayload = syntheticOpus,
                                )
                            synchronized(pipelineLock) {
                                val admit =
                                    assembly.pipeline.admitProtectedDatagram(
                                        sourceIdentity = syntheticFixture.sourceIdentity,
                                        datagram = payload,
                                        rxWallMs = rxWallMs,
                                        mediaTimeMs = rxWallMs,
                                    )
                                when (val ingress = admit.ingress) {
                                    is WireIngressResult.Accepted -> {
                                        syntheticPacketsInjected += 1
                                        ingressAcceptedBySource[syntheticFixture.sourceIdentity] =
                                            (ingressAcceptedBySource[syntheticFixture.sourceIdentity] ?: 0L) + 1L
                                        var disposition = admit.frameAdmit
                                        if (disposition == FrameAdmitDisposition.REORDER_DISPLACEMENT_EXCEEDED) {
                                            reorderCount += 1
                                            val resynced =
                                                maybeSoakLiveEdgeResync(
                                                    assembly = assembly,
                                                    sourceIdentity = syntheticFixture.sourceIdentity,
                                                    liveSlot = admit.mediaSlot,
                                                    mediaTimeMs = admit.mediaTimeMs ?: rxWallMs,
                                                    arrivalMs = rxWallMs,
                                                    nowMs = rxWallMs,
                                                )
                                            if (resynced != null) {
                                                liveEdgeResyncCount += 1
                                                packetsDiscardedOnResync += resynced.discarded
                                                disposition = resynced.disposition
                                            }
                                        }
                                        perSourceJitterDepth[syntheticFixture.sourceIdentity] =
                                            assembly.orchestrator.pipeline.jitterSize(
                                                syntheticFixture.sourceIdentity,
                                                assembly.orchestrator.authority
                                                    .currentAdmitted(syntheticFixture.sourceIdentity)
                                                    ?.incarnationId
                                                    ?: 0L,
                                            )
                                        val dispositionName = disposition?.name ?: "NULL"
                                        frameAdmitByKind[dispositionName] =
                                            (frameAdmitByKind[dispositionName] ?: 0L) + 1L
                                        if (disposition == FrameAdmitDisposition.QUEUED) {
                                            framesQueued += 1
                                        } else {
                                            framesNotQueued += 1
                                        }
                                    }
                                    is WireIngressResult.Rejected -> {
                                        syntheticInjectErrors += 1
                                        ingressRejected += 1
                                        ingressRejectedBySource[syntheticFixture.sourceIdentity] =
                                            (ingressRejectedBySource[syntheticFixture.sourceIdentity] ?: 0L) + 1L
                                        lastRejectReason = "${ingress.frozenClass}:${ingress.reason}"
                                    }
                                }
                            }
                            nextSendNs += syntheticIntervalNs
                        }
                    },
                    "rx-scaling4-synthetic-s4",
                ).apply {
                    isDaemon = true
                    start()
                }

            while (System.currentTimeMillis() < endMs) {
                val outcome = transport.receiveOnce(sourceIdentity = null)
                if (outcome == null) {
                    if (transport.observability.lastReceiveError == "SOCKET_NULL") {
                        consecutiveNullSocket += 1
                        if (consecutiveNullSocket >= 3) break
                    }
                    continue
                }
                consecutiveNullSocket = 0
                val (payload, rxWallMs) =
                    when (outcome) {
                        is ConferenceMulticastRtpSrtpTransport.ReceiveOutcome.Raw ->
                            outcome.payload.copyOf(outcome.length) to outcome.rxWallMs
                        is ConferenceMulticastRtpSrtpTransport.ReceiveOutcome.Ingress -> continue
                    }
                datagramsReceived += 1

                val sourceIdentity = WireSourceIdentityResolver.resolveBySsrc(payload, networkFixtures)
                if (sourceIdentity == null) {
                    ingressRejected += 1
                    lastRejectReason = "UNKNOWN_SSRC"
                    continue
                }

                synchronized(pipelineLock) {
                    val admit =
                        assembly.pipeline.admitProtectedDatagram(
                            sourceIdentity = sourceIdentity,
                            datagram = payload,
                            rxWallMs = rxWallMs,
                            mediaTimeMs = rxWallMs,
                        )
                    when (val ingress = admit.ingress) {
                        is WireIngressResult.Accepted -> {
                            ingressAcceptedBySource[sourceIdentity] =
                                (ingressAcceptedBySource[sourceIdentity] ?: 0L) + 1L
                            var disposition = admit.frameAdmit
                            if (disposition == FrameAdmitDisposition.REORDER_DISPLACEMENT_EXCEEDED) {
                                reorderCount += 1
                                val resynced =
                                    maybeSoakLiveEdgeResync(
                                        assembly = assembly,
                                        sourceIdentity = sourceIdentity,
                                        liveSlot = admit.mediaSlot,
                                        mediaTimeMs = admit.mediaTimeMs ?: rxWallMs,
                                        arrivalMs = rxWallMs,
                                        nowMs = rxWallMs,
                                    )
                                if (resynced != null) {
                                    liveEdgeResyncCount += 1
                                    packetsDiscardedOnResync += resynced.discarded
                                    disposition = resynced.disposition
                                }
                            }
                            perSourceJitterDepth[sourceIdentity] =
                                assembly.orchestrator.pipeline.jitterSize(
                                    sourceIdentity,
                                    assembly.orchestrator.authority.currentAdmitted(sourceIdentity)
                                        ?.incarnationId
                                        ?: 0L,
                                )
                            val dispositionName = disposition?.name ?: "NULL"
                            frameAdmitByKind[dispositionName] =
                                (frameAdmitByKind[dispositionName] ?: 0L) + 1L
                            if (disposition == FrameAdmitDisposition.QUEUED) {
                                framesQueued += 1
                            } else {
                                framesNotQueued += 1
                            }
                        }
                        is WireIngressResult.Rejected -> {
                            ingressRejected += 1
                            ingressRejectedBySource[sourceIdentity] =
                                (ingressRejectedBySource[sourceIdentity] ?: 0L) + 1L
                            lastRejectReason = "${ingress.frozenClass}:${ingress.reason}"
                        }
                    }
                }
            }

            syntheticStop.set(true)
            syntheticThread?.join(5_000L)

            val pipeline = assembly.orchestrator.pipeline
            val snap = assembly.pipeline.observability.snapshot()
            transport.endScope()
            val endedAtMs = System.currentTimeMillis()
            val clockSnap = playoutClock.snapshot()
            val timingSnap = soak.snapshot()
            val topKMax = (timingSnap["topKSelectedCount"] as Map<*, *>)["max"] as? Int ?: 0
            val liveDecoderMax = (timingSnap["liveDecoderCount"] as Map<*, *>)["max"] as? Int ?: 0
            val writeP50 = (timingSnap["writeIntervalUs"] as Map<*, *>)["p50"] as? Long ?: 0L
            val budgetP99 = (timingSnap["playoutBudgetUsedUs"] as Map<*, *>)["p99"] as? Long ?: 0L
            val capsHeld =
                topKMax <= MediaRuntimeConstants.TOP_K &&
                    liveDecoderMax <= MediaMixConstants.MAX_LIVE_DECODERS
            val scalingPass =
                ingressAcceptedBySource.values.all { it > 0 } &&
                    mixCycles > 0 &&
                    mixCyclesWithFour > 0 &&
                    assembly.playoutMetrics.successfulWrites > 0 &&
                    capsHeld &&
                    writeP50 in 16_000L..28_000L &&
                    budgetP99 <= 20_000L
            val metrics =
                mapOf(
                    "evidenceClass" to ReceiverScaling4SourceConstants.EVIDENCE_CLASS,
                    "topology" to ReceiverScaling4SourceConstants.TOPOLOGY_NOTE,
                    "notFourDeviceFieldEvidence" to ReceiverScaling4SourceConstants.NOT_FOUR_DEVICE_FIELD_EVIDENCE,
                    "networkSources" to ReceiverScaling4SourceConstants.NETWORK_SOURCES,
                    "syntheticSource" to ReceiverScaling4SourceConstants.SYNTHETIC_SOURCE,
                    "observationOnly" to true,
                    "capacityGates" to "NONE",
                    "fixApplied" to true,
                    "lockScopeRefinement" to PipelineLockPlayoutRefinement.FIX_NAME,
                    "frozenCaps" to
                        mapOf(
                            "topK" to MediaRuntimeConstants.TOP_K,
                            "maxLiveDecoders" to MediaMixConstants.MAX_LIVE_DECODERS,
                            "mixMaxSources" to MediaMixConstants.MIX_MAX_SOURCES,
                        ),
                    "playoutClock" to clockSnap,
                    "datagramsReceived" to datagramsReceived,
                    "syntheticPacketsInjected" to syntheticPacketsInjected,
                    "syntheticInjectErrors" to syntheticInjectErrors,
                    "ingressAccepted" to ingressAcceptedBySource.values.sum(),
                    "ingressRejected" to ingressRejected,
                    "ingressAcceptedBySource" to ingressAcceptedBySource,
                    "ingressRejectedBySource" to ingressRejectedBySource,
                    "framesQueued" to framesQueued,
                    "framesNotQueued" to framesNotQueued,
                    "frameAdmitByKind" to frameAdmitByKind,
                    "soakLiveEdgeResync" to
                        mapOf(
                            "policy" to "SOAK_HARNESS_ONLY_NOT_PROFILE03",
                            "liveEdgeResyncCount" to liveEdgeResyncCount,
                            "packetsDiscardedOnResync" to packetsDiscardedOnResync,
                            "reorderCount" to reorderCount,
                        ),
                    "decodeSuccessBySource" to decodeSuccessBySource,
                    "mixCycles" to mixCycles,
                    "mixCyclesWithFourParticipants" to mixCyclesWithFour,
                    "successfulWrites" to assembly.playoutMetrics.successfulWrites,
                    "failedWrites" to assembly.playoutMetrics.failedWrites,
                    "underrunCount" to assembly.playoutMetrics.underrunCount,
                    "underrunObserveOnly" to true,
                    "lateForPlayoutCount" to pipeline.lateForPlayoutCount,
                    "plcCount" to pipeline.plcCount,
                    "pipelineDecodeCount" to pipeline.decodeCount,
                    "perSourceJitterDepth" to perSourceJitterDepth,
                    "liveDecoderCountFinal" to assembly.orchestrator.decodeMix.decoderPool.liveCount(),
                    "topKFinal" to
                        assembly.orchestrator.selection.currentTopK().members.map { it.sourceIdentity },
                    "capsHeld" to capsHeld,
                    "scalingPass" to scalingPass,
                    "soakTiming" to timingSnap,
                    "lastRejectReason" to lastRejectReason,
                    "bindSucceeded" to bind.bindSucceeded,
                    "bindInterface" to bind.networkInterfaceName,
                    "joinSucceeded" to transport.lastJoinSucceeded(),
                    "transportObservability" to obsJson(snap),
                    "note" to
                        "scalingPass requires 4-source mix + frozen caps; underrun observe-only",
                )
            log(
                "RECEIVER_END rx=$datagramsReceived synth=$syntheticPacketsInjected " +
                    "mix4=$mixCyclesWithFour writes=${assembly.playoutMetrics.successfulWrites} " +
                    "topKMax=$topKMax liveDecMax=$liveDecoderMax scalingPass=$scalingPass",
            )
            return writeReport(config, startedAtMs, endedAtMs, metrics)
        } finally {
            syntheticStop.set(true)
            syntheticThread?.join(2_000L)
            playoutClock?.stop()
            assembly.stopPlayout()
        }
    }

    private data class SoakLiveEdgeResyncResult(
        val discarded: Long,
        val disposition: FrameAdmitDisposition,
    )

    private fun maybeSoakLiveEdgeResync(
        assembly: ConferenceMulticastRealMediaAssembly,
        sourceIdentity: String,
        liveSlot: Long?,
        mediaTimeMs: Long,
        arrivalMs: Long,
        nowMs: Long,
    ): SoakLiveEdgeResyncResult? {
        if (liveSlot == null) return null
        val admitted =
            assembly.orchestrator.authority.currentAdmitted(sourceIdentity) ?: return null
        val pipeline = assembly.orchestrator.pipeline
        val expected =
            pipeline.nextExpectedSlot(sourceIdentity, admitted.incarnationId) ?: return null
        if (liveSlot <= expected) return null
        val displacement = liveSlot - expected
        if (displacement <= MediaJitterConstants.MAX_REORDER_PACKETS) return null

        var gapEmpty = true
        var slot = expected
        while (slot < liveSlot) {
            if (pipeline.hasBufferedFrame(sourceIdentity, admitted.incarnationId, slot)) {
                gapEmpty = false
                break
            }
            slot += 1
        }
        if (!gapEmpty) return null

        val gapAgeMs = displacement * MediaJitterConstants.MEDIA_SLOT_MS
        if (gapAgeMs <= MediaJitterConstants.MAX_PLAYOUT_DELAY_MS) return null

        val discarded =
            pipeline.soakResyncJitterToLiveEdge(
                sourceIdentity = sourceIdentity,
                incarnationId = admitted.incarnationId,
                liveSlot = liveSlot,
            )
        val retry =
            pipeline.admitFrame(
                AdmittedMediaFrame(
                    sourceIdentity = sourceIdentity,
                    incarnationId = admitted.incarnationId,
                    mediaSlot = liveSlot,
                    mediaTimeMs = mediaTimeMs,
                    arrivalMs = arrivalMs,
                ),
                nowMs,
            )
        return SoakLiveEdgeResyncResult(discarded = discarded.toLong(), disposition = retry)
    }

    private fun runScalingPlayoutTick(
        assembly: ConferenceMulticastRealMediaAssembly,
        fixtures: List<Phase1MediaHarness.SourceFixture>,
        nowMs: Long,
        decodeSuccessBySource: MutableMap<String, Long>,
    ): PipelineLockPlayoutRefinement.PlayoutTickProduct? {
        val pipeline = assembly.pipeline.orchestrator.pipeline
        assembly.orchestrator.selectTopK(nowMs)
        val pcmFrames = mutableListOf<com.talkback.core.conference.runtime.PcmFrame>()
        val mixIds = linkedSetOf<String>()
        val topKIds =
            assembly.orchestrator.selection.currentTopK().members.map { it.sourceIdentity }.toSet()

        val decodeStartNs = System.nanoTime()
        for (fixture in fixtures) {
            val admitted =
                assembly.orchestrator.authority.currentAdmitted(fixture.sourceIdentity)
                    ?: continue
            val slots =
                pipeline.bufferedSlots(fixture.sourceIdentity, admitted.incarnationId)
            if (slots.isEmpty()) continue
            val slot = slots.minOrNull() ?: continue
            val frame =
                pipeline.peekBufferedFrame(fixture.sourceIdentity, admitted.incarnationId, slot)
                    ?: continue
            pipeline.pullSlot(
                sourceIdentity = fixture.sourceIdentity,
                incarnationId = admitted.incarnationId,
                slot = slot,
                slotMediaTimeMs = frame.mediaTimeMs,
                nowMs = nowMs,
            )
            val pcm =
                assembly.orchestrator.decodeMix.produceMixablePcm(
                    sourceIdentity = fixture.sourceIdentity,
                    incarnationId = admitted.incarnationId,
                    mediaSlot = slot,
                    nowMs = nowMs,
                )
            if (pcm != null) {
                mixIds += fixture.sourceIdentity
                pcmFrames += pcm
                decodeSuccessBySource[fixture.sourceIdentity] =
                    (decodeSuccessBySource[fixture.sourceIdentity] ?: 0L) + 1L
            }
        }
        val decodeDurationUs = (System.nanoTime() - decodeStartNs) / 1_000L
        if (pcmFrames.isEmpty()) return null

        val mixStartNs = System.nanoTime()
        val block = com.talkback.core.conference.runtime.EqualWeightMixer.mix(pcmFrames)
        val mixDurationUs = (System.nanoTime() - mixStartNs) / 1_000L

        return PipelineLockPlayoutRefinement.PlayoutTickProduct(
            mixedBlock = PipelineLockPlayoutRefinement.sealedMixedBlock(block),
            decodeDurationUs = decodeDurationUs,
            mixDurationUs = mixDurationUs,
            topKCount = topKIds.size,
            liveDecoders = assembly.orchestrator.decodeMix.decoderPool.liveCount(),
            mixParticipantCount = mixIds.size,
        )
    }

    private fun bindFact(
        bind: MulticastSocketBindResult?,
        rebind: MulticastSocketTransportRebindSeam,
    ): Map<String, Any?> =
        mapOf(
            "bindSucceeded" to (bind?.bindSucceeded ?: false),
            "networkInterfaceName" to bind?.networkInterfaceName,
            "error" to bind?.error,
            "joinSucceeded" to rebind.lastJoinSucceeded,
            "joinError" to rebind.lastJoinError,
        )

    private fun obsJson(snap: MulticastTransportObservabilitySnapshot): JSONObject =
        JSONObject()
            .put("packetsSent", snap.packetsSent)
            .put("packetsReceived", snap.packetsReceived)
            .put("ingressAccepted", snap.ingressAccepted)
            .put("ingressRejected", snap.ingressRejected)
            .put("decodeDurationUs", snap.decodeDurationUs)
            .put("mixDurationUs", snap.mixDurationUs)
            .put("playoutBudgetUsedUs", snap.playoutBudgetUsedUs)
            .put("audioTrackUnderrunCount", snap.audioTrackUnderrunCount)

    private fun writeReport(
        config: ReceiverScaling4SourceConfig,
        startedAtMs: Long,
        endedAtMs: Long,
        metrics: Map<String, Any?>,
    ): File {
        val root = context.getExternalFilesDir(null) ?: context.filesDir
        val dir = File(root, "receiver-scaling-4source").apply { mkdirs() }
        val file = File(dir, "${config.runId}-${config.role.name.lowercase()}.json")
        val json =
            JSONObject()
                .put("probe", ReceiverScaling4SourceConstants.PROBE_NAME)
                .put("evidenceClass", ReceiverScaling4SourceConstants.EVIDENCE_CLASS)
                .put("topology", ReceiverScaling4SourceConstants.TOPOLOGY_NOTE)
                .put("notFourDeviceFieldEvidence", ReceiverScaling4SourceConstants.NOT_FOUR_DEVICE_FIELD_EVIDENCE)
                .put("runId", config.runId)
                .put("role", config.role.name)
                .put("deviceLabel", config.deviceLabel)
                .put("sourceIdentity", config.sourceIdentity)
                .put("startedAtMs", startedAtMs)
                .put("endedAtMs", endedAtMs)
                .put("durationSec", config.durationSec)
                .put("multicastAddress", config.multicastAddress)
                .put("mediaPort", config.mediaPort)
                .put("networkInterfaceName", config.networkInterfaceName)
                .put("nominalPps", config.nominalPps)
                .put("baseSeq", config.baseSeq)
                .put("epochWallMs", config.epochWallMs)
                .put("slotClock", "CONTINUOUS_SOAK_MONOTONIC_SEQ+HARNESS_LIVE_EDGE_RESYNC")
                .put("playoutClock", "ABSOLUTE_20MS_DECOUPLED")
                .put("syntheticSource", ReceiverScaling4SourceConstants.SYNTHETIC_SOURCE)
                .put("networkSources", org.json.JSONArray(ReceiverScaling4SourceConstants.NETWORK_SOURCES))
        json.put("metrics", metricsToJson(metrics))
        file.writeText(json.toString(2))
        return file
    }

    private fun metricsToJson(metrics: Map<String, Any?>): JSONObject {
        val m = JSONObject()
        for ((k, v) in metrics) {
            m.put(k, metricValueToJson(v))
        }
        return m
    }

    private fun metricValueToJson(value: Any?): Any? =
        when (value) {
            null -> JSONObject.NULL
            is Map<*, *> -> {
                val nested = JSONObject()
                for ((nk, nv) in value) {
                    nested.put(nk.toString(), metricValueToJson(nv))
                }
                nested
            }
            is List<*> -> {
                val arr = org.json.JSONArray()
                for (item in value) {
                    arr.put(metricValueToJson(item))
                }
                arr
            }
            is JSONObject -> value
            else -> value
        }

    private fun acquireWakeLock(durationSec: Int): PowerManager.WakeLock? {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return null
        return try {
            pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RX_SCALING_4SRC").apply {
                setReferenceCounted(false)
                acquire((durationSec + 60) * 1000L)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun releaseWakeLock(wakeLock: PowerManager.WakeLock?) {
        if (wakeLock == null) return
        try {
            if (wakeLock.isHeld) wakeLock.release()
        } catch (_: Exception) {
            // best-effort
        }
    }

    private fun log(message: String) {
        Log.i(ReceiverScaling4SourceConstants.LOG_TAG, message)
    }
}
