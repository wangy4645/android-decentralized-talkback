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

enum class Slice4MulticastNetworkRole {
    SENDER,
    RECEIVER,
}

data class Slice4MulticastNetworkConfig(
    val runId: String,
    val role: Slice4MulticastNetworkRole,
    val durationSec: Int,
    val multicastAddress: String = Slice4MulticastNetworkConstants.DEFAULT_MULTICAST_ADDRESS,
    val mediaPort: Int = Slice4MulticastNetworkConstants.DEFAULT_MEDIA_PORT,
    val networkInterfaceName: String = Slice4MulticastNetworkConstants.DEFAULT_IFACE,
    val deviceLabel: String = "unknown",
    val nominalPps: Int = Slice4MulticastNetworkConstants.NOMINAL_PPS,
)

/**
 * Phase 1 Slice 4 — M01→M03 dual-node multicast SRTP → Opus → AudioTrack.
 */
class Slice4MulticastNetworkRunner(
    private val context: Context,
) {
    fun run(config: Slice4MulticastNetworkConfig): File {
        val wakeLock = acquireWakeLock(config.durationSec)
        return try {
            when (config.role) {
                Slice4MulticastNetworkRole.SENDER -> runSender(config)
                Slice4MulticastNetworkRole.RECEIVER -> runReceiver(config)
            }
        } finally {
            releaseWakeLock(wakeLock)
        }
    }

    private fun runSender(config: Slice4MulticastNetworkConfig): File {
        val startedAtMs = System.currentTimeMillis()
        val endMs = startedAtMs + config.durationSec * 1000L
        val fixture = Phase1MediaHarness.threeSourceFixtures.first { it.sourceIdentity == "S1" }
        val endpoint =
            MediaGroupEndpointBinding(
                multicastAddress = config.multicastAddress,
                mediaPort = config.mediaPort,
                underlayScopeId = "slice4-${config.runId}",
            )
        val handle =
            TransportHandle(
                id = "slice4-sender",
                endpoint = endpoint,
                networkBinding =
                    ConferenceMulticastNetworkBinding.fromInterfaceName(config.networkInterfaceName),
                multicastLockPolicy = MulticastLockPolicy.WIFI_MULTICAST_LOCK,
            )

        val resources = AndroidRuntimeResources.forAndroidProduct(context)
        val transport =
            ConferenceMulticastRtpSrtpTransport(
                resources = resources,
                wiring = null,
            )

        log(
            "SENDER_START runId=${config.runId} mcast=${config.multicastAddress}:${config.mediaPort} " +
                "iface=${config.networkInterfaceName}",
        )

        if (!transport.beginScope(handle, startedAtMs)) {
            return writeReport(
                config,
                startedAtMs,
                System.currentTimeMillis(),
                mapOf("error" to "TRANSPORT_SCOPE_FAILED", "bind" to bindFact(resources)),
            )
        }

        val rebind = resources.rebindSeam as MulticastSocketTransportRebindSeam
        val socket = rebind.currentSocket
        val joinOk = rebind.lastJoinSucceeded
        val joinErr = rebind.lastJoinError
        val bind = rebind.lastSocketBindResult
        if (socket == null || socket.isClosed) {
            transport.endScope()
            return writeReport(
                config,
                startedAtMs,
                System.currentTimeMillis(),
                mapOf(
                    "error" to "SOCKET_NULL_AFTER_SCOPE",
                    "bind" to bindFactSnapshot(bind, joinOk, joinErr),
                    "joinSucceeded" to joinOk,
                    "joinError" to joinErr,
                ),
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
        var nextSeq = 0x3001
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
                    mediaSlot = nextSeq,
                    opusPayload = opusPayload,
                )
            if (transport.sendProtectedArtifact(artifact, endpoint)) {
                packetsSent += 1
            } else {
                sendErrors += 1
                if (sendErrors == 1L) {
                    log("SENDER_FIRST_ERROR ${transport.observability.lastSendError}")
                }
            }
            nextSeq += 1
            nextSendNs += intervalNs
        }

        val snap = transport.observability.snapshot()
        val joinOkEnd = rebind.lastJoinSucceeded
        val joinErrEnd = rebind.lastJoinError
        transport.endScope()
        val endedAtMs = System.currentTimeMillis()
        log(
            "SENDER_END sent=$packetsSent errors=$sendErrors lastSendError=${snap.lastSendError} " +
                "join=$joinOkEnd",
        )
        return writeReport(
            config,
            startedAtMs,
            endedAtMs,
            mapOf(
                "packetsSent" to packetsSent,
                "sendErrors" to sendErrors,
                "lastSeqSent" to (nextSeq - 1),
                "opusPayloadBytes" to opusPayload.size,
                "bind" to bindFactSnapshot(bind, joinOkEnd, joinErrEnd),
                "joinSucceeded" to joinOkEnd,
                "joinError" to joinErrEnd,
                "lastSendError" to snap.lastSendError,
                "transportObservability" to obsJson(snap),
            ),
        )
    }

    private fun runReceiver(config: Slice4MulticastNetworkConfig): File {
        val startedAtMs = System.currentTimeMillis()
        val endMs = startedAtMs + config.durationSec * 1000L
        val fixture = Phase1MediaHarness.threeSourceFixtures.first { it.sourceIdentity == "S1" }

        val assembly = ConferenceMulticastRealMediaAssembly.create(context)
        Phase1MediaHarness.installAuthority(
            assembly.orchestrator.authority.store,
            listOf(fixture),
        )
        assembly.orchestrator.authority.syncAllAdmittedToRuntime()

        val endpoint =
            MediaGroupEndpointBinding(
                multicastAddress = config.multicastAddress,
                mediaPort = config.mediaPort,
                underlayScopeId = "slice4-${config.runId}",
            )
        val handle =
            TransportHandle(
                id = "slice4-receiver",
                endpoint = endpoint,
                networkBinding =
                    ConferenceMulticastNetworkBinding.fromInterfaceName(config.networkInterfaceName),
                multicastLockPolicy = MulticastLockPolicy.WIFI_MULTICAST_LOCK,
            )

        log(
            "RECEIVER_START runId=${config.runId} mcast=${config.multicastAddress}:${config.mediaPort} " +
                "iface=${config.networkInterfaceName}",
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
            val joinOk = transport.lastJoinSucceeded()
            val joinErr = transport.lastJoinError()
            if (bind == null || !bind.bindSucceeded) {
                transport.endScope()
                return writeReport(
                    config,
                    startedAtMs,
                    System.currentTimeMillis(),
                    mapOf(
                        "error" to "BIND_FAILED",
                        "bindSucceeded" to false,
                        "bindError" to bind?.error,
                        "joinSucceeded" to joinOk,
                        "joinError" to joinErr,
                    ),
                )
            }

            // Re-assert iface for RX path (underlay probe pattern).
            transport.configureNetworkInterface(config.networkInterfaceName)

            var datagramsReceived = 0L
            var ingressAccepted = 0L
            var ingressRejected = 0L
            var framesQueued = 0L
            var mixCycles = 0L
            var decodeSuccessTotal = 0L
            var playoutWrites = 0L
            var lastRejectReason: String? = null
            var consecutiveNullSocket = 0
            val timeline = RelativeMediaTimeline()

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
                        is ConferenceMulticastRtpSrtpTransport.ReceiveOutcome.Ingress ->
                            continue
                    }
                datagramsReceived += 1

                val admit =
                    assembly.pipeline.admitProtectedDatagram(
                        sourceIdentity = fixture.sourceIdentity,
                        datagram = payload,
                        rxWallMs = rxWallMs,
                        mediaTimeline = timeline,
                    )
                when (val ingress = admit.ingress) {
                    is WireIngressResult.Accepted -> {
                        ingressAccepted += 1
                        if (admit.frameAdmit == FrameAdmitDisposition.QUEUED) {
                            framesQueued += 1
                            val slot = admit.mediaSlot ?: continue
                            val mediaTimeMs =
                                admit.mediaTimeMs
                                    ?: (slot * MediaJitterConstants.MEDIA_SLOT_MS)
                            val beforeDecode = assembly.decoderSeam.decodeSuccessCount
                            val beforeWrites = assembly.playoutMetrics.successfulWrites
                            assembly.pipeline.runMixPlayoutCycle(
                                nowMs = rxWallMs + MediaJitterConstants.MEDIA_SLOT_MS,
                                slot = slot,
                                slotMediaTimeMs = mediaTimeMs,
                            )
                            mixCycles += 1
                            decodeSuccessTotal +=
                                (assembly.decoderSeam.decodeSuccessCount - beforeDecode)
                            playoutWrites +=
                                (assembly.playoutMetrics.successfulWrites - beforeWrites)
                        }
                    }
                    is WireIngressResult.Rejected -> {
                        ingressRejected += 1
                        lastRejectReason = "${ingress.frozenClass}:${ingress.reason}"
                    }
                }
            }

            val snap = assembly.pipeline.observability.snapshot()
            val joinOkEnd = transport.lastJoinSucceeded()
            transport.endScope()
            val endedAtMs = System.currentTimeMillis()
            val metrics =
                mapOf(
                    "datagramsReceived" to datagramsReceived,
                    "ingressAccepted" to ingressAccepted,
                    "ingressRejected" to ingressRejected,
                    "framesQueued" to framesQueued,
                    "mixCycles" to mixCycles,
                    "decodeSuccessCount" to assembly.decoderSeam.decodeSuccessCount,
                    "decodeSuccessDelta" to decodeSuccessTotal,
                    "successfulWrites" to assembly.playoutMetrics.successfulWrites,
                    "failedWrites" to assembly.playoutMetrics.failedWrites,
                    "underrunCount" to assembly.playoutMetrics.underrunCount,
                    "playoutWritesDelta" to playoutWrites,
                    "lastRejectReason" to lastRejectReason,
                    "lastReceiveError" to snap.lastReceiveError,
                    "bindSucceeded" to bind.bindSucceeded,
                    "bindInterface" to bind.networkInterfaceName,
                    "joinSucceeded" to joinOkEnd,
                    "joinError" to joinErr,
                    "transportObservability" to obsJson(snap),
                    "gateNetworkPacketsArrived" to (datagramsReceived > 0),
                    "gateSrtpIngressOk" to (ingressAccepted > 0),
                    "gateDecodeMixOk" to
                        (assembly.decoderSeam.decodeSuccessCount > 0 && mixCycles > 0),
                    "gateAudioTrackWritesOk" to (assembly.playoutMetrics.successfulWrites > 0),
                )
            log(
                "RECEIVER_END rx=$datagramsReceived ingressOk=$ingressAccepted " +
                    "decode=${assembly.decoderSeam.decodeSuccessCount} " +
                    "writes=${assembly.playoutMetrics.successfulWrites} " +
                    "lastRxErr=${snap.lastReceiveError}",
            )
            return writeReport(config, startedAtMs, endedAtMs, metrics)
        } finally {
            assembly.stopPlayout()
        }
    }

    private fun bindFact(resources: AndroidRuntimeResources): Map<String, Any?> {
        val rebind = resources.rebindSeam as? MulticastSocketTransportRebindSeam
        return bindFactSnapshot(
            rebind?.lastSocketBindResult,
            rebind?.lastJoinSucceeded,
            rebind?.lastJoinError,
        )
    }

    private fun bindFactSnapshot(
        bind: MulticastSocketBindResult?,
        joinSucceeded: Boolean?,
        joinError: String?,
    ): Map<String, Any?> =
        mapOf(
            "bindSucceeded" to (bind?.bindSucceeded ?: false),
            "networkInterfaceName" to bind?.networkInterfaceName,
            "boundNetworkId" to bind?.boundNetworkId,
            "error" to bind?.error,
            "joinSucceeded" to joinSucceeded,
            "joinError" to joinError,
        )

    private fun obsJson(snap: MulticastTransportObservabilitySnapshot): JSONObject =
        JSONObject()
            .put("packetsSent", snap.packetsSent)
            .put("packetsReceived", snap.packetsReceived)
            .put("ingressAccepted", snap.ingressAccepted)
            .put("ingressRejected", snap.ingressRejected)
            .put("sendErrors", snap.sendErrors)
            .put("receiveErrors", snap.receiveErrors)
            .put("lastSendError", snap.lastSendError)
            .put("lastReceiveError", snap.lastReceiveError)
            .put("decodeDurationUs", snap.decodeDurationUs)
            .put("mixDurationUs", snap.mixDurationUs)
            .put("playoutBudgetUsedUs", snap.playoutBudgetUsedUs)
            .put("audioTrackUnderrunCount", snap.audioTrackUnderrunCount)

    private fun writeReport(
        config: Slice4MulticastNetworkConfig,
        startedAtMs: Long,
        endedAtMs: Long,
        metrics: Map<String, Any?>,
    ): File {
        val root = context.getExternalFilesDir(null) ?: context.filesDir
        val dir = File(root, "slice4-mcast-srtp").apply { mkdirs() }
        val file = File(dir, "${config.runId}-${config.role.name.lowercase()}.json")
        val json =
            JSONObject()
                .put("probe", "PHASE1_SLICE4_MULTICAST_SRTP_NETWORK")
                .put("runId", config.runId)
                .put("role", config.role.name)
                .put("deviceLabel", config.deviceLabel)
                .put("startedAtMs", startedAtMs)
                .put("endedAtMs", endedAtMs)
                .put("durationSec", config.durationSec)
                .put("multicastAddress", config.multicastAddress)
                .put("mediaPort", config.mediaPort)
                .put("networkInterfaceName", config.networkInterfaceName)
                .put("nominalPps", config.nominalPps)
        val m = JSONObject()
        for ((k, v) in metrics) {
            m.put(k, v)
        }
        json.put("metrics", m)
        file.writeText(json.toString(2))
        return file
    }

    private fun acquireWakeLock(durationSec: Int): PowerManager.WakeLock? {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return null
        return try {
            pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SLICE4_MC_SRTP").apply {
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
        Log.i(Slice4MulticastNetworkConstants.LOG_TAG, message)
    }
}
