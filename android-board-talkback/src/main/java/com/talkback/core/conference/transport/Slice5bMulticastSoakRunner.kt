package com.talkback.core.conference.transport

import android.content.Context
import android.os.PowerManager
import android.util.Log
import com.talkback.core.conference.runtime.AdmittedMediaFrame
import com.talkback.core.conference.runtime.AndroidRuntimeResources
import com.talkback.core.conference.runtime.FrameAdmitDisposition
import com.talkback.core.conference.runtime.MediaGroupEndpointBinding
import com.talkback.core.conference.runtime.MediaJitterConstants
import com.talkback.core.conference.runtime.MulticastSocketTransportRebindSeam
import com.talkback.core.conference.runtime.OpusTestVectors
import com.talkback.core.conference.runtime.TransportHandle
import com.talkback.core.conference.wire.WireIngressResult
import java.io.File
import java.net.NetworkInterface
import org.json.JSONObject

enum class Slice5bMulticastSoakRole {
    SENDER,
    RECEIVER,
}

data class Slice5bMulticastSoakConfig(
    val runId: String,
    val role: Slice5bMulticastSoakRole,
    val durationSec: Int,
    val sourceIdentity: String? = null,
    val baseSeq: Int = Slice5bMulticastSoakConstants.DEFAULT_BASE_SEQ,
    /** Shared wall-clock epoch (ms) so all senders compute the same mediaSlot timeline. */
    val epochWallMs: Long = 0L,
    val multicastAddress: String = Slice4MulticastNetworkConstants.DEFAULT_MULTICAST_ADDRESS,
    val mediaPort: Int = Slice4MulticastNetworkConstants.DEFAULT_MEDIA_PORT,
    val networkInterfaceName: String = Slice4MulticastNetworkConstants.DEFAULT_IFACE,
    val deviceLabel: String = "unknown",
    val nominalPps: Int = Slice4MulticastNetworkConstants.NOMINAL_PPS,
)

/**
 * Phase 1 Slice 5b — 3-source continuous soak (observation only).
 *
 * Uses continuous incrementing media slots for sustained playout.
 * This is NOT the LIVE_SLOT diagnostic experiment — soak observation only.
 */
