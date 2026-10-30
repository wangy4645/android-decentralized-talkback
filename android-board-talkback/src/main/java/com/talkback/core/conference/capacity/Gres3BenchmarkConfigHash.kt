package com.talkback.core.conference.capacity

import java.security.MessageDigest

/**
 * Canonical hash of frozen benchmark parameters for qualifying-set mechanical checks.
 */
object Gres3BenchmarkConfigHash {
    fun compute(config: Gres3CapacityHarnessConfig): String {
        val canonical =
            buildString {
                append("artifactRingSize=").append(config.artifactRingSize).append('\n')
                append("cooldownSec=").append(config.cooldownSec).append('\n')
                append("executionModel=").append(config.executionModel.name).append('\n')
                append("legCount=").append(config.endpoints.size).append('\n')
                append("logicalMediaRateHz=").append(AbsoluteSlotScheduler.LOGICAL_MEDIA_RATE_HZ).append('\n')
                append("measurementSec=").append(config.measurementSec).append('\n')
                append("runClass=").append(config.runClass.name).append('\n')
                append("slotPeriodNs=").append(AbsoluteSlotScheduler.SLOT_PERIOD_NS).append('\n')
                append("socketCount=").append(config.executionModel.socketCount).append('\n')
                append("threadPriority=").append(config.pacingThreadPriority).append('\n')
                append("udpSendBufferBytes=").append(Gres3UdpEgressSocketConfigurator.REQUESTED_SEND_BUFFER_BYTES).append('\n')
                append("topology=")
                config.formalTopology?.topologyId?.let { append(it).append(';') }
                config.endpoints
                    .sortedBy { it.endpointKey() }
                    .forEach { endpoint ->
                        append(endpoint.endpointKey()).append(';')
                    }
                append('\n')
                append("warmupSec=").append(config.warmupSec).append('\n')
            }
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }
}
