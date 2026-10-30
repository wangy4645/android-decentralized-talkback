package com.talkback.core.conference.capacity

/**
 * Builds frozen 9-leg topology for harness runs. Prefer distinct IPs when available.
 */
object Gres3CapacityTopology {
    fun distinctLocalPorts(
        baseIp: String = "127.0.0.1",
        basePort: Int = 47001,
        legCount: Int = Gres3CapacityHarnessConstants.REQUIRED_LEG_COUNT,
        moduleIdPrefix: String = "R",
    ): List<PerReceiverUnicastEndpoint> {
        require(legCount == Gres3CapacityHarnessConstants.REQUIRED_LEG_COUNT) {
            "legCount must be ${Gres3CapacityHarnessConstants.REQUIRED_LEG_COUNT}"
        }
        return (0 until legCount).map { index ->
            PerReceiverUnicastEndpoint(
                receiverModuleId = "$moduleIdPrefix${(index + 1).toString().padStart(2, '0')}",
                moduleFixedIp = baseIp,
                mediaPort = basePort + index,
            )
        }
    }

    fun fromInstrumentationCsv(csv: String): List<PerReceiverUnicastEndpoint> {
        val legs =
            csv.split(';')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .map { token ->
                    val parts = token.split('@', limit = 2)
                    require(parts.size == 2) { "invalid endpoint token: $token" }
                    val moduleId = parts[0]
                    val hostPort = parts[1].split(':', limit = 2)
                    require(hostPort.size == 2) { "invalid host:port in $token" }
                    PerReceiverUnicastEndpoint(
                        receiverModuleId = moduleId,
                        moduleFixedIp = hostPort[0],
                        mediaPort = hostPort[1].toInt(),
                    )
                }
        require(legs.size == Gres3CapacityHarnessConstants.REQUIRED_LEG_COUNT) {
            "expected ${Gres3CapacityHarnessConstants.REQUIRED_LEG_COUNT} legs, got ${legs.size}"
        }
        return legs
    }
}
