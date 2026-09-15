package com.talkback.core.conference.capacity

import com.talkback.core.conference.wire.ConferenceWireConstants

/**
 * E3/E3b probe: one native sendmmsg syscall per slot (9 datagrams).
 * E3b uses MSG_DONTWAIT — partial/EAGAIN per Decision 1/2, no retry.
 */
class SendmmsgBatchFanOutSender private constructor(
    private val socketFd: Int,
    private val endpoints: List<PerReceiverUnicastEndpoint>,
    private val sendBuffer: ByteArray,
    private val legDurationScratchNs: LongArray,
    private val outcomeScratch: FanOutSendOutcomeScratch,
    private val fixedPayloadLength: Int,
    override var socketProfile: Gres3UdpEgressSocketProfile,
    private val localPortValue: Int,
    private val nonBlocking: Boolean,
) : FanOutSender {
    override val executionModel: Gres3SenderExecutionModel =
        if (nonBlocking) {
            Gres3SenderExecutionModel.SENDMMSG_BATCH_NONBLOCKING
        } else {
            Gres3SenderExecutionModel.SENDMMSG_BATCH
        }
    override val socketCount: Int = 1
    override val legCount: Int = endpoints.size

    override val localPort: Int
        get() = localPortValue

    override val localPorts: List<Int>
        get() = listOf(localPortValue)

    override val lastLegDurationsNs: LongArray
        get() = legDurationScratchNs

    override fun warmEgress(artifact: ByteArray): Gres3EgressWarmupResult {
        val result =
            try {
                System.arraycopy(artifact, 0, sendBuffer, 0, fixedPayloadLength)
                warmEgressSendBatch()
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

    override fun sendFanOut(
        artifact: ByteArray,
        recordLegTiming: Boolean,
    ): FanOutSendOutcomeScratch {
        System.arraycopy(artifact, 0, sendBuffer, 0, fixedPayloadLength)
        val batch =
            if (recordLegTiming) {
                val cpuStartNs = FanOutThreadCpuTiming.threadCpuTimeNs()
                val result = invokeSendmmsgBatch()
                applyBatchOutcome(result, recordLegTiming = true, cpuStartNs = cpuStartNs)
            } else {
                val result = invokeSendmmsgBatch()
                applyBatchOutcome(result, recordLegTiming = false, cpuStartNs = 0L)
            }
        return batch
    }

    private fun invokeSendmmsgBatch(): Gres3SendmmsgNative.BatchResult =
        if (nonBlocking) {
            Gres3SendmmsgNative.sendmmsgBatchNonblocking(
                socketFd,
                sendBuffer,
                fixedPayloadLength,
            )
        } else {
            Gres3SendmmsgNative.sendmmsgBatch(
                socketFd,
                sendBuffer,
                fixedPayloadLength,
            )
        }

    override fun close() {
        Gres3SendmmsgNative.close(socketFd)
    }

    private fun warmEgressSendBatch(): Gres3EgressWarmupResult {
        val batch = invokeSendmmsgBatch()
        return batchToWarmupResult(batch)
    }

    private fun applyBatchOutcome(
        batch: Gres3SendmmsgNative.BatchResult,
        recordLegTiming: Boolean,
        cpuStartNs: Long,
    ): FanOutSendOutcomeScratch {
        val scratch = outcomeScratch
        scratch.legsAttempted = legCount
        scratch.legsSucceeded = if (batch.errno == 0) batch.returnedMessages else 0
        scratch.legsFailed = legCount - scratch.legsSucceeded
        scratch.legFailedMask = failedMaskFromReturned(batch.returnedMessages, batch.errno != 0)
        scratch.maxLegIndex = -1
        scratch.maxLegDurationNs = 0L
        scratch.sendmmsgRequestedMessages = batch.requestedMessages
        scratch.sendmmsgReturnedMessages = batch.returnedMessages
        scratch.sendmmsgSyscallWallNs = batch.syscallWallNs
        scratch.sendmmsgErrno = batch.errno
        scratch.sendmmsgEagain = batch.isEagain
        if (recordLegTiming) {
            scratch.fanoutWallNs = batch.syscallWallNs
            scratch.fanoutThreadCpuNs = FanOutThreadCpuTiming.threadCpuTimeNs() - cpuStartNs
        } else {
            scratch.fanoutWallNs = batch.syscallWallNs
            scratch.fanoutThreadCpuNs = 0L
        }
        return scratch
    }

    private fun batchToWarmupResult(batch: Gres3SendmmsgNative.BatchResult): Gres3EgressWarmupResult {
        if (batch.errno != 0) {
            return Gres3EgressWarmupResult(
                attempted = true,
                legsSucceeded = 0,
                legsFailed = legCount,
                legFailures =
                    listOf(
                        Gres3EgressWarmupLegFailure(
                            legIndex = 0,
                            receiverModuleId = endpoints[0].receiverModuleId,
                            moduleFixedIp = endpoints[0].moduleFixedIp,
                            mediaPort = endpoints[0].mediaPort,
                            endpointKey = endpoints[0].endpointKey(),
                            exceptionClass = "sendmmsg",
                            message = "errno=${batch.errno}",
                            causeClass = null,
                            causeMessage = null,
                        ),
                    ),
            )
        }
        if (batch.returnedMessages == legCount) {
            return Gres3EgressWarmupResult(
                attempted = true,
                legsSucceeded = legCount,
                legsFailed = 0,
                legFailures = emptyList(),
            )
        }
        val failures = ArrayList<Gres3EgressWarmupLegFailure>()
        var i = batch.returnedMessages
        while (i < legCount) {
            val endpoint = endpoints[i]
            failures +=
                Gres3EgressWarmupLegFailure(
                    legIndex = i,
                    receiverModuleId = endpoint.receiverModuleId,
                    moduleFixedIp = endpoint.moduleFixedIp,
                    mediaPort = endpoint.mediaPort,
                    endpointKey = endpoint.endpointKey(),
                    exceptionClass = "sendmmsg",
                    message = "partial batch returned=${batch.returnedMessages}",
                    causeClass = null,
                    causeMessage = null,
                )
            i++
        }
        return Gres3EgressWarmupResult(
            attempted = true,
            legsSucceeded = batch.returnedMessages,
            legsFailed = legCount - batch.returnedMessages,
            legFailures = failures,
        )
    }

    private fun failedMaskFromReturned(returnedMessages: Int, syscallFailed: Boolean): Long {
        if (syscallFailed) {
            return if (legCount >= 64) {
                -1L
            } else {
                (1L shl legCount) - 1L
            }
        }
        var mask = 0L
        var i = returnedMessages
        while (i < legCount) {
            if (i < 63) {
                mask = mask or (1L shl i)
            }
            i++
        }
        return mask
    }

    companion object {
        fun open(
            endpoints: List<PerReceiverUnicastEndpoint>,
            bindPort: Int = 0,
            nonBlocking: Boolean = false,
        ): SendmmsgBatchFanOutSender {
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

            val destIps = endpoints.map { it.moduleFixedIp }.toTypedArray()
            val destPorts = IntArray(legCount) { endpoints[it].mediaPort }
            val openResult = Gres3SendmmsgNative.open(bindPort, destIps, destPorts)
            val payloadLength = ConferenceWireConstants.SAFE_UDP_PAYLOAD_BYTES
            val socketMode =
                if (nonBlocking) {
                    "sendmmsg_batch_nonblocking"
                } else {
                    "sendmmsg_batch"
                }
            val socketProfile =
                Gres3UdpEgressSocketProfile(
                    socketMode = socketMode,
                    connected = false,
                    reuseAddress = false,
                    broadcast = false,
                    sendBufferSizeDefault = openResult.sendBufferSizeDefault,
                    sendBufferSizeRequested = Gres3UdpEgressSocketConfigurator.REQUESTED_SEND_BUFFER_BYTES,
                    sendBufferSizeEffective = openResult.sendBufferSizeEffective,
                    receiveBufferSizeEffective = 0,
                    trafficClass = 0,
                )
            return SendmmsgBatchFanOutSender(
                socketFd = openResult.socketFd,
                endpoints = endpoints,
                sendBuffer = ByteArray(payloadLength),
                legDurationScratchNs = LongArray(legCount),
                outcomeScratch = FanOutSendOutcomeScratch(),
                fixedPayloadLength = payloadLength,
                socketProfile = socketProfile,
                localPortValue = openResult.localPort,
                nonBlocking = nonBlocking,
            )
        }
    }
}
