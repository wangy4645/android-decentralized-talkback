package com.talkback.core.conference.capacity

import com.talkback.core.conference.wire.ConferenceWireConstants
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress

/**
 * Production seam: one Source artifact → N unicast UDP legs via a single unconnected socket.
 * H1d.3: fixed packet length, indexed send loop, preallocated outcome scratch.
 * H1d.4: wall vs thread-CPU timing when [recordLegTiming] (diagnostic mode only).
 * H1d.5: SO_SNDBUF configuration, long-lived socket, pre-measurement egress warm-up.
 */
class FanOutDatagramSender private constructor(
    private val socket: DatagramSocket,
    private val endpoints: List<PerReceiverUnicastEndpoint>,
    private val legPackets: Array<DatagramPacket>,
    private val sendBuffer: ByteArray,
    private val legDurationScratchNs: LongArray,
    private val outcomeScratch: FanOutSendOutcomeScratch,
    private val fixedPayloadLength: Int,
    override var socketProfile: Gres3UdpEgressSocketProfile,
) : FanOutSender {
    override val executionModel: Gres3SenderExecutionModel =
        Gres3SenderExecutionModel.SHARED_SOCKET_SEQUENTIAL
    override val socketCount: Int = 1
    override val legCount: Int = legPackets.size

    override val localPort: Int
        get() = socket.localPort

    override val localPorts: List<Int>
        get() = listOf(localPort)

    /** Valid until next [sendFanOut] call. */
    override val lastLegDurationsNs: LongArray
        get() = legDurationScratchNs

    /**
     * H1d.5a: one deterministic 9-leg warm-up (no retry); per-leg failure observability.
     * Not counted in slot metrics / evidence counters.
     */
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
                socket.send(legPackets[i])
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

    /**
     * @param artifact ring buffer entry (exactly [fixedPayloadLength] bytes)
     * @param recordLegTiming H1d only — H1c/qualifying must pass false
     */
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
                    socket.send(legPackets[i])
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
                    socket.send(legPackets[i])
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
        if (!socket.isClosed) {
            socket.close()
        }
    }

    companion object {
        fun open(
            endpoints: List<PerReceiverUnicastEndpoint>,
            bindPort: Int = 0,
        ): FanOutDatagramSender {
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
            val socket = DatagramSocket(bindPort)
            val socketProfile = Gres3UdpEgressSocketConfigurator.configure(socket)
            val sendBuffer = ByteArray(payloadLength)
            val legDurationScratchNs = LongArray(legCount)
            val legPackets = arrayOfNulls<DatagramPacket>(legCount)
            var pi = 0
            while (pi < legCount) {
                val endpoint = endpoints[pi]
                val address = InetSocketAddress(endpoint.moduleFixedIp, endpoint.mediaPort)
                legPackets[pi] = DatagramPacket(sendBuffer, payloadLength, address)
                pi++
            }
            @Suppress("UNCHECKED_CAST")
            return FanOutDatagramSender(
                socket = socket,
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
