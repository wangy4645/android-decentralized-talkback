package com.talkback.core.conference.capacity

import java.net.Inet4Address
import java.net.InetAddress

data class Gres3TargetRouteHint(
    val receiverModuleId: String,
    val receiverIp: String,
    val receiverPort: Int,
    val resolvesViaInterface: String?,
    val isLoopback: Boolean,
    val isMulticast: Boolean,
)

data class Gres3TopologyPreflightResult(
    val passes: Boolean,
    val targetCountValid: Boolean,
    val moduleIdsDistinct: Boolean,
    val endpointTuplesDistinct: Boolean,
    val noLoopbackTargets: Boolean,
    val noMulticastTargets: Boolean,
    val senderNotLoopbackOnly: Boolean,
    val sinkBindingsVerified: Boolean,
    val routeHints: List<Gres3TargetRouteHint>,
    val blockerReasons: List<String>,
)

/**
 * H1c topology validity checks before measurement.
 */
object Gres3TopologyPreflight {
    fun validate(
        topology: Gres3FormalTopology,
        senderLocalIp: String?,
        sinkBindingsVerified: Boolean,
        intendedWlanInterface: String = topology.senderDut.wlanInterface,
    ): Gres3TopologyPreflightResult {
        val blockers = mutableListOf<String>()
        val targetCountValid = topology.targets.size == Gres3CapacityHarnessConstants.REQUIRED_LEG_COUNT
        if (!targetCountValid) {
            blockers += "targetCount=${topology.targets.size} expected=${Gres3CapacityHarnessConstants.REQUIRED_LEG_COUNT}"
        }

        val moduleIdsDistinct = topology.distinctModuleIdCount == topology.targets.size
        if (!moduleIdsDistinct) {
            blockers += "receiverModuleId not distinct"
        }

        val tuples = topology.targets.map { it.endpointTuple() }
        val endpointTuplesDistinct = tuples.size == tuples.toSet().size
        if (!endpointTuplesDistinct) {
            blockers += "duplicate IP:port tuples"
        }

        val routeHints =
            topology.targets.map { target ->
                val address = InetAddress.getByName(target.receiverIp)
                Gres3TargetRouteHint(
                    receiverModuleId = target.receiverModuleId,
                    receiverIp = target.receiverIp,
                    receiverPort = target.receiverPort,
                    resolvesViaInterface = intendedWlanInterface,
                    isLoopback = isLoopbackAddress(address),
                    isMulticast = address.isMulticastAddress,
                )
            }

        val noLoopbackTargets = routeHints.none { it.isLoopback }
        if (!noLoopbackTargets) {
            blockers += "target resolves to loopback"
        }

        val noMulticastTargets = routeHints.none { it.isMulticast }
        if (!noMulticastTargets) {
            blockers += "multicast destination forbidden"
        }

        val senderNotLoopbackOnly =
            when {
                topology.topologyClass == Gres3TopologyClass.LOOPBACK -> true
                senderLocalIp.isNullOrBlank() -> false
                else -> !isLoopbackLiteral(senderLocalIp)
            }
        if (topology.topologyClass != Gres3TopologyClass.LOOPBACK && !senderNotLoopbackOnly) {
            blockers += "sender local IP missing or loopback on formal topology"
        }

        if (!sinkBindingsVerified) {
            blockers += "remote sinks not verified bound"
        }

        if (topology.topologyClass != Gres3TopologyClass.LOOPBACK &&
            topology.distinctReceiverIpCount < 2
        ) {
            blockers += "formal topology requires >=2 distinct receiver IPs (got ${topology.distinctReceiverIpCount})"
        }

        return Gres3TopologyPreflightResult(
            passes = blockers.isEmpty(),
            targetCountValid = targetCountValid,
            moduleIdsDistinct = moduleIdsDistinct,
            endpointTuplesDistinct = endpointTuplesDistinct,
            noLoopbackTargets = noLoopbackTargets,
            noMulticastTargets = noMulticastTargets,
            senderNotLoopbackOnly = senderNotLoopbackOnly,
            sinkBindingsVerified = sinkBindingsVerified,
            routeHints = routeHints,
            blockerReasons = blockers,
        )
    }

    fun isLoopbackLiteral(ip: String): Boolean =
        ip == "127.0.0.1" || ip.startsWith("127.") || ip == "::1"

    private fun isLoopbackAddress(address: InetAddress): Boolean =
        address.isLoopbackAddress || isLoopbackLiteral(address.hostAddress ?: "")
}
