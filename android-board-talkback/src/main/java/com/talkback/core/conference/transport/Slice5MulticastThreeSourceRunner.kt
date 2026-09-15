package com.talkback.core.conference.transport

import android.content.Context
import android.os.PowerManager
import android.util.Log
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

enum class Slice5MulticastThreeSourceRole {
    SENDER,
    RECEIVER,
}

data class Slice5MulticastThreeSourceConfig(
    val runId: String,
    val role: Slice5MulticastThreeSourceRole,
    val durationSec: Int,
    val sourceIdentity: String? = null,
    val slotMode: Slice5SlotMode = Slice5SlotMode.ALIGNED_SLOT,
    val baseSeq: Int = Slice5MulticastThreeSourceConstants.DEFAULT_BASE_SEQ,
    val multicastAddress: String = Slice4MulticastNetworkConstants.DEFAULT_MULTICAST_ADDRESS,
    val mediaPort: Int = Slice4MulticastNetworkConstants.DEFAULT_MEDIA_PORT,
    val networkInterfaceName: String = Slice4MulticastNetworkConstants.DEFAULT_IFACE,
    val deviceLabel: String = "unknown",
    val nominalPps: Int = Slice4MulticastNetworkConstants.NOMINAL_PPS,
    val alignedMediaSlot: Int = Slice5MulticastThreeSourceConstants.ALIGNED_MEDIA_SLOT,
)

