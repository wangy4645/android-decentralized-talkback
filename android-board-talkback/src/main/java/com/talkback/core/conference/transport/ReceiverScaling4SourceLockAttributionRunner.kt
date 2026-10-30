package com.talkback.core.conference.transport

import android.content.Context
import android.os.PowerManager
import android.util.Log
import com.talkback.core.conference.runtime.AdmittedMediaFrame
import com.talkback.core.conference.runtime.FrameAdmitDisposition
import com.talkback.core.conference.runtime.MediaGroupEndpointBinding
import com.talkback.core.conference.runtime.MediaJitterConstants
import com.talkback.core.conference.runtime.MulticastSocketTransportRebindSeam
import com.talkback.core.conference.runtime.OpusTestVectors
import com.talkback.core.conference.runtime.TransportHandle
import com.talkback.core.conference.wire.WireIngressResult
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

data class ReceiverScaling4SourceLockAttributionConfig(
    val runId: String,
    val durationSec: Int = ReceiverScaling4SourceLockAttributionConstants.DEFAULT_DURATION_SEC,
    val multicastAddress: String = Slice4MulticastNetworkConstants.DEFAULT_MULTICAST_ADDRESS,
    val mediaPort: Int = Slice4MulticastNetworkConstants.DEFAULT_MEDIA_PORT,
    val networkInterfaceName: String = Slice4MulticastNetworkConstants.DEFAULT_IFACE,
    val deviceLabel: String = "M04",
    val nominalPps: Int = Slice4MulticastNetworkConstants.NOMINAL_PPS,
)

/**
 * 4-source pipelineLock hold attribution — receiver only.
 *
 * Answers where ~16ms hold tail is spent: decode / mix / jitter pull / synthetic admit / scope overhead.
 */