class Slice5bMulticastSoakRunner(
    private val context: Context,
) {
    fun run(config: Slice5bMulticastSoakConfig): File {
        val wakeLock = acquireWakeLock(config.durationSec)
        return try {
            when (config.role) {
                Slice5bMulticastSoakRole.SENDER -> runSender(config)
                Slice5bMulticastSoakRole.RECEIVER -> runReceiver(config)
            }
        } finally {
            releaseWakeLock(wakeLock)
        }
    }

    private fun runSender(config: Slice5bMulticastSoakConfig): File {
        val startedAtMs = System.currentTimeMillis()
        val endMs = startedAtMs + config.durationSec * 1000L
        val sourceIdentity =
            config.sourceIdentity
                ?: Slice5bMulticastSoakConstants.SENDER_SOURCE_BY_DEVICE[config.deviceLabel]
                ?: error("sender requires sourceIdentity or known deviceLabel")
        val fixture =
            Phase1MediaHarness.threeSourceFixtures.first { it.sourceIdentity == sourceIdentity }
        val endpoint =
            MediaGroupEndpointBinding(
                multicastAddress = config.multicastAddress,
                mediaPort = config.mediaPort,
                underlayScopeId = "slice5b-${config.runId}",
            )
        val handle =
            TransportHandle(
                id = "slice5b-sender-$sourceIdentity",
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
        // Monotonic seq — wall-clock slots create gaps under send jitter and trip
        // REORDER_DISPLACEMENT_EXCEEDED on the receiver. Soak mix is playout-driven,
        // so identical cross-source slots are not required.
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

    private fun runReceiver(config: Slice5bMulticastSoakConfig): File {
        val startedAtMs = System.currentTimeMillis()
        val endMs = startedAtMs + config.durationSec * 1000L
        val fixtures =
            Slice5bMulticastSoakConstants.RECEIVER_SOURCES.map { id ->
                Phase1MediaHarness.threeSourceFixtures.first { it.sourceIdentity == id }
            }
        val assembly = ConferenceMulticastRealMediaAssembly.create(context)
        Phase1MediaHarness.installAuthority(assembly.orchestrator.authority.store, fixtures)
        assembly.orchestrator.authority.syncAllAdmittedToRuntime()

        val endpoint =
            MediaGroupEndpointBinding(
                multicastAddress = config.multicastAddress,
                mediaPort = config.mediaPort,
                underlayScopeId = "slice5b-${config.runId}",
            )
        val handle =
            TransportHandle(
                id = "slice5b-receiver",
                endpoint = endpoint,
                networkBinding =
                    ConferenceMulticastNetworkBinding.fromInterfaceName(config.networkInterfaceName),
                multicastLockPolicy = MulticastLockPolicy.WIFI_MULTICAST_LOCK,
            )

        log("RECEIVER_START soakSec=${config.durationSec} playout=ABSOLUTE_20MS_DECOUPLED sources=${fixtures.map { it.sourceIdentity }}")
        val soak = SoakMetricsCollector()
        assembly.startPlayout()
        val pipelineLock = Any()
        var mixCycles = 0L
        var mixCyclesWithThree = 0L
        var playoutClock: AbsoluteMediaPlayoutClock? = null
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

            val sourceIds = Slice5bMulticastSoakConstants.RECEIVER_SOURCES
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

            playoutClock =
                AbsoluteMediaPlayoutClock(
                    anchorMs = startedAtMs,
                    threadName = "slice5b-soak-playout",
                ) { tickMediaTimeMs ->
                    val waitStartNs = System.nanoTime()
                    val tickProduct =
                        synchronized(pipelineLock) {
                            val waitUs = (System.nanoTime() - waitStartNs) / 1_000L
                            val holdStartNs = System.nanoTime()
                            if (System.currentTimeMillis() >= endMs) return@AbsoluteMediaPlayoutClock
                            val product =
                                runSoakPlayoutTick(
                                    assembly = assembly,
                                    fixtures = fixtures,
                                    nowMs = tickMediaTimeMs,
                                    decodeSuccessBySource = decodeSuccessBySource,
                                    soak = soak,
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
                        if (product.mixParticipantCount >= 3) mixCyclesWithThree += 1
                    }
                }
            playoutClock.start()

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

                val sourceIdentity = WireSourceIdentityResolver.resolveBySsrc(payload, fixtures)
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

            val pipeline = assembly.orchestrator.pipeline
            val snap = assembly.pipeline.observability.snapshot()
            transport.endScope()
            val endedAtMs = System.currentTimeMillis()
            val clockSnap = playoutClock.snapshot()
            val metrics =
                mapOf(
                    "observationOnly" to true,
                    "capacityGates" to "NONE",
                    "fixApplied" to true,
                    "lockScopeRefinement" to PipelineLockPlayoutRefinement.FIX_NAME,
                    "playoutClock" to clockSnap,
                    "datagramsReceived" to datagramsReceived,
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
                    "liveEdgeResyncCount" to liveEdgeResyncCount,
                    "packetsDiscardedOnResync" to packetsDiscardedOnResync,
                    "reorderCount" to reorderCount,
                    "decodeSuccessBySource" to decodeSuccessBySource,
                    "mixCycles" to mixCycles,
                    "mixCyclesWithThreeParticipants" to mixCyclesWithThree,
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
                    "soakTiming" to soak.snapshot(),
                    "lastRejectReason" to lastRejectReason,
                    "bindSucceeded" to bind.bindSucceeded,
                    "bindInterface" to bind.networkInterfaceName,
                    "joinSucceeded" to transport.lastJoinSucceeded(),
                    "transportObservability" to obsJson(snap),
                    // Soft completion — underrun does NOT fail soak
                    "soakCompleted" to
                        (
                            ingressAcceptedBySource.values.all { it > 0 } &&
                                mixCycles > 0 &&
                                mixCyclesWithThree > 0 &&
                                assembly.playoutMetrics.successfulWrites > 0
                        ),
                    "note" to
                        "soakCompleted requires 3-source mix; underrun observe-only (not a fail gate)",
                )
            log(
                "RECEIVER_END rx=$datagramsReceived mix3=$mixCyclesWithThree " +
                    "writes=${assembly.playoutMetrics.successfulWrites} " +
                    "underrun=${assembly.playoutMetrics.underrunCount} late=${pipeline.lateForPlayoutCount}",
            )
            return writeReport(config, startedAtMs, endedAtMs, metrics)
        } finally {
            playoutClock?.stop()
            assembly.stopPlayout()
        }
    }

    /**
     * Soak-harness live-edge resync — **not** a Profile 03 semantic change.
     *
     * Profile 03 [admitFrame] still returns REORDER_DISPLACEMENT_EXCEEDED.
     * Only when the gap is empty and older than [MediaJitterConstants.MAX_PLAYOUT_DELAY_MS]
     * along the slot timeline do we discard stale expected and retry admit once.
     */
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

    /**
     * Playout-clock driven soak mix: pull earliest buffered frame per source (if any),
     * decode available PCM, mix 1..3 participants. Does not require identical mediaSlots.
     */
    private fun runSoakPlayoutTick(
        assembly: ConferenceMulticastRealMediaAssembly,
        fixtures: List<Phase1MediaHarness.SourceFixture>,
        nowMs: Long,
        decodeSuccessBySource: MutableMap<String, Long>,
        soak: SoakMetricsCollector,
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
        if (pcmFrames.isEmpty()) {
            soak.recordEmptyPcmTick()
            return null
        }

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

    private fun tryThreeSourceMix(
        assembly: ConferenceMulticastRealMediaAssembly,
        fixtures: List<Phase1MediaHarness.SourceFixture>,
        slot: Long,
        nowMs: Long,
    ): PipelinePlayoutResult? {
        val pipeline = assembly.pipeline.orchestrator.pipeline
        val buffered =
            fixtures.map { fixture ->
                val admitted =
                    assembly.orchestrator.authority.currentAdmitted(fixture.sourceIdentity)
                        ?: return null
                pipeline.peekBufferedFrame(fixture.sourceIdentity, admitted.incarnationId, slot)
                    ?: return null
            }
        val slotMediaTimeMs = buffered.maxOf { it.mediaTimeMs }
        return assembly.pipeline.runMixPlayoutCycle(
            nowMs = nowMs,
            slot = slot,
            slotMediaTimeMs = slotMediaTimeMs,
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
        config: Slice5bMulticastSoakConfig,
        startedAtMs: Long,
        endedAtMs: Long,
        metrics: Map<String, Any?>,
    ): File {
        val root = context.getExternalFilesDir(null) ?: context.filesDir
        val dir = File(root, "slice5b-mcast-soak").apply { mkdirs() }
        val file = File(dir, "${config.runId}-${config.role.name.lowercase()}.json")
        val json =
            JSONObject()
                .put("probe", Slice5bMulticastSoakConstants.PROBE_NAME)
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
            pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SLICE5B_MC_SOAK").apply {
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
        Log.i(Slice5bMulticastSoakConstants.LOG_TAG, message)
    }
}