class Slice5MulticastThreeSourceRunner(
    private val context: Context,
) {
    fun run(config: Slice5MulticastThreeSourceConfig): File {
        val wakeLock = acquireWakeLock(config.durationSec)
        return try {
            when (config.role) {
                Slice5MulticastThreeSourceRole.SENDER -> runSender(config)
                Slice5MulticastThreeSourceRole.RECEIVER -> runReceiver(config)
            }
        } finally {
            releaseWakeLock(wakeLock)
        }
    }

    private fun runSender(config: Slice5MulticastThreeSourceConfig): File {
        val startedAtMs = System.currentTimeMillis()
        val endMs = startedAtMs + config.durationSec * 1000L
        val sourceIdentity =
            config.sourceIdentity
                ?: Slice5MulticastThreeSourceConstants.SENDER_SOURCE_BY_DEVICE[config.deviceLabel]
                ?: error("sender requires sourceIdentity or known deviceLabel")
        val fixture =
            Phase1MediaHarness.threeSourceFixtures.first { it.sourceIdentity == sourceIdentity }
        val endpoint =
            MediaGroupEndpointBinding(
                multicastAddress = config.multicastAddress,
                mediaPort = config.mediaPort,
                underlayScopeId = "slice5-${config.runId}",
            )
        val handle =
            TransportHandle(
                id = "slice5-sender-$sourceIdentity",
                endpoint = endpoint,
                networkBinding =
                    ConferenceMulticastNetworkBinding.fromInterfaceName(config.networkInterfaceName),
                multicastLockPolicy = MulticastLockPolicy.WIFI_MULTICAST_LOCK,
            )

        val resources = AndroidRuntimeResources.forAndroidProduct(context)
        val transport = ConferenceMulticastRtpSrtpTransport(resources = resources, wiring = null)

        log(
            "SENDER_START runId=${config.runId} source=$sourceIdentity mode=${config.slotMode} " +
                "mcast=${config.multicastAddress}:${config.mediaPort}",
        )

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
                mapOf("error" to "SOCKET_NULL_AFTER_SCOPE", "bind" to bindFactSnapshot(bind, rebind)),
            )
        }

        try {
            NetworkInterface.getByName(config.networkInterfaceName)?.let { socket.networkInterface = it }
            socket.timeToLive = 32
            socket.loopbackMode = false
        } catch (e: Exception) {
            log("SENDER_IFACE_SETUP ${e.javaClass.simpleName}:${e.message}")
        }

        val opusPayload = OpusTestVectors.encodeTone(fixture.frequencyHz)
        var packetsSent = 0L
        var sendErrors = 0L
        var lastMediaSlot = -1
        val intervalNs = 1_000_000_000L / config.nominalPps
        var nextSendNs = System.nanoTime()

        while (System.currentTimeMillis() < endMs) {
            val nowNs = System.nanoTime()
            if (nowNs < nextSendNs) {
                val sleepMs = ((nextSendNs - nowNs) / 1_000_000L).coerceAtMost(5L)
                if (sleepMs > 0) Thread.sleep(sleepMs)
                continue
            }
            val mediaSlot =
                when (config.slotMode) {
                    Slice5SlotMode.ALIGNED_SLOT -> config.alignedMediaSlot
                    Slice5SlotMode.LIVE_SLOT -> {
                        val elapsedMs = System.currentTimeMillis() - startedAtMs
                        config.baseSeq +
                            (elapsedMs / MediaJitterConstants.MEDIA_SLOT_MS).toInt()
                    }
                }
            if (config.slotMode == Slice5SlotMode.LIVE_SLOT && mediaSlot == lastMediaSlot) {
                nextSendNs += intervalNs
                continue
            }
            lastMediaSlot = mediaSlot
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
                "slotMode" to config.slotMode.name,
                "packetsSent" to packetsSent,
                "sendErrors" to sendErrors,
                "lastMediaSlot" to lastMediaSlot,
                "baseSeq" to config.baseSeq,
                "alignedMediaSlot" to config.alignedMediaSlot,
                "bind" to bindFactSnapshot(bind, rebind),
                "lastSendError" to snap.lastSendError,
                "transportObservability" to obsJson(snap),
            ),
        )
    }

    private fun runReceiver(config: Slice5MulticastThreeSourceConfig): File {
        val startedAtMs = System.currentTimeMillis()
        val endMs = startedAtMs + config.durationSec * 1000L
        val receiverFixtures =
            Slice5MulticastThreeSourceConstants.RECEIVER_SOURCES.map { id ->
                Phase1MediaHarness.threeSourceFixtures.first { it.sourceIdentity == id }
            }

        val assembly = ConferenceMulticastRealMediaAssembly.create(context)
        Phase1MediaHarness.installAuthority(assembly.orchestrator.authority.store, receiverFixtures)
        assembly.orchestrator.authority.syncAllAdmittedToRuntime()

        val endpoint =
            MediaGroupEndpointBinding(
                multicastAddress = config.multicastAddress,
                mediaPort = config.mediaPort,
                underlayScopeId = "slice5-${config.runId}",
            )
        val handle =
            TransportHandle(
                id = "slice5-receiver",
                endpoint = endpoint,
                networkBinding =
                    ConferenceMulticastNetworkBinding.fromInterfaceName(config.networkInterfaceName),
                multicastLockPolicy = MulticastLockPolicy.WIFI_MULTICAST_LOCK,
            )

        log(
            "RECEIVER_START runId=${config.runId} mode=${config.slotMode} " +
                "sources=${receiverFixtures.map { it.sourceIdentity }}",
        )

        assembly.startPlayout()
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

            val sourceIds = Slice5MulticastThreeSourceConstants.RECEIVER_SOURCES
            val ingressAcceptedBySource = sourceIds.associateWith { 0L }.toMutableMap()
            val ingressRejectedBySource = sourceIds.associateWith { 0L }.toMutableMap()
            val decodeSuccessBySource = sourceIds.associateWith { 0L }.toMutableMap()
            val perSourceJitterDepth = mutableMapOf<String, Int>()
            var datagramsReceived = 0L
            var ingressRejected = 0L
            var mixCycles = 0L
            var mixCyclesWithThreeParticipants = 0L
            var consecutiveNullSocket = 0
            val alignedSlot = config.alignedMediaSlot.toLong()
            var lastRejectReason: String? = null

            while (System.currentTimeMillis() < endMs) {
                val outcome = transport.receiveOnce(sourceIdentity = null)
                if (outcome == null) {
                    if (transport.observability.lastReceiveError == "SOCKET_NULL") {
                        consecutiveNullSocket += 1
                        if (consecutiveNullSocket >= 3) break
                        Thread.sleep(20)
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

                val sourceIdentity = WireSourceIdentityResolver.resolveBySsrc(payload, receiverFixtures)
                if (sourceIdentity == null) {
                    ingressRejected += 1
                    lastRejectReason = "UNKNOWN_SSRC"
                    continue
                }

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
                        perSourceJitterDepth[sourceIdentity] = admit.jitterDepth
                        val slot = admit.mediaSlot ?: continue
                        val shouldTryMix =
                            when (config.slotMode) {
                                Slice5SlotMode.ALIGNED_SLOT -> slot == alignedSlot
                                Slice5SlotMode.LIVE_SLOT -> true
                            }
                        if (admit.frameAdmit == FrameAdmitDisposition.QUEUED && shouldTryMix) {
                            val mix =
                                tryAlignedMix(
                                    assembly = assembly,
                                    fixtures = receiverFixtures,
                                    slot = slot,
                                    nowMs = rxWallMs + MediaJitterConstants.MEDIA_SLOT_MS,
                                )
                            if (mix != null) {
                                mixCycles += 1
                                recordMixOutcome(mix, decodeSuccessBySource)
                                if (mix.mixCycle.mixParticipantIdentities.size >= 3) {
                                    mixCyclesWithThreeParticipants += 1
                                }
                            }
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

            if (config.slotMode == Slice5SlotMode.ALIGNED_SLOT) {
                val finalMix =
                    tryAlignedMix(
                        assembly = assembly,
                        fixtures = receiverFixtures,
                        slot = alignedSlot,
                        nowMs = System.currentTimeMillis(),
                    )
                if (finalMix != null) {
                    mixCycles += 1
                    recordMixOutcome(finalMix, decodeSuccessBySource)
                    if (finalMix.mixCycle.mixParticipantIdentities.size >= 3) {
                        mixCyclesWithThreeParticipants += 1
                    }
                }
            }

            val snap = assembly.pipeline.observability.snapshot()
            transport.endScope()
            val endedAtMs = System.currentTimeMillis()

            val gateIngress = sourceIds.all { (ingressAcceptedBySource[it] ?: 0L) > 0L }
            val gateDecode = sourceIds.all { (decodeSuccessBySource[it] ?: 0L) > 0L }
            val gateTripleMix = mixCyclesWithThreeParticipants > 0
            val gateAudioTrack = assembly.playoutMetrics.successfulWrites > 0
            val gateIngressRejectedZero = ingressRejected == 0L

            val metrics =
                mapOf(
                    "slotMode" to config.slotMode.name,
                    "datagramsReceived" to datagramsReceived,
                    "ingressAccepted" to ingressAcceptedBySource.values.sum(),
                    "ingressRejected" to ingressRejected,
                    "ingressAcceptedBySource" to ingressAcceptedBySource,
                    "ingressRejectedBySource" to ingressRejectedBySource,
                    "decodeSuccessBySource" to decodeSuccessBySource,
                    "mixCycles" to mixCycles,
                    "mixCyclesWithThreeParticipants" to mixCyclesWithThreeParticipants,
                    "alignedMediaSlot" to alignedSlot,
                    "successfulWrites" to assembly.playoutMetrics.successfulWrites,
                    "failedWrites" to assembly.playoutMetrics.failedWrites,
                    "underrunCount" to assembly.playoutMetrics.underrunCount,
                    "perSourceJitterDepth" to perSourceJitterDepth,
                    "lastRejectReason" to lastRejectReason,
                    "bindSucceeded" to bind.bindSucceeded,
                    "bindInterface" to bind.networkInterfaceName,
                    "joinSucceeded" to transport.lastJoinSucceeded(),
                    "transportObservability" to obsJson(snap),
                    "gateObservesAllThreeSources" to gateIngress,
                    "gateIngressAcceptedS1" to ((ingressAcceptedBySource["S1"] ?: 0L) > 0L),
                    "gateIngressAcceptedS2" to ((ingressAcceptedBySource["S2"] ?: 0L) > 0L),
                    "gateIngressAcceptedS3" to ((ingressAcceptedBySource["S3"] ?: 0L) > 0L),
                    "gateDecodeSuccessS1" to ((decodeSuccessBySource["S1"] ?: 0L) > 0L),
                    "gateDecodeSuccessS2" to ((decodeSuccessBySource["S2"] ?: 0L) > 0L),
                    "gateDecodeSuccessS3" to ((decodeSuccessBySource["S3"] ?: 0L) > 0L),
                    "gateTripleParticipantMix" to gateTripleMix,
                    "gateAudioTrackWritesOk" to gateAudioTrack,
                    "gateIngressRejectedZero" to gateIngressRejectedZero,
                    "gateAllPass" to
                        (
                            gateIngress &&
                                gateDecode &&
                                gateTripleMix &&
                                gateAudioTrack &&
                                gateIngressRejectedZero
                        ),
                )
            log(
                "RECEIVER_END rx=$datagramsReceived tripleMix=$mixCyclesWithThreeParticipants " +
                    "writes=${assembly.playoutMetrics.successfulWrites}",
            )
            return writeReport(config, startedAtMs, endedAtMs, metrics)
        } finally {
            assembly.stopPlayout()
        }
    }

    private fun tryAlignedMix(
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
        return assembly.pipeline.runMixPlayoutCycle(nowMs = nowMs, slot = slot, slotMediaTimeMs = slotMediaTimeMs)
    }

    private fun recordMixOutcome(
        mix: PipelinePlayoutResult,
        decodeSuccessBySource: MutableMap<String, Long>,
    ) {
        for (id in mix.mixCycle.decodeInvocationIdentities) {
            decodeSuccessBySource[id] = (decodeSuccessBySource[id] ?: 0L) + 1L
        }
    }

    private fun bindFactSnapshot(
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
            .put("perSourceJitterDepth", JSONObject(snap.perSourceJitterDepth))

    private fun writeReport(
        config: Slice5MulticastThreeSourceConfig,
        startedAtMs: Long,
        endedAtMs: Long,
        metrics: Map<String, Any?>,
    ): File {
        val root = context.getExternalFilesDir(null) ?: context.filesDir
        val dir = File(root, "slice5-mcast-three").apply { mkdirs() }
        val file = File(dir, "${config.runId}-${config.role.name.lowercase()}.json")
        val json =
            JSONObject()
                .put("probe", Slice5MulticastThreeSourceConstants.PROBE_NAME)
                .put("runId", config.runId)
                .put("role", config.role.name)
                .put("deviceLabel", config.deviceLabel)
                .put("sourceIdentity", config.sourceIdentity)
                .put("slotMode", config.slotMode.name)
                .put("startedAtMs", startedAtMs)
                .put("endedAtMs", endedAtMs)
                .put("durationSec", config.durationSec)
                .put("multicastAddress", config.multicastAddress)
                .put("mediaPort", config.mediaPort)
                .put("networkInterfaceName", config.networkInterfaceName)
                .put("nominalPps", config.nominalPps)
                .put("alignedMediaSlot", config.alignedMediaSlot)
                .put("baseSeq", config.baseSeq)
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
            is JSONObject -> value
            else -> value
        }

    private fun acquireWakeLock(durationSec: Int): PowerManager.WakeLock? {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return null
        return try {
            pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SLICE5_MC_THREE").apply {
                setReferenceCounted(false)
                acquire((durationSec + 30) * 1000L)
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
        Log.i(Slice5MulticastThreeSourceConstants.LOG_TAG, message)
    }
}
