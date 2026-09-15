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

enum class LiveSlotTimingDiagnosticRole {
    SENDER,
    RECEIVER,
}

data class LiveSlotTimingDiagnosticConfig(
    val runId: String,
    val role: LiveSlotTimingDiagnosticRole,
    val durationSec: Int,
    val sourceIdentity: String? = null,
    val baseSeq: Int = LiveSlotTimingDiagnosticConstants.DEFAULT_BASE_SEQ,
    val epochWallMs: Long = 0L,
    val multicastAddress: String = Slice4MulticastNetworkConstants.DEFAULT_MULTICAST_ADDRESS,
    val mediaPort: Int = Slice4MulticastNetworkConstants.DEFAULT_MEDIA_PORT,
    val networkInterfaceName: String = Slice4MulticastNetworkConstants.DEFAULT_IFACE,
    val deviceLabel: String = "device",
    val nominalPps: Int = 50,
)

/**
 * LIVE_SLOT timing diagnostic — attribution only.
 *
 * Reuses Slice 5b soak path (monotonic seq + harness live-edge resync +
 * mediaTimeMs=rxWallMs) so Q1–Q3 attribute the same late/underrun regime as
 * soak Attempt #5. Does **not** change Profile 03 / deadline / AudioTrack.
 */
class LiveSlotTimingDiagnosticRunner(
    private val context: Context,
) {
    fun run(config: LiveSlotTimingDiagnosticConfig): File {
        val wake = acquireWakeLock(config.durationSec)
        try {
            return when (config.role) {
                LiveSlotTimingDiagnosticRole.SENDER -> runSender(config)
                LiveSlotTimingDiagnosticRole.RECEIVER -> runReceiver(config)
            }
        } finally {
            releaseWakeLock(wake)
        }
    }

    private fun runSender(config: LiveSlotTimingDiagnosticConfig): File {
        val startedAtMs = System.currentTimeMillis()
        val endMs = startedAtMs + config.durationSec * 1000L
        val sourceIdentity =
            config.sourceIdentity
                ?: LiveSlotTimingDiagnosticConstants.SENDER_SOURCE_BY_DEVICE[config.deviceLabel]
                ?: error("sourceIdentity required")
        val fixture =
            Phase1MediaHarness.threeSourceFixtures.first { it.sourceIdentity == sourceIdentity }
        val endpoint =
            MediaGroupEndpointBinding(
                multicastAddress = config.multicastAddress,
                mediaPort = config.mediaPort,
                underlayScopeId = "live-slot-timing-${config.runId}",
            )
        val handle =
            TransportHandle(
                id = "live-slot-timing-sender-$sourceIdentity",
                endpoint = endpoint,
                networkBinding =
                    ConferenceMulticastNetworkBinding.fromInterfaceName(config.networkInterfaceName),
                multicastLockPolicy = MulticastLockPolicy.WIFI_MULTICAST_LOCK,
            )
        val resources = AndroidRuntimeResources.forAndroidProduct(context)
        val transport = ConferenceMulticastRtpSrtpTransport(resources = resources, wiring = null)

        log("SENDER_START source=$sourceIdentity durationSec=${config.durationSec}")

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
                "note" to "Same sender path as Slice5b #5 for late/underrun attribution continuity",
                "bind" to bindFact(bind, rebind),
                "transportObservability" to obsJson(snap),
            ),
        )
    }

    private fun runReceiver(config: LiveSlotTimingDiagnosticConfig): File {
        val startedAtMs = System.currentTimeMillis()
        val endMs = startedAtMs + config.durationSec * 1000L
        val fixtures =
            LiveSlotTimingDiagnosticConstants.RECEIVER_SOURCES.map { id ->
                Phase1MediaHarness.threeSourceFixtures.first { it.sourceIdentity == id }
            }
        val assembly = ConferenceMulticastRealMediaAssembly.create(context)
        Phase1MediaHarness.installAuthority(assembly.orchestrator.authority.store, fixtures)
        assembly.orchestrator.authority.syncAllAdmittedToRuntime()

        val endpoint =
            MediaGroupEndpointBinding(
                multicastAddress = config.multicastAddress,
                mediaPort = config.mediaPort,
                underlayScopeId = "live-slot-timing-${config.runId}",
            )
        val handle =
            TransportHandle(
                id = "live-slot-timing-receiver",
                endpoint = endpoint,
                networkBinding =
                    ConferenceMulticastNetworkBinding.fromInterfaceName(config.networkInterfaceName),
                multicastLockPolicy = MulticastLockPolicy.WIFI_MULTICAST_LOCK,
            )

        log("RECEIVER_START durationSec=${config.durationSec} playout=ABSOLUTE_20MS_DECOUPLED")
        val timing = LiveSlotTimingDiagnosticCollector()
        val soak = SoakMetricsCollector()
        assembly.startPlayout()
        val pipelineLock = Any()
        var mixCycles = 0L
        var mixCyclesWithThree = 0L
        var lastLateCount = 0
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

            val sourceIds = LiveSlotTimingDiagnosticConstants.RECEIVER_SOURCES
            val ingressAcceptedBySource = sourceIds.associateWith { 0L }.toMutableMap()
            val decodeSuccessBySource = sourceIds.associateWith { 0L }.toMutableMap()
            var datagramsReceived = 0L
            var ingressRejected = 0L
            var framesQueued = 0L
            var liveEdgeResyncCount = 0L
            var packetsDiscardedOnResync = 0L
            var reorderCount = 0L
            var consecutiveNullSocket = 0

            playoutClock =
                AbsoluteMediaPlayoutClock(
                    anchorMs = startedAtMs,
                    threadName = "live-slot-timing-playout",
                ) { tickMediaTimeMs ->
                    val prep =
                        synchronized(pipelineLock) {
                            if (System.currentTimeMillis() >= endMs) return@AbsoluteMediaPlayoutClock
                            runDiagPlayoutTickInsideLock(
                                assembly = assembly,
                                fixtures = fixtures,
                                nowMs = tickMediaTimeMs,
                                decodeSuccessBySource = decodeSuccessBySource,
                                timing = timing,
                                lastLateCount = lastLateCount,
                            )
                        }
                    if (prep != null) {
                        val writeStartNs = System.nanoTime()
                        PipelineLockPlayoutRefinement.writeAudioTrackOutsideLock(
                            assembly.orchestrator,
                            prep.product.mixedBlock,
                            tickMediaTimeMs,
                        )
                        val writeUs = (System.nanoTime() - writeStartNs) / 1_000L
                        soak.recordAudioTrackWrite(writeUs)
                        val result =
                            finishDiagPlayoutTickOutsideLock(
                                assembly = assembly,
                                prep = prep,
                                writeUs = writeUs,
                                nowMs = tickMediaTimeMs,
                                soak = soak,
                                timing = timing,
                            )
                        lastLateCount = result.lastLateCount
                        mixCycles += result.cycles
                        mixCyclesWithThree += result.triple
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
                    when (admit.ingress) {
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
                            val mediaSlot = admit.mediaSlot ?: -1L
                            val mediaTimeMs = admit.mediaTimeMs ?: rxWallMs
                            val usefulDeadlineMs =
                                mediaTimeMs + MediaJitterConstants.MAX_PLAYOUT_DELAY_MS
                            timing.recordAdmit(
                                source = sourceIdentity,
                                slot = mediaSlot,
                                rxWallMs = rxWallMs,
                                mediaTimeMs = mediaTimeMs,
                                usefulDeadlineMs = usefulDeadlineMs,
                                disposition = disposition?.name ?: "NULL",
                            )
                            if (disposition == FrameAdmitDisposition.QUEUED) {
                                framesQueued += 1
                            }
                        }
                        is WireIngressResult.Rejected -> {
                            ingressRejected += 1
                        }
                    }
                }
            }

            val pipeline = assembly.orchestrator.pipeline
            val snap = assembly.pipeline.observability.snapshot()
            transport.endScope()
            val endedAtMs = System.currentTimeMillis()
            val clockSnap = playoutClock.snapshot()
            val timingSnap = timing.snapshot()
            val activeSources = ingressAcceptedBySource.count { it.value > 0 }
            val metrics =
                mapOf(
                    "observationOnly" to true,
                    "capacityGates" to "NONE",
                    "fixApplied" to true,
                    "playoutClock" to clockSnap,
                    "datagramsReceived" to datagramsReceived,
                    "ingressAccepted" to ingressAcceptedBySource.values.sum(),
                    "ingressRejected" to ingressRejected,
                    "ingressAcceptedBySource" to ingressAcceptedBySource,
                    "activeSourceCount" to activeSources,
                    "framesQueued" to framesQueued,
                    "liveEdgeResyncCount" to liveEdgeResyncCount,
                    "packetsDiscardedOnResync" to packetsDiscardedOnResync,
                    "reorderCount" to reorderCount,
                    "decodeSuccessBySource" to decodeSuccessBySource,
                    "mixCycles" to mixCycles,
                    "mixCyclesWithThreeParticipants" to mixCyclesWithThree,
                    "successfulWrites" to assembly.playoutMetrics.successfulWrites,
                    "failedWrites" to assembly.playoutMetrics.failedWrites,
                    "underrunCount" to assembly.playoutMetrics.underrunCount,
                    "lateForPlayoutCount" to pipeline.lateForPlayoutCount,
                    "plcCount" to pipeline.plcCount,
                    "soakTiming" to soak.snapshot(),
                    "bindSucceeded" to bind.bindSucceeded,
                    "bindInterface" to bind.networkInterfaceName,
                    "joinSucceeded" to transport.lastJoinSucceeded(),
                    "transportObservability" to obsJson(snap),
                    "timingDiagnostic" to timingSnap,
                    "diagnosticCompleted" to
                        (
                            activeSources >= 2 &&
                                mixCycles > 0 &&
                                assembly.playoutMetrics.successfulWrites > 0
                        ),
                )
            return writeReport(config, startedAtMs, endedAtMs, metrics)
        } finally {
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

    private data class DiagPlayoutPrep(
        val product: PipelineLockPlayoutRefinement.PlayoutTickProduct,
        val mixIds: Set<String>,
        val lateCount: Int,
        val underrunBefore: Long,
        val lateBeforeWrite: Int,
        val lastLateCount: Int,
    )

    private data class DiagTickResult(
        val cycles: Long,
        val triple: Long,
        val lastLateCount: Int,
    )

    private fun runDiagPlayoutTickInsideLock(
        assembly: ConferenceMulticastRealMediaAssembly,
        fixtures: List<Phase1MediaHarness.SourceFixture>,
        nowMs: Long,
        decodeSuccessBySource: MutableMap<String, Long>,
        timing: LiveSlotTimingDiagnosticCollector,
        lastLateCount: Int,
    ): DiagPlayoutPrep? {
        val pipeline = assembly.pipeline.orchestrator.pipeline
        assembly.orchestrator.selectTopK(nowMs)
        val pcmFrames = mutableListOf<com.talkback.core.conference.runtime.PcmFrame>()
        val mixIds = linkedSetOf<String>()
        val topKIds =
            assembly.orchestrator.selection.currentTopK().members.map { it.sourceIdentity }.toSet()
        var lateCount = lastLateCount

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
            val beforeLate = pipeline.lateForPlayoutCount
            pipeline.pullSlot(
                sourceIdentity = fixture.sourceIdentity,
                incarnationId = admitted.incarnationId,
                slot = slot,
                slotMediaTimeMs = frame.mediaTimeMs,
                nowMs = nowMs,
            )
            if (pipeline.lateForPlayoutCount > beforeLate) {
                timing.recordPullLate(
                    source = fixture.sourceIdentity,
                    slot = slot,
                    nowMs = nowMs,
                    mediaTimeMs = frame.mediaTimeMs,
                    usefulDeadlineMs = frame.usefulDeadlineMs(),
                )
            }
            lateCount = pipeline.lateForPlayoutCount
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
            timing.recordEmptyPcmTick(nowMs)
            return null
        }

        val underrunBefore = assembly.playoutMetrics.underrunCount
        val lateBeforeWrite = pipeline.lateForPlayoutCount
        val mixStartNs = System.nanoTime()
        val block = com.talkback.core.conference.runtime.EqualWeightMixer.mix(pcmFrames)
        val mixDurationUs = (System.nanoTime() - mixStartNs) / 1_000L

        return DiagPlayoutPrep(
            product =
                PipelineLockPlayoutRefinement.PlayoutTickProduct(
                    mixedBlock = PipelineLockPlayoutRefinement.sealedMixedBlock(block),
                    decodeDurationUs = decodeDurationUs,
                    mixDurationUs = mixDurationUs,
                    topKCount = topKIds.size,
                    liveDecoders = assembly.orchestrator.decodeMix.decoderPool.liveCount(),
                    mixParticipantCount = mixIds.size,
                ),
            mixIds = mixIds,
            lateCount = lateCount,
            underrunBefore = underrunBefore,
            lateBeforeWrite = lateBeforeWrite,
            lastLateCount = lastLateCount,
        )
    }

    private fun finishDiagPlayoutTickOutsideLock(
        assembly: ConferenceMulticastRealMediaAssembly,
        prep: DiagPlayoutPrep,
        writeUs: Long,
        nowMs: Long,
        soak: SoakMetricsCollector,
        timing: LiveSlotTimingDiagnosticCollector,
    ): DiagTickResult {
        val pipeline = assembly.pipeline.orchestrator.pipeline
        val writeWallMs = System.currentTimeMillis()
        val underrunDelta =
            (assembly.playoutMetrics.underrunCount - prep.underrunBefore).coerceAtLeast(0L)
        val lateDelta =
            (pipeline.lateForPlayoutCount - prep.lateBeforeWrite).coerceAtLeast(0)

        soak.recordCycle(
            decodeDurationUs = prep.product.decodeDurationUs,
            mixDurationUs = prep.product.mixDurationUs,
            playoutBudgetUsedUs = prep.product.decodeDurationUs + prep.product.mixDurationUs,
            topKCount = prep.product.topKCount,
            liveDecoders = prep.product.liveDecoders,
            underrunCount = assembly.playoutMetrics.underrunCount,
        )
        timing.recordWriteCycle(
            writeWallMs = writeWallMs,
            underrunDelta = underrunDelta,
            lateDeltaThisCycle = lateDelta.toLong() +
                (prep.lateCount - prep.lastLateCount).coerceAtLeast(0).toLong(),
            pcmSources = prep.mixIds.size,
            decodeUs = prep.product.decodeDurationUs,
            mixUs = prep.product.mixDurationUs,
            playoutBudgetUs = prep.product.decodeDurationUs + prep.product.mixDurationUs + writeUs,
        )
        val triple = if (prep.mixIds.size >= 3) 1L else 0L
        return DiagTickResult(1L, triple, pipeline.lateForPlayoutCount)
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
        config: LiveSlotTimingDiagnosticConfig,
        startedAtMs: Long,
        endedAtMs: Long,
        metrics: Map<String, Any?>,
    ): File {
        val root = context.getExternalFilesDir(null) ?: context.filesDir
        val dir = File(root, "live-slot-timing").apply { mkdirs() }
        val file = File(dir, "${config.runId}-${config.role.name.lowercase()}.json")
        val json =
            JSONObject()
                .put("probe", LiveSlotTimingDiagnosticConstants.PROBE_NAME)
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
                .put(
                    "slotClock",
                    "SOAK5_PATH_MONOTONIC+HARNESS_RESYNC+mediaTimeMs=rxWallMs",
                )
                .put("playoutClock", "ABSOLUTE_20MS_DECOUPLED")
                .put(
                    "attributionContinuity",
                    "Slice5b-attempt5 media path; absolute playout clock (receive decoupled)",
                )
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
            pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LIVE_SLOT_TIMING").apply {
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
        }
    }

    private fun log(message: String) {
        Log.i(LiveSlotTimingDiagnosticConstants.LOG_TAG, message)
    }
}
