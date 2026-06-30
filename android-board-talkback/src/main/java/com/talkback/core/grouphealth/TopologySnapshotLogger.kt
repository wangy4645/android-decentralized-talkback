package com.talkback.core.grouphealth

import com.talkback.core.util.TalkbackLog

/**
 * Serializes [GroupRuntimeHealth] to logcat (ADR-0008 schemaVersion 1).
 */
object TopologySnapshotLogger {

    const val TAG = "TOPOLOGY_SNAPSHOT"

    fun log(reason: TopologySnapshotReason, health: GroupRuntimeHealth) {
        TalkbackLog.i(format(reason, health))
    }

    fun format(reason: TopologySnapshotReason, health: GroupRuntimeHealth): String =
        buildString {
            append(TAG)
            append(" reason=").append(reason.name)
            append(" schemaVersion=").append(health.schemaVersion)
            append(" sessionId=").append(health.sessionId ?: "null")
            append(" localModuleId=").append(health.localModuleId)
            append(" topologyKind=").append(health.topologyKind?.name ?: "null")
            append(" members=").append(joinCsv(health.members))
            append(" rosterEpoch=").append(health.rosterEpoch)
            append(" memberHash=").append(health.memberHash)
            append(" sessionAccepted=").append(health.sessionAccepted)
            append(" membershipDigestAligned=").append(health.membershipDigestAligned)
            append(" suspectPeers=").append(joinCsv(health.suspectPeers.sorted()))
            append(" membershipReconciled=").append(health.membershipReconciled)
            append(" meshDesiredLinks=").append(
                joinCsv(health.meshDesiredLinks.map { it.toString() }.sorted())
            )
            append(" meshSignaledPeers=").append(joinCsv(health.meshSignaledPeers.sorted()))
            append(" meshIceConnectedPeers=").append(joinCsv(health.meshIceConnectedPeers.sorted()))
            append(" meshMissingSignal=").append(joinCsv(health.meshMissingSignal.sorted()))
            append(" meshMissingIce=").append(joinCsv(health.meshMissingIce.sorted()))
            append(" transmitRequiredPeers=").append(joinCsv(health.transmitRequiredPeers.sorted()))
            append(" transmitReadyPeers=").append(joinCsv(health.transmitReadyPeers.sorted()))
            append(" transmitMissingPeers=").append(joinCsv(health.transmitMissingPeers.sorted()))
            append(" groupTopologyReadiness=").append(health.groupTopologyReadiness.name)
            append(" mappedChannelReadiness=").append(health.mappedChannelReadiness.name)
            append(" convergenceAgeMs=").append(health.convergenceAgeMs)
            append(" floorOwner=").append(health.floorOwnerKey ?: "null")
            append(" floorEpoch=").append(health.floorEpoch)
            append(" floorVersion=").append(health.floorVersion)
            append(" channelGated=").append(health.channelGated)
        }

    private fun joinCsv(values: Collection<String>): String =
        if (values.isEmpty()) "" else values.joinToString(",")
}
