package com.talkback.core.conference.capacity

import com.talkback.core.conference.wire.ConferenceWireConstants
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress

/**
 * E1 probe: one long-lived [DatagramSocket] per target; single pacing thread sequential send.
 * Semantics unchanged — protect-once artifact, byte-identical replication, per-leg failure isolation.
 */
class PerTargetFanOutDatagramSender private constructor(
    private val sockets: Array<DatagramSocket>,
    private val endpoints: List<PerReceiverUnicastEndpoint>,
    private val legPackets: Array<DatagramPacket>,
    private val sendBuffer: ByteArray,
    private val legDurationScratchNs: LongArray,
    private val outcomeScratch: FanOutSendOutcomeScratch,
    private val fixedPayloadLength: Int,
    override var socketProfile: Gres3UdpEgressSocketProfile,
) : FanOutSender {
    override val executionModel: Gres3SenderExecutionModel =
        Gres3SenderExecutionModel.PER_TARGET_SOCKET_SEQUENTIAL
    override val socketCount: Int = sockets.size
    override val legCount: Int = legPackets.size

    override val localPort: Int
        get() = sockets.first().localPort

    override val localPorts: List<Int>
        get() = sockets.map { it.localPort }

    override val lastLegDurationsNs: LongArray
        get() = legDurationScratchNs

    override fun warmEgress(artifact: ByteArray): Gres3EgressWarmupResult {
        val result =
            try {
                System.arraycopy(artifact, 0, sendBuffer, 0, fixedPayloadLength)
                warmEgressSendAllLegs()
            } catch (e: Exception) {
                Gres3EgressWarmupResult(
                    attempted = true,
                    legsSucceeded = 0,
                    legsFailed = legCount,
                    legFailures = emptyList(),
                    setupError = "${e.javaClass.name}: ${e.message}",
                )
            }
        socketProfile = socketProfile.withWarmup(result)
        return result
    }

    private fun warmEgressSendAllLegs(): Gres3EgressWarmupResult {
        val failures = ArrayList<Gres3EgressWarmupLegFailure>(legCount)
        var succeeded = 0
        var failed = 0
        var i = 0
        while (i < legCount) {
            try {
                sockets[i].send(legPackets[i])
                succeeded++
            } catch (e: Exception) {
                failed++
                val endpoint = endpoints[i]
                failures +=
                    Gres3EgressWarmupLegFailure(
                        legIndex = i,
                        receiverModuleId = endpoint.receiverModuleId,
                        moduleFixedIp = endpoint.moduleFixedIp,
                        mediaPort = endpoint.mediaPort,
                        endpointKey = endpoint.endpointKey(),
                        exceptionClass = e.javaClass.name,
                        message = e.message,
                        causeClass = e.cause?.javaClass?.name,
                        causeMessage = e.cause?.message,
                    )
            }
            i++
        }
        return Gres3EgressWarmupResult(
            attempted = true,
            legsSucceeded = succeeded,
            legsFailed = failed,
            legFailures = failures,
        )
    }

    override fun sendFanOut(
        artifact: ByteArray,
        recordLegTiming: Boolean,
    ): FanOutSendOutcomeScratch {
        System.arraycopy(artifact, 0, sendBuffer, 0, fixedPayloadLength)

        var succeeded = 0
        var failed = 0
        var failedMask = 0L
        var maxLegNs = 0L
        var maxLegIndex = -1

        val wallStartNs =
            if (recordLegTiming) {
                System.nanoTime()
            } else {
                0L
            }
        val cpuStartNs =
            if (recordLegTiming) {
                FanOutThreadCpuTiming.threadCpuTimeNs()
            } else {
                0L
            }

        if (recordLegTiming) {
            var i = 0
            while (i < legCount) {
                val legStartNs = System.nanoTime()
                try {
                    sockets[i].send(legPackets[i])
                    succeeded++
                } catch (_: Exception) {
                    failed++
                    if (i < 63) {
                        failedMask = failedMask or (1L shl i)
                    }
                }
                val legDurationNs = System.nanoTime() - legStartNs
                legDurationScratchNs[i] = legDurationNs
                if (legDurationNs > maxLegNs) {
                    maxLegNs = legDurationNs
                    maxLegIndex = i
                }
                i++
            }
        } else {
            var i = 0
            while (i < legCount) {
                try {
                    sockets[i].send(legPackets[i])
                    succeeded++
                } catch (_: Exception) {
                    failed++
                    if (i < 63) {
                        failedMask = failedMask or (1L shl i)
                    }
                }
                i++
            }
        }

        val scratch = outcomeScratch
        scratch.legsAttempted = legCount
        scratch.legsSucceeded = succeeded
        scratch.legsFailed = failed
        scratch.legFailedMask = failedMask
        scratch.maxLegIndex = maxLegIndex
        scratch.maxLegDurationNs = maxLegNs
        if (recordLegTiming) {
            scratch.fanoutWallNs = System.nanoTime() - wallStartNs
            scratch.fanoutThreadCpuNs = FanOutThreadCpuTiming.threadCpuTimeNs() - cpuStartNs
        } else {
            scratch.fanoutWallNs = 0L
            scratch.fanoutThreadCpuNs = 0L
        }
        return scratch
    }

    override fun close() {
        var i = 0
        while (i < sockets.size) {
            val socket = sockets[i]
            if (!socket.isClosed) {
                socket.close()
            }
            i++
        }
    }

    companion object {
        fun open(
            endpoints: List<PerReceiverUnicastEndpoint>,
            bindPort: Int = 0,
        ): PerTargetFanOutDatagramSender {
            val legCount = endpoints.size
            require(legCount == Gres3CapacityHarnessConstants.REQUIRED_LEG_COUNT) {
                "G-RES-3 requires exactly ${Gres3CapacityHarnessConstants.REQUIRED_LEG_COUNT} legs, got $legCount"
            }
            val seenKeys = HashSet<String>(legCount)
            var ei = 0
            while (ei < legCount) {
                val key = endpoints[ei].endpointKey()
                require(seenKeys.add(key)) {
                    "endpoints must be distinct receiver identities: duplicate=$key"
                }
                ei++
            }

            val payloadLength = ConferenceWireConstants.SAFE_UDP_PAYLOAD_BYTES
            val sockets = Array(legCount) { DatagramSocket(bindPort) }
            val socketProfile = Gres3UdpEgressSocketConfigurator.configure(sockets[0])
            val sendBuffer = ByteArray(payloadLength)
            val legDurationScratchNs = LongArray(legCount)
            val legPackets = arrayOfNulls<DatagramPacket>(legCount)
            var pi = 0
            while (pi < legCount) {
                if (pi > 0) {
                    Gres3UdpEgressSocketConfigurator.configure(sockets[pi])
                }
                val endpoint = endpoints[pi]
                val address = InetSocketAddress(endpoint.moduleFixedIp, endpoint.mediaPort)
                legPackets[pi] = DatagramPacket(sendBuffer, payloadLength, address)
                pi++
            }
            @Suppress("UNCHECKED_CAST")
            return PerTargetFanOutDatagramSender(
                sockets = sockets,
                endpoints = endpoints,
                legPackets = legPackets as Array<DatagramPacket>,
                sendBuffer = sendBuffer,
                legDurationScratchNs = legDurationScratchNs,
                outcomeScratch = FanOutSendOutcomeScratch(),
                fixedPayloadLength = payloadLength,
                socketProfile = socketProfile,
            )
        }
    }
}
