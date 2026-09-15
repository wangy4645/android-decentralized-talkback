package com.talkback.core.conference.capacity

enum class Gres3TopologyClass {
    LOOPBACK,
    WLAN_CAPACITY_SINK,
}

data class Gres3SenderDut(
    val deviceLabel: String,
    val deviceSerial: String,
    val moduleId: String,
    val wlanInterface: String,
    val localIp: String? = null,
)

data class Gres3FormalTarget(
    val receiverModuleId: String,
    val receiverIp: String,
    val receiverPort: Int,
    val sinkIdentity: String,
    val sinkHostLabel: String,
) {
    fun toEndpoint(): PerReceiverUnicastEndpoint =
        PerReceiverUnicastEndpoint(
            receiverModuleId = receiverModuleId,
            moduleFixedIp = receiverIp,
            mediaPort = receiverPort,
        )

    fun endpointTuple(): String = "$receiverIp:$receiverPort"
}

data class Gres3FormalTopology(
    val topologyId: String,
    val topologyClass: Gres3TopologyClass,
    val senderDut: Gres3SenderDut,
    val targets: List<Gres3FormalTarget>,
) {
    init {
        require(targets.size == Gres3CapacityHarnessConstants.REQUIRED_LEG_COUNT) {
            "formal topology requires ${Gres3CapacityHarnessConstants.REQUIRED_LEG_COUNT} targets"
        }
    }

    val endpoints: List<PerReceiverUnicastEndpoint>
        get() = targets.map { it.toEndpoint() }

    val distinctReceiverIpCount: Int
        get() = targets.map { it.receiverIp }.toSet().size

    val distinctModuleIdCount: Int
        get() = targets.map { it.receiverModuleId }.toSet().size
}
