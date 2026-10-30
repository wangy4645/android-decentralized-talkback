package com.talkback.core.conference.probe

import android.content.Context
import android.os.PowerManager
import android.util.Log
import com.talkback.core.conference.runtime.AndroidRuntimeResources
import com.talkback.core.conference.runtime.MediaGroupEndpointBinding
import com.talkback.core.conference.runtime.MulticastSocketTransportRebindSeam
import com.talkback.core.conference.runtime.TransportHandle
import java.io.File
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

enum class UnderlayMulticastProbeRole {
    SENDER,
    RECEIVER,
}

data class UnderlayMulticastProbeConfig(
    val runId: String,
    val role: UnderlayMulticastProbeRole,
    val durationSec: Int,
    val multicastAddress: String = UnderlayMulticastProbeConstants.DEFAULT_MULTICAST_ADDRESS,
    val mediaPort: Int = UnderlayMulticastProbeConstants.DEFAULT_MEDIA_PORT,
    val nominalPps: Int = UnderlayMulticastProbeConstants.NOMINAL_PPS,
    val networkInterfaceName: String? = null,
    val deviceLabel: String = "unknown",
)

class UnderlayMulticastProbeRunner(
    private val context: Context,
) {
    fun run(config: UnderlayMulticastProbeConfig): File {
        val runIdHash = UnderlayMulticastProbePacket.runIdHash(config.runId)
        val endpoint =
            MediaGroupEndpointBinding(
                multicastAddress = config.multicastAddress,
                mediaPort = config.mediaPort,
                underlayScopeId = "underlay-probe-${config.runId}",
            )
        val handle =
            TransportHandle(
                id = "probe-${config.role.name.lowercase()}",
                endpoint = endpoint,
                networkInterfaceName = config.networkInterfaceName,
            )

        val resources = AndroidRuntimeResources.forAndroidProduct(context)
        val rebindSeam = resources.rebindSeam as MulticastSocketTransportRebindSeam
        val startedAtMs = System.currentTimeMillis()
        val endMs = startedAtMs + config.durationSec * 1000L
        val wakeLock = acquireProbeWakeLock(config.durationSec)

        try {
            return runProbeBody(
                config,
                runIdHash,
                handle,
                resources,
                rebindSeam,
                startedAtMs,
                endMs,
            )
        } finally {
            releaseProbeWakeLock(wakeLock)
        }
    }

    private fun runProbeBody(
        config: UnderlayMulticastProbeConfig,
        runIdHash: Int,
        handle: TransportHandle,
        resources: AndroidRuntimeResources,
        rebindSeam: MulticastSocketTransportRebindSeam,
        startedAtMs: Long,
        endMs: Long,
    ): File {
        log(
            "PROBE_START role=${config.role} runId=${config.runId} durationSec=${config.durationSec} " +
                "mcast=${config.multicastAddress}:${config.mediaPort} pps=${config.nominalPps} " +
                "payload=${UnderlayMulticastProbeConstants.PAYLOAD_BYTES}",
        )

        if (!resources.beginTransportScope(handle, startedAtMs)) {
            return writeReport(
                config,
                startedAtMs,
                System.currentTimeMillis(),
                joinSucceeded = false,
                multicastLockReleasedOnEnd = false,
                extra = mapOf("error" to "MULTICAST_LOCK_ACQUIRE_FAILED"),
            )
        }

        val joinSucceeded = rebindSeam.lastJoinSucceeded
        val joinAttempted = rebindSeam.lastJoinAttempted
        val joinError = rebindSeam.lastJoinError
        val socket = rebindSeam.currentSocket
        if (socket == null || socket.isClosed) {
            resources.endTransportScope()
            return writeReport(
                config,
                startedAtMs,
                System.currentTimeMillis(),
                joinSucceeded = joinSucceeded,
                multicastLockReleasedOnEnd = !resources.multicastLock.isHeld(),
                extra = mapOf("error" to "SOCKET_OPEN_FAILED"),
            )
        }

        socket.soTimeout = 250
        socket.timeToLive = 32
        config.networkInterfaceName?.let { name ->
            val iface = java.net.NetworkInterface.getByName(name)
            if (iface != null) {
                socket.networkInterface = iface
            }
        }

        val transportFactsAtStart =
            UnderlayMulticastProbeTransportFacts.capture(
                config,
                socket,
                rebindSeam.lastJoinNetworkInterfaceName,
            )
        log("PROBE_TRANSPORT ${UnderlayMulticastProbeTransportFacts.formatLogLine(transportFactsAtStart)}")

        val running = AtomicBoolean(true)
        var packetsSent = 0L
        var lastSeqSent = -1L
        val metrics = UnderlayMulticastProbeMetrics(runIdHash)
        var receiverError: String? = null
        var receiveTimeouts = 0
        var senderError: String? = null

        val worker =
            Thread(
                {
                    when (config.role) {
                        UnderlayMulticastProbeRole.SENDER -> {
                            val intervalNs = 1_000_000_000L / config.nominalPps
                            var nextSendNs = System.nanoTime()
                            var seq = 0L
                            while (running.get() && System.currentTimeMillis() < endMs) {
                                val nowNs = System.nanoTime()
                                if (nowNs >= nextSendNs) {
                                    val sendWallMs = System.currentTimeMillis()
                                    val payload =
                                        UnderlayMulticastProbePacket(runIdHash, seq, sendWallMs).encode()
                                    val group = InetAddress.getByName(config.multicastAddress)
                                    val packet =
                                        DatagramPacket(
                                            payload,
                                            payload.size,
                                            group,
                                            config.mediaPort,
                                        )
                                    try {
                                        socket.send(packet)
                                    } catch (e: java.io.IOException) {
                                        if (senderError == null) {
                                            senderError = "${e.javaClass.simpleName}: ${e.message}"
                                            log("PROBE_SEND_ERROR $senderError")
                                        }
                                        break
                                    }
                                    packetsSent += 1
                                    lastSeqSent = seq
                                    seq += 1
                                    nextSendNs += intervalNs
                                    if (seq % config.nominalPps == 0L) {
                                        log("PROBE_SEND_PROGRESS seq=$seq sent=$packetsSent")
                                    }
                                } else {
                                    val sleepMs = ((nextSendNs - nowNs) / 1_000_000L).coerceAtMost(5L)
                                    if (sleepMs > 0) Thread.sleep(sleepMs)
                                }
                            }
                        }
                        UnderlayMulticastProbeRole.RECEIVER -> {
                            val buf = ByteArray(UnderlayMulticastProbeConstants.PAYLOAD_BYTES)
                            while (running.get() && System.currentTimeMillis() < endMs) {
                                val packet = DatagramPacket(buf, buf.size)
                                try {
                                    socket.receive(packet)
                                } catch (_: SocketTimeoutException) {
                                    receiveTimeouts += 1
                                    continue
                                } catch (e: Exception) {
                                    receiverError = e.javaClass.simpleName + ": " + e.message
                                    break
                                }
                                val decoded = UnderlayMulticastProbePacket.decode(packet.data) ?: continue
                                metrics.onPacket(decoded, System.currentTimeMillis())
                            }
                        }
                    }
                },
                "underlay-mcast-probe-${config.role.name.lowercase()}",
            )
        worker.isDaemon = true
        worker.start()

        val shutdownExecutor = Executors.newSingleThreadScheduledExecutor()
        val shutdownFuture =
            shutdownExecutor.schedule(
                {
                    running.set(false)
                    log("PROBE_SHUTDOWN_DEADLINE role=${config.role}")
                    try {
                        if (!socket.isClosed) {
                            socket.close()
                        }
                    } catch (e: Exception) {
                        log("PROBE_SHUTDOWN_SOCKET_CLOSE ${e.javaClass.simpleName}: ${e.message}")
                    }
                },
                config.durationSec.toLong(),
                TimeUnit.SECONDS,
            )

        log("PROBE_DURATION_BEGIN durationSec=${config.durationSec}")
        worker.join((config.durationSec * 1000L) + 10_000L)
        shutdownFuture.cancel(false)
        shutdownExecutor.shutdownNow()
        running.set(false)
        log("PROBE_DURATION_END role=${config.role}")

        worker.join(2_000L)
        if (worker.isAlive) {
            log("PROBE_WORKER_JOIN_TIMEOUT role=${config.role}")
            try {
                if (!socket.isClosed) {
                    socket.close()
                }
            } catch (e: Exception) {
                log("PROBE_SOCKET_CLOSE_ON_TIMEOUT ${e.javaClass.simpleName}: ${e.message}")
            }
            worker.join(2_000L)
        }
        log("PROBE_WORKER_JOIN_DONE role=${config.role} alive=${worker.isAlive}")

        val socketLocalPort = socket.localPort
        val transportFactsAtEnd =
            UnderlayMulticastProbeTransportFacts.capture(
                config,
                socket,
                rebindSeam.lastJoinNetworkInterfaceName,
            )
        log("PROBE_TRANSPORT_END ${UnderlayMulticastProbeTransportFacts.formatLogLine(transportFactsAtEnd)}")
        val endedAtMs = System.currentTimeMillis()
        resources.endTransportScope()

        val extra = mutableMapOf<String, Any?>(
            "joinAttempted" to joinAttempted,
            "joinSucceeded" to joinSucceeded,
            "joinError" to joinError,
            "multicastLockReleasedOnEnd" to !resources.multicastLock.isHeld(),
            "socketLocalPort" to socketLocalPort,
        )
        when (config.role) {
            UnderlayMulticastProbeRole.SENDER -> {
                extra["packetsSent"] = packetsSent
                extra["lastSeqSent"] = lastSeqSent
                extra["expectedPackets"] = config.durationSec * config.nominalPps
                extra["senderError"] = senderError
            }
            UnderlayMulticastProbeRole.RECEIVER -> {
                extra.putAll(metrics.snapshot())
                extra["receiverError"] = receiverError
                extra["receiveTimeouts"] = receiveTimeouts
            }
        }
        extra.putAll(transportFactsAtEnd)

        val report =
            writeReport(
                config,
                startedAtMs,
                endedAtMs,
                joinSucceeded = joinSucceeded,
                multicastLockReleasedOnEnd = !resources.multicastLock.isHeld(),
                extra = extra,
            )
        log("PROBE_END role=${config.role} report=${report.absolutePath}")
        return report
    }

    private fun acquireProbeWakeLock(durationSec: Int): PowerManager.WakeLock? {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return null
        return try {
            pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "UNDERLAY_MC_PROBE").apply {
                setReferenceCounted(false)
                acquire((durationSec + 15) * 1000L)
            }
        } catch (e: Exception) {
            log("PROBE_WAKELOCK_ACQUIRE_FAILED ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    private fun releaseProbeWakeLock(wakeLock: PowerManager.WakeLock?) {
        if (wakeLock == null) return
        try {
            if (wakeLock.isHeld) wakeLock.release()
        } catch (_: Exception) {
            // best-effort
        }
    }

    private fun writeReport(
        config: UnderlayMulticastProbeConfig,
        startedAtMs: Long,
        endedAtMs: Long,
        joinSucceeded: Boolean,
        multicastLockReleasedOnEnd: Boolean,
        extra: Map<String, Any?>,
    ): File {
        val root = context.getExternalFilesDir(null) ?: context.filesDir
        val dir = File(root, "underlay-mcast-probe").apply { mkdirs() }
        val file = File(dir, "${config.runId}-${config.role.name.lowercase()}.json")

        val json =
            JSONObject()
                .put("probe", "ADR0058_UNDERLAY_MULTICAST_DUAL_NODE")
                .put("runId", config.runId)
                .put("role", config.role.name)
                .put("deviceLabel", config.deviceLabel)
                .put("startedAtMs", startedAtMs)
                .put("endedAtMs", endedAtMs)
                .put("durationSec", config.durationSec)
                .put("multicastAddress", config.multicastAddress)
                .put("mediaPort", config.mediaPort)
                .put("nominalPps", config.nominalPps)
                .put("payloadBytes", UnderlayMulticastProbeConstants.PAYLOAD_BYTES)
                .put("joinSucceeded", joinSucceeded)
                .put("multicastLockReleasedOnEnd", multicastLockReleasedOnEnd)
                .put(
                    "frozenReference",
                    JSONObject()
                        .put("maxPlayoutDelayMs", UnderlayMulticastProbeConstants.FROZEN_MAX_PLAYOUT_DELAY_MS)
                        .put(
                            "maxConsecutivePlcFrames",
                            UnderlayMulticastProbeConstants.FROZEN_MAX_CONSECUTIVE_PLC_FRAMES,
                        )
                        .put("nominalPps", UnderlayMulticastProbeConstants.NOMINAL_PPS)
                        .put("safeUdpPayloadBytes", UnderlayMulticastProbeConstants.PAYLOAD_BYTES),
                )
        val metrics = JSONObject()
        for ((k, v) in extra) {
            metrics.put(k, v)
        }
        json.put("metrics", metrics)
        file.writeText(json.toString(2))
        return file
    }

    private fun log(message: String) {
        Log.i(UnderlayMulticastProbeConstants.LOG_TAG, message)
    }
}
