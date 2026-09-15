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

enum class Slice4bMulticastMultiSourceRole {
    SENDER,
    RECEIVER,
}

data class Slice4bMulticastMultiSourceConfig(
    val runId: String,
    val role: Slice4bMulticastMultiSourceRole,
    val durationSec: Int,
    val sourceIdentity: String? = null,
    val multicastAddress: String = Slice4MulticastNetworkConstants.DEFAULT_MULTICAST_ADDRESS,
    val mediaPort: Int = Slice4MulticastNetworkConstants.DEFAULT_MEDIA_PORT,
    val networkInterfaceName: String = Slice4MulticastNetworkConstants.DEFAULT_IFACE,
    val deviceLabel: String = "unknown",
    val nominalPps: Int = Slice4MulticastNetworkConstants.NOMINAL_PPS,
    val alignedMediaSlot: Int = Slice4bMulticastMultiSourceConstants.ALIGNED_MEDIA_SLOT,
)

/**
 * Phase 1 Slice 4b — M01/S1 + M02/S2 → M03 multi-source multicast SRTP convergence.
 */
class Slice4bMulticastMultiSourceRunner(
    private val context: Context,
) {
    fun run(config: Slice4bMulticastMultiSourceConfig): File {
        val wakeLock = acquireWakeLock(config.durationSec)
        return try {
            when (config.role) {
                Slice4bMulticastMultiSourceRole.SENDER -> runSender(config)
                Slice4bMulticastMultiSourceRole.RECEIVER -> runReceiver(config)
            }
        } finally {
            releaseWakeLock(wakeLock)
        }
    }

    private fun runSender(config: Slice4bMulticastMultiSourceConfig): File {
        val startedAtMs = System.currentTimeMillis()
        val endMs = startedAtMs + config.durationSec * 1000L
        val sourceIdentity =
            config.sourceIdentity
                ?: Slice4bMulticastMultiSourceConstants.SENDER_SOURCE_BY_DEVICE[config.deviceLabel]
                ?: error("sender requires sourceIdentity or known deviceLabel")
        val fixture =
            Phase1MediaHarness.threeSourceFixtures.first { it.sourceIdentity == sourceIdentity }
        val endpoint =
            MediaGroupEndpointBinding(
                multicastAddress = config.multicastAddress,
                mediaPort = config.mediaPort,
                underlayScopeId = "slice4b-${config.runId}",
            )
        val handle =
            TransportHandle(
                id = "slice4b-sender-$sourceIdentity",
                endpoint = endpoint,
                networkBinding =
                    ConferenceMulticastNetworkBinding.fromInterfaceName(config.networkInterfaceName),
                multicastLockPolicy = MulticastLockPolicy.WIFI_MULTICAST_LOCK,
            )

        val resources = AndroidRuntimeResources.forAndroidProduct(context)
        val transport = ConferenceMulticastRtpSrtpTransport(resources = resources, wiring = null)

        log(
            "SENDER_START runId=${config.runId} source=$sourceIdentity " +
                "mcast=${config.multicastAddress}:${config.mediaPort} iface=${config.networkInterfaceName}",
        )

        if (!transport.beginScope(handle, startedAtMs)) {
            return writeReport(
                config,
                startedAtMs,
                System.currentTimeMillis(),
                mapOf("error" to "TRANSPORT_SCOPE_FAILED"),
            )
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
            val iface = NetworkInterface.getByName(config.networkInterfaceName)
            if (iface != null) {
                socket.networkInterface = iface
            }
            socket.timeToLive = 32
            socket.loopbackMode = false
        } catch (e: Exception) {
            log("SENDER_IFACE_SETUP ${e.javaClass.simpleName}:${e.message}")
        }

        val opusPayload = OpusTestVectors.encodeTone(fixture.frequencyHz)
        var packetsSent = 0L
        var sendErrors = 0L
        val mediaSlot = config.alignedMediaSlot
        val intervalNs = 1_000_000_000L / config.nominalPps
        var nextSendNs = System.nanoTime()

        while (System.currentTimeMillis() < endMs) {
            val nowNs = System.nanoTime()
            if (nowNs < nextSendNs) {
                val sleepMs = ((nextSendNs - nowNs) / 1_000_000L).coerceAtMost(5L)
                if (sleepMs > 0) Thread.sleep(sleepMs)
                continue
            }
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
        val endedAtMs = System.currentTimeMillis()
        log("SENDER_END source=$sourceIdentity sent=$packetsSent errors=$sendErrors slot=$mediaSlot")
        return writeReport(
            config,
            startedAtMs,
            endedAtMs,
            mapOf(
                "sourceIdentity" to sourceIdentity,
                "packetsSent" to packetsSent,
                "sendErrors" to sendErrors,
                "alignedMediaSlot" to mediaSlot,
                "opusPayloadBytes" to opusPayload.size,
                "bind" to bindFactSnapshot(bind, rebind),
                "lastSendError" to snap.lastSendError,
                "transportObservability" to obsJson(snap),
            ),
        )
    }

    private fun runReceiver(config: Slice4bMulticastMultiSourceConfig): File {
        val startedAtMs = System.currentTimeMillis()
        val endMs = startedAtMs + config.durationSec * 1000L
        val receiverFixtures =
            Slice4bMulticastMultiSourceConstants.RECEIVER_SOURCES.map { id ->
                Phase1MediaHarness.threeSourceFixtures.first { it.sourceIdentity == id }
            }

        val assembly = ConferenceMulticastRealMediaAssembly.create(context)
        Phase1MediaHarness.installAuthority(
            assembly.orchestrator.authority.store,
            receiverFixtures,
        )
        assembly.orchestrator.authority.syncAllAdmittedToRuntime()

        val endpoint =
            MediaGroupEndpointBinding(
                multicastAddress = config.multicastAddress,
                mediaPort = config.mediaPort,
                underlayScopeId = "slice4b-${config.runId}",
            )
        val handle =
            TransportHandle(
                id = "slice4b-receiver",
                endpoint = endpoint,
                networkBinding =
                    ConferenceMulticastNetworkBinding.fromInterfaceName(config.networkInterfaceName),
                multicastLockPolicy = MulticastLockPolicy.WIFI_MULTICAST_LOCK,
            )

        log(
            "RECEIVER_START runId=${config.runId} sources=${receiverFixtures.map { it.sourceIdentity }} " +
                "mcast=${config.multicastAddress}:${config.mediaPort} iface=${config.networkInterfaceName}",
        )

        assembly.startPlayout()
        try {
            val transport = assembly.pipeline.transport
            if (!transport.beginScope(handle, startedAtMs)) {
                return writeReport(
                    config,
                    startedAtMs,
                    System.currentTimeMillis(),
                    mapOf("error" to "TRANSPORT_SCOPE_FAILED"),
                )
            }

            val bind = transport.lastSocketBindResult()
            if (bind == null || !bind.bindSucceeded) {
                transport.endScope()
                return writeReport(
                    config,
                    startedAtMs,
                    System.currentTimeMillis(),
                    mapOf(
                        "error" to "BIND_FAILED",
                        "bindError" to bind?.error,
                    ),
                )
            }
            transport.configureNetworkInterface(config.networkInterfaceName)

            val ingressAcceptedBySource = mutableMapOf<String, Long>()
            val ingressRejectedBySource = mutableMapOf<String, Long>()
            val decodeSuccessBySource = mutableMapOf<String, Long>()
            val perSourceJitterDepth = mutableMapOf<String, Int>()
            var datagramsReceived = 0L
            var ingressRejected = 0L
            var mixCycles = 0L
            var mixCyclesWithTwoParticipants = 0L
            var consecutiveNullSocket = 0
            val alignedSlot = config.alignedMediaSlot.toLong()
            var lastRejectReason: String? = null

            for (id in Slice4bMulticastMultiSourceConstants.RECEIVER_SOURCES) {
                ingressAcceptedBySource[id] = 0L
                ingressRejectedBySource[id] = 0L
                decodeSuccessBySource[id] = 0L
            }

            while (System.currentTimeMillis() < endMs) {
                val outcome = transport.receiveOnce(sourceIdentity = null)
                if (outcome == null) {
                    if (transport.observability.lastReceiveError == "SOCKET_NULL") {
                        consecutiveNullSocket += 1
                        if (consecutiveNullSocket >= 3) {
                            log("RECEIVER_ABORT SOCKET_NULL")
                            break
                        }
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

                val sourceIdentity =
                    WireSourceIdentityResolver.resolveBySsrc(payload, receiverFixtures)
                if (sourceIdentity == null) {
                    ingressRejected += 1
                    ingressRejectedBySource["UNKNOWN"] =
                        (ingressRejectedBySource["UNKNOWN"] ?: 0L) + 1L
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
                        if (admit.frameAdmit == FrameAdmitDisposition.QUEUED &&
                            admit.mediaSlot == alignedSlot
                        ) {
                            val mix =
                                tryAlignedMix(
                                    assembly = assembly,
                                    fixtures = receiverFixtures,
                                    slot = alignedSlot,
                                    nowMs = rxWallMs + MediaJitterConstants.MEDIA_SLOT_MS,
                                )
                            if (mix != null) {
                                mixCycles += 1
                                recordMixOutcome(mix, decodeSuccessBySource)
                                if (mix.mixCycle.mixParticipantIdentities.size >= 2) {
                                    mixCyclesWithTwoParticipants += 1
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

            // Final attempt for any remaining aligned-slot buffers.
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
                if (finalMix.mixCycle.mixParticipantIdentities.size >= 2) {
                    mixCyclesWithTwoParticipants += 1
                }
            }

            val snap = assembly.pipeline.observability.snapshot()
            transport.endScope()
            val endedAtMs = System.currentTimeMillis()

            val gateObservesBoth =
                ingressAcceptedBySource["S1"]!! > 0 && ingressAcceptedBySource["S2"]!! > 0
            val gateIngressS1 = ingressAcceptedBySource["S1"]!! > 0
            val gateIngressS2 = ingressAcceptedBySource["S2"]!! > 0
            val gateDecodeS1 = decodeSuccessBySource["S1"]!! > 0
            val gateDecodeS2 = decodeSuccessBySource["S2"]!! > 0
            val gateDualMix = mixCyclesWithTwoParticipants > 0
            val gateAudioTrack = assembly.playoutMetrics.successfulWrites > 0
            val gateIngressRejectedZero = ingressRejected == 0L

            val metrics =
                mapOf(
                    "datagramsReceived" to datagramsReceived,
                    "ingressAccepted" to ingressAcceptedBySource.values.sum(),
                    "ingressRejected" to ingressRejected,
                    "ingressAcceptedBySource" to ingressAcceptedBySource,
                    "ingressRejectedBySource" to ingressRejectedBySource,
                    "decodeSuccessBySource" to decodeSuccessBySource,
                    "mixCycles" to mixCycles,
                    "mixCyclesWithTwoParticipants" to mixCyclesWithTwoParticipants,
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
                    "gateObservesBothSources" to gateObservesBoth,
                    "gateIngressAcceptedS1" to gateIngressS1,
                    "gateIngressAcceptedS2" to gateIngressS2,
                    "gateDecodeSuccessS1" to gateDecodeS1,
                    "gateDecodeSuccessS2" to gateDecodeS2,
                    "gateDualParticipantMix" to gateDualMix,
                    "gateAudioTrackWritesOk" to gateAudioTrack,
                    "gateIngressRejectedZero" to gateIngressRejectedZero,
                    "gateAllPass" to
                        (
                            gateObservesBoth &&
                                gateIngressS1 &&
                                gateIngressS2 &&
                                gateDecodeS1 &&
                                gateDecodeS2 &&
                                gateDualMix &&
                                gateAudioTrack &&
                                gateIngressRejectedZero
                        ),
                )
            log(
                "RECEIVER_END rx=$datagramsReceived ingressS1=${ingressAcceptedBySource["S1"]} " +
                    "ingressS2=${ingressAcceptedBySource["S2"]} dualMix=$mixCyclesWithTwoParticipants " +
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
                pipeline.peekBufferedFrame(
                    fixture.sourceIdentity,
                    admitted.incarnationId,
                    slot,
                ) ?: return null
            }
        val slotMediaTimeMs = buffered.maxOf { it.mediaTimeMs }
        return assembly.pipeline.runMixPlayoutCycle(
            nowMs = nowMs,
            slot = slot,
            slotMediaTimeMs = slotMediaTimeMs,
        )
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
        config: Slice4bMulticastMultiSourceConfig,
        startedAtMs: Long,
        endedAtMs: Long,
        metrics: Map<String, Any?>,
    ): File {
        val root = context.getExternalFilesDir(null) ?: context.filesDir
        val dir = File(root, "slice4b-mcast-multi").apply { mkdirs() }
        val file = File(dir, "${config.runId}-${config.role.name.lowercase()}.json")
        val json =
            JSONObject()
                .put("probe", Slice4bMulticastMultiSourceConstants.PROBE_NAME)
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
                .put("alignedMediaSlot", config.alignedMediaSlot)
        val m = metricsToJson(metrics)
        json.put("metrics", m)
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
            pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SLICE4B_MC_MULTI").apply {
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
        Log.i(Slice4bMulticastMultiSourceConstants.LOG_TAG, message)
    }
}