class ReceiverScaling4SourceLockAttributionRunner(
    private val context: Context,
) {
    fun run(config: ReceiverScaling4SourceLockAttributionConfig): File {
        val wakeLock = acquireWakeLock(config.durationSec)
        return try {
            runReceiver(config)
        } finally {
            releaseWakeLock(wakeLock)
        }
    }

    private fun runReceiver(config: ReceiverScaling4SourceLockAttributionConfig): File {
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
                underlayScopeId = "rx-scaling4-lock-${config.runId}",
            )
        val handle =
            TransportHandle(
                id = "rx-scaling4-lock-receiver",
                endpoint = endpoint,
                networkBinding =
                    ConferenceMulticastNetworkBinding.fromInterfaceName(config.networkInterfaceName),
                multicastLockPolicy = MulticastLockPolicy.WIFI_MULTICAST_LOCK,
            )

        log("LOCK_ATTRIBUTION_START durationSec=${config.durationSec}")
        val soak = SoakMetricsCollector()
        val attribution = PipelineLockAttributionCollector()
        assembly.startPlayout()
        val pipelineLock = Any()
        var mixCycles = 0L
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

            val ingressAcceptedBySource =
                ReceiverScaling4SourceConstants.RECEIVER_SOURCES.associateWith { 0L }.toMutableMap()
            var datagramsReceived = 0L
            var syntheticPacketsInjected = 0L
            var consecutiveNullSocket = 0

            playoutClock =
                AbsoluteMediaPlayoutClock(
                    anchorMs = startedAtMs,
                    threadName = "rx-scaling4-lock-playout",
                ) { tickMediaTimeMs ->
                    val waitStartNs = System.nanoTime()
                    val insideLock =
                        synchronized(pipelineLock) {
                            val waitUs = (System.nanoTime() - waitStartNs) / 1_000L
                            val holdStartNs = System.nanoTime()
                            if (System.currentTimeMillis() >= endMs) return@AbsoluteMediaPlayoutClock
                            val tickResult =
                                runAttributedPlayoutTick(
                                    assembly = assembly,
                                    fixtures = fixtures,
                                    nowMs = tickMediaTimeMs,
                                    attribution = attribution,
                                )
                            val totalHoldUs = (System.nanoTime() - holdStartNs) / 1_000L
                            tickResult?.let {
                                attribution.recordEntry(
                                    entryPoint = PipelineLockAttributionCollector.EntryPoint.PLAYOUT_TICK,
                                    totalHoldUs = totalHoldUs,
                                    attributedUs = it.attributedUs,
                                )
                            }
                            soak.recordLockSample(waitUs = waitUs, holdUs = totalHoldUs)
                            Triple(tickResult?.product, waitUs, totalHoldUs)
                        }
                    insideLock.first?.let { product ->
                        val writeStartNs = System.nanoTime()
                        PipelineLockPlayoutRefinement.writeAudioTrackOutsideLock(
                            assembly.orchestrator,
                            product.mixedBlock,
                            tickMediaTimeMs,
                        )
                        val playoutUs = (System.nanoTime() - writeStartNs) / 1_000L
                        attribution.recordCategory(
                            PipelineLockAttributionCollector.Category.PLAYOUT_AUDIOTRACK,
                            playoutUs,
                        )
                        soak.recordAudioTrackWrite(playoutUs)
                        mixCycles += 1
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
                                val holdStartNs = System.nanoTime()
                                var attributedUs = 0L
                                val admitStartNs = System.nanoTime()
                                val admit =
                                    assembly.pipeline.admitProtectedDatagram(
                                        sourceIdentity = syntheticFixture.sourceIdentity,
                                        datagram = payload,
                                        rxWallMs = rxWallMs,
                                        mediaTimeMs = rxWallMs,
                                    )
                                val admitUs = (System.nanoTime() - admitStartNs) / 1_000L
                                attribution.recordCategory(
                                    PipelineLockAttributionCollector.Category.SYNTHETIC_ADMIT,
                                    admitUs,
                                )
                                attributedUs += admitUs
                                when (val ingress = admit.ingress) {
                                    is WireIngressResult.Accepted -> {
                                        syntheticPacketsInjected += 1
                                        ingressAcceptedBySource[syntheticFixture.sourceIdentity] =
                                            (ingressAcceptedBySource[syntheticFixture.sourceIdentity] ?: 0L) + 1L
                                        if (admit.frameAdmit == FrameAdmitDisposition.REORDER_DISPLACEMENT_EXCEEDED) {
                                            val resyncStartNs = System.nanoTime()
                                            maybeSoakLiveEdgeResync(
                                                assembly = assembly,
                                                sourceIdentity = syntheticFixture.sourceIdentity,
                                                liveSlot = admit.mediaSlot,
                                                mediaTimeMs = admit.mediaTimeMs ?: rxWallMs,
                                                arrivalMs = rxWallMs,
                                                nowMs = rxWallMs,
                                            )
                                            val resyncUs = (System.nanoTime() - resyncStartNs) / 1_000L
                                            attribution.recordCategory(
                                                PipelineLockAttributionCollector.Category.SYNTHETIC_RESYNC,
                                                resyncUs,
                                            )
                                            attributedUs += resyncUs
                                        }
                                    }
                                    is WireIngressResult.Rejected -> Unit
                                }
                                val totalHoldUs = (System.nanoTime() - holdStartNs) / 1_000L
                                attribution.recordEntry(
                                    entryPoint = PipelineLockAttributionCollector.EntryPoint.SYNTHETIC_INJECT,
                                    totalHoldUs = totalHoldUs,
                                    attributedUs = attributedUs,
                                )
                            }
                            nextSendNs += syntheticIntervalNs
                        }
                    },
                    "rx-scaling4-lock-synthetic",
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
                    ?: continue

                synchronized(pipelineLock) {
                    val holdStartNs = System.nanoTime()
                    var attributedUs = 0L
                    val admitStartNs = System.nanoTime()
                    val admit =
                        assembly.pipeline.admitProtectedDatagram(
                            sourceIdentity = sourceIdentity,
                            datagram = payload,
                            rxWallMs = rxWallMs,
                            mediaTimeMs = rxWallMs,
                        )
                    val admitUs = (System.nanoTime() - admitStartNs) / 1_000L
                    attribution.recordCategory(
                        PipelineLockAttributionCollector.Category.NETWORK_ADMIT,
                        admitUs,
                    )
                    attributedUs += admitUs
                    when (val ingress = admit.ingress) {
                        is WireIngressResult.Accepted -> {
                            ingressAcceptedBySource[sourceIdentity] =
                                (ingressAcceptedBySource[sourceIdentity] ?: 0L) + 1L
                            if (admit.frameAdmit == FrameAdmitDisposition.REORDER_DISPLACEMENT_EXCEEDED) {
                                val resyncStartNs = System.nanoTime()
                                maybeSoakLiveEdgeResync(
                                    assembly = assembly,
                                    sourceIdentity = sourceIdentity,
                                    liveSlot = admit.mediaSlot,
                                    mediaTimeMs = admit.mediaTimeMs ?: rxWallMs,
                                    arrivalMs = rxWallMs,
                                    nowMs = rxWallMs,
                                )
                                val resyncUs = (System.nanoTime() - resyncStartNs) / 1_000L
                                attribution.recordCategory(
                                    PipelineLockAttributionCollector.Category.NETWORK_RESYNC,
                                    resyncUs,
                                )
                                attributedUs += resyncUs
                            }
                        }
                        is WireIngressResult.Rejected -> Unit
                    }
                    val totalHoldUs = (System.nanoTime() - holdStartNs) / 1_000L
                    attribution.recordEntry(
                        entryPoint = PipelineLockAttributionCollector.EntryPoint.NETWORK_RECEIVE,
                        totalHoldUs = totalHoldUs,
                        attributedUs = attributedUs,
                    )
                }
            }

            syntheticStop.set(true)
            syntheticThread.join(5_000L)
            transport.endScope()
            val endedAtMs = System.currentTimeMillis()
            val timingSnap = soak.snapshot()
            val attrSnap = attribution.snapshot()
            val lockHoldP99 =
                (timingSnap["pipelineLockUs"] as Map<*, *>)["hold"]?.let { it as Map<*, *> }?.get("p99") as? Long ?: 0L
            val metrics =
                mapOf(
                    "probeIntent" to "PIPELINE_LOCK_HOLD_ATTRIBUTION_ONLY",
                    "referenceScalingRun" to ReceiverScaling4SourceLockAttributionConstants.REFERENCE_SCALING_RUN,
                    "referenceHoldP99Us" to 16247L,
                    "evidenceClass" to ReceiverScaling4SourceLockAttributionConstants.EVIDENCE_CLASS,
                    "topology" to ReceiverScaling4SourceConstants.TOPOLOGY_NOTE,
                    "notFourDeviceFieldEvidence" to true,
                    "observationOnly" to true,
                    "capacityGates" to "NONE",
                    "lockScopeRefinement" to PipelineLockPlayoutRefinement.FIX_NAME,
                    "datagramsReceived" to datagramsReceived,
                    "syntheticPacketsInjected" to syntheticPacketsInjected,
                    "ingressAcceptedBySource" to ingressAcceptedBySource,
                    "mixCycles" to mixCycles,
                    "pipelineLockHoldP99Us" to lockHoldP99,
                    "soakTiming" to timingSnap,
                    "lockAttribution" to attrSnap,
                    "attributionComplete" to
                        (
                            ingressAcceptedBySource.values.all { it > 0 } &&
                                mixCycles > 0 &&
                                (attrSnap["dominance"] as Map<*, *>)["primaryCategory"] != "NONE"
                        ),
                )
            log("LOCK_ATTRIBUTION_END holdP99=$lockHoldP99 interpretation=${(attrSnap["dominance"] as Map<*, *>)["interpretation"]}")
            return writeReport(config, startedAtMs, endedAtMs, metrics)
        } finally {
            syntheticStop.set(true)
            syntheticThread?.join(2_000L)
            playoutClock?.stop()
            assembly.stopPlayout()
        }
    }

    private data class AttributedPlayoutInsideLock(
        val product: PipelineLockPlayoutRefinement.PlayoutTickProduct,
        val attributedUs: Long,
    )

    private fun runAttributedPlayoutTick(
        assembly: ConferenceMulticastRealMediaAssembly,
        fixtures: List<Phase1MediaHarness.SourceFixture>,
        nowMs: Long,
        attribution: PipelineLockAttributionCollector,
    ): AttributedPlayoutInsideLock? {
        var attributedUs = 0L
        val pipeline = assembly.pipeline.orchestrator.pipeline

        val topKStartNs = System.nanoTime()
        assembly.orchestrator.selectTopK(nowMs)
        val topKUs = (System.nanoTime() - topKStartNs) / 1_000L
        attribution.recordCategory(PipelineLockAttributionCollector.Category.PLAYOUT_TOPK, topKUs)
        attributedUs += topKUs

        val pcmFrames = mutableListOf<com.talkback.core.conference.runtime.PcmFrame>()
        for (fixture in fixtures) {
            val admitted =
                assembly.orchestrator.authority.currentAdmitted(fixture.sourceIdentity)
                    ?: continue
            val pullStartNs = System.nanoTime()
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
            val pullUs = (System.nanoTime() - pullStartNs) / 1_000L
            attribution.recordCategory(PipelineLockAttributionCollector.Category.PLAYOUT_JITTER_PULL, pullUs)
            attributedUs += pullUs

            val decodeStartNs = System.nanoTime()
            val pcm =
                assembly.orchestrator.decodeMix.produceMixablePcm(
                    sourceIdentity = fixture.sourceIdentity,
                    incarnationId = admitted.incarnationId,
                    mediaSlot = slot,
                    nowMs = nowMs,
                )
            val decodeUs = (System.nanoTime() - decodeStartNs) / 1_000L
            attribution.recordCategory(PipelineLockAttributionCollector.Category.PLAYOUT_DECODE, decodeUs)
            attributedUs += decodeUs
            if (pcm != null) pcmFrames += pcm
        }
        if (pcmFrames.isEmpty()) return null

        val mixStartNs = System.nanoTime()
        val block = com.talkback.core.conference.runtime.EqualWeightMixer.mix(pcmFrames)
        val mixUs = (System.nanoTime() - mixStartNs) / 1_000L
        attribution.recordCategory(PipelineLockAttributionCollector.Category.PLAYOUT_MIX, mixUs)
        attributedUs += mixUs
        val topKCount =
            assembly.orchestrator.selection.currentTopK().members.size

        return AttributedPlayoutInsideLock(
            product =
                PipelineLockPlayoutRefinement.PlayoutTickProduct(
                    mixedBlock = PipelineLockPlayoutRefinement.sealedMixedBlock(block),
                    decodeDurationUs = attributedUs - topKUs - mixUs,
                    mixDurationUs = mixUs,
                    topKCount = topKCount,
                    liveDecoders = assembly.orchestrator.decodeMix.decoderPool.liveCount(),
                    mixParticipantCount = pcmFrames.size,
                ),
            attributedUs = attributedUs,
        )
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

    private fun writeReport(
        config: ReceiverScaling4SourceLockAttributionConfig,
        startedAtMs: Long,
        endedAtMs: Long,
        metrics: Map<String, Any?>,
    ): File {
        val root = context.getExternalFilesDir(null) ?: context.filesDir
        val dir = File(root, ReceiverScaling4SourceLockAttributionConstants.REPORT_DIR).apply { mkdirs() }
        val file = File(dir, "${config.runId}-receiver.json")
        val json =
            JSONObject()
                .put("probe", ReceiverScaling4SourceLockAttributionConstants.PROBE_NAME)
                .put("evidenceClass", ReceiverScaling4SourceLockAttributionConstants.EVIDENCE_CLASS)
                .put("topology", ReceiverScaling4SourceConstants.TOPOLOGY_NOTE)
                .put("notFourDeviceFieldEvidence", true)
                .put("runId", config.runId)
                .put("role", "RECEIVER")
                .put("deviceLabel", config.deviceLabel)
                .put("startedAtMs", startedAtMs)
                .put("endedAtMs", endedAtMs)
                .put("durationSec", config.durationSec)
                .put("referenceScalingRun", ReceiverScaling4SourceLockAttributionConstants.REFERENCE_SCALING_RUN)
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
            else -> value
        }

    private fun acquireWakeLock(durationSec: Int): PowerManager.WakeLock? {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return null
        return try {
            pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RX_SCALING4_LOCK_ATTR").apply {
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
        Log.i(ReceiverScaling4SourceLockAttributionConstants.LOG_TAG, message)
    }
}
