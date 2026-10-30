package com.talkback.core.conference.capacity

/**
 * Frozen lab topology: M03 sender → M01 (6 sinks) + M02 (3 sinks).
 */
object Gres3FormalTopologyLab {
    const val TOPOLOGY_ID: String = "gres3-h1c-lab-m03-sender-v1"

    fun m03SenderV1(
        m01Ip: String,
        m02Ip: String,
        senderDut: Gres3SenderDut,
        senderLocalIp: String? = null,
    ): Gres3FormalTopology {
        val dut = senderDut.copy(localIp = senderLocalIp ?: senderDut.localIp)
        val assignments =
            listOf(
                Triple("R01", m01Ip, 47_001) to "M01",
                Triple("R02", m01Ip, 47_002) to "M01",
                Triple("R03", m01Ip, 47_003) to "M01",
                Triple("R04", m02Ip, 47_004) to "M02",
                Triple("R05", m02Ip, 47_005) to "M02",
                Triple("R06", m02Ip, 47_006) to "M02",
                Triple("R07", m01Ip, 47_007) to "M01",
                Triple("R08", m01Ip, 47_008) to "M01",
                Triple("R09", m01Ip, 47_009) to "M01",
            )
        val targets =
            assignments.map { (triple, sinkHost) ->
                val (moduleId, ip, port) = triple
                Gres3FormalTarget(
                    receiverModuleId = moduleId,
                    receiverIp = ip,
                    receiverPort = port,
                    sinkIdentity = "$sinkHost:$port",
                    sinkHostLabel = sinkHost,
                )
            }
        return Gres3FormalTopology(
            topologyId = TOPOLOGY_ID,
            topologyClass = Gres3TopologyClass.WLAN_CAPACITY_SINK,
            senderDut = dut,
            targets = targets,
        )
    }
}
