package com.talkback.core.conference.capacity

/**
 * Harness seam for G-RES-3 E0/E1 sender execution-model probes.
 */
interface FanOutSender {
    val executionModel: Gres3SenderExecutionModel
    val socketCount: Int
    val legCount: Int
    val localPort: Int
    val localPorts: List<Int>
    var socketProfile: Gres3UdpEgressSocketProfile
    val lastLegDurationsNs: LongArray

    fun warmEgress(artifact: ByteArray): Gres3EgressWarmupResult

    fun sendFanOut(
        artifact: ByteArray,
        recordLegTiming: Boolean,
    ): FanOutSendOutcomeScratch

    fun close()
}

object FanOutSenderFactory {
    fun open(config: Gres3CapacityHarnessConfig): FanOutSender =
        when (config.executionModel) {
            Gres3SenderExecutionModel.SHARED_SOCKET_SEQUENTIAL ->
                FanOutDatagramSender.open(
                    endpoints = config.endpoints,
                    bindPort = config.senderBindPort,
                )
            Gres3SenderExecutionModel.PER_TARGET_SOCKET_SEQUENTIAL ->
                PerTargetFanOutDatagramSender.open(
                    endpoints = config.endpoints,
                    bindPort = config.senderBindPort,
                )
            Gres3SenderExecutionModel.SENDMMSG_BATCH ->
                SendmmsgBatchFanOutSender.open(
                    endpoints = config.endpoints,
                    bindPort = config.senderBindPort,
                    nonBlocking = false,
                )
            Gres3SenderExecutionModel.SENDMMSG_BATCH_NONBLOCKING ->
                SendmmsgBatchFanOutSender.open(
                    endpoints = config.endpoints,
                    bindPort = config.senderBindPort,
                    nonBlocking = true,
                )
        }
}
