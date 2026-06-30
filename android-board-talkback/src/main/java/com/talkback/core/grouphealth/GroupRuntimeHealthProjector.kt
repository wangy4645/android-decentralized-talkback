package com.talkback.core.grouphealth

import com.talkback.core.model.ModuleId
import com.talkback.core.session.ChannelReadiness
import com.talkback.core.session.GroupMemberReachability
import com.talkback.core.session.GroupMembershipSupport
import com.talkback.core.session.GroupMeshPlanner
import com.talkback.core.session.MediaTopology
import com.talkback.core.session.SessionType
import com.talkback.core.session.TalkbackSession

/**
 * Pure GroupRuntimeHealth projection (ADR-0008 / R32). No side effects; mesh never gates readiness.
 */
object GroupRuntimeHealthProjector {

    const val SCHEMA_VERSION: Int = 1

    fun project(input: GroupRuntimeHealthInput): GroupRuntimeHealth {
        val session = input.session
        if (session == null || session.type != SessionType.GROUP) {
            return emptyProjection(
                input = input,
                readiness = GroupTopologyReadiness.DISCOVERING,
                mapped = mapDiscoveringChannelReadiness(input.dialablePeerCount)
            )
        }
        return projectGroupSession(input, session)
    }

    private fun projectGroupSession(
        input: GroupRuntimeHealthInput,
        session: TalkbackSession
    ): GroupRuntimeHealth {
        val local = input.localModuleId
        val members = GroupMembershipSupport.canonicalMemberModuleIds(session)
        val memberIds = members.map { it.value }.sorted()
        val suspectPeers = suspectPeerIds(session, members)
        val membershipDigestAligned = input.membershipDigestAlignedWithAuthority
        val membershipReconciled = session.accepted &&
            membershipDigestAligned &&
            suspectPeers.isEmpty()

        val topology = MediaTopology.forSession(session.mediaTopology, members.size)
        val anchor = session.anchorModuleId ?: local
        val initiator = session.initiatorModuleId ?: anchor
        val activeMembers = GroupMembershipSupport.activeMemberModuleIds(session, input.iceStateForModule)

        val meshDesiredPeerIds = meshDesiredPeerIds(local, initiator, anchor, members, topology)
        val meshDesiredLinks = meshDesiredPeerIds
            .map { MeshLink(local.value, it) }
            .toSet()

        val meshSignaledPeers = session.meshCompletedModules.intersect(memberIds.toSet())
        val meshIceConnectedPeers = memberIds
            .filter { it != local.value && input.peerMediaConnected.contains(it) }
            .toSet()

        val meshMissingSignal = meshDesiredPeerIds - meshSignaledPeers
        val meshMissingIce = meshDesiredPeerIds - meshIceConnectedPeers

        val transmitRequiredPeers = topology.transmitPeerIds(local, anchor, activeMembers)
        val transmitReadyPeers = transmitRequiredPeers.filter { input.peerMediaConnected.contains(it) }.toSet()
        val transmitMissingPeers = transmitRequiredPeers - transmitReadyPeers

        val readiness = when {
            !membershipReconciled -> GroupTopologyReadiness.MEMBERSHIP_PENDING
            transmitMissingPeers.isNotEmpty() -> GroupTopologyReadiness.BUILDING
            else -> GroupTopologyReadiness.OPERATIONAL
        }

        val floor = session.floor.snapshot()
        return GroupRuntimeHealth(
            schemaVersion = SCHEMA_VERSION,
            sessionId = session.id,
            localModuleId = local.value,
            topologyKind = session.mediaTopology,
            members = memberIds,
            rosterEpoch = session.rosterEpoch,
            memberHash = GroupMembershipSupport.memberHashForSession(session),
            sessionAccepted = session.accepted,
            membershipDigestAligned = membershipDigestAligned,
            suspectPeers = suspectPeers,
            membershipReconciled = membershipReconciled,
            meshDesiredLinks = meshDesiredLinks,
            meshSignaledPeers = meshSignaledPeers,
            meshIceConnectedPeers = meshIceConnectedPeers,
            meshMissingSignal = meshMissingSignal,
            meshMissingIce = meshMissingIce,
            transmitRequiredPeers = transmitRequiredPeers,
            transmitReadyPeers = transmitReadyPeers,
            transmitMissingPeers = transmitMissingPeers,
            groupTopologyReadiness = readiness,
            mappedChannelReadiness = mapChannelReadiness(readiness, input.dialablePeerCount),
            convergenceAgeMs = input.convergenceAgeMs,
            floorOwnerKey = floor.owner?.key,
            floorEpoch = floor.epoch,
            floorVersion = floor.version,
            channelGated = input.channelGated
        )
    }

    private fun meshDesiredPeerIds(
        local: ModuleId,
        initiator: ModuleId,
        anchor: ModuleId,
        members: Set<ModuleId>,
        topology: MediaTopology
    ): Set<String> {
        val invite = GroupMeshPlanner.inviteTargets(local, members).map { it.value }
        val join = topology.joinTargets(local, initiator, anchor, members).map { it.value }
        return (invite + join).toSet()
    }

    private fun suspectPeerIds(
        session: TalkbackSession,
        members: Set<ModuleId>
    ): Set<String> = members
        .map { it.value }
        .filter { moduleId ->
            moduleId != session.local.moduleId.value &&
                GroupMembershipSupport.membershipState(session, moduleId) ==
                GroupMemberReachability.SUSPECT
        }
        .toSet()

    private fun emptyProjection(
        input: GroupRuntimeHealthInput,
        readiness: GroupTopologyReadiness,
        mapped: ChannelReadiness
    ): GroupRuntimeHealth = GroupRuntimeHealth(
        schemaVersion = SCHEMA_VERSION,
        sessionId = null,
        localModuleId = input.localModuleId.value,
        topologyKind = null,
        members = emptyList(),
        rosterEpoch = 0L,
        memberHash = 0,
        sessionAccepted = false,
        membershipDigestAligned = false,
        suspectPeers = emptySet(),
        membershipReconciled = false,
        meshDesiredLinks = emptySet(),
        meshSignaledPeers = emptySet(),
        meshIceConnectedPeers = emptySet(),
        meshMissingSignal = emptySet(),
        meshMissingIce = emptySet(),
        transmitRequiredPeers = emptySet(),
        transmitReadyPeers = emptySet(),
        transmitMissingPeers = emptySet(),
        groupTopologyReadiness = readiness,
        mappedChannelReadiness = mapped,
        convergenceAgeMs = input.convergenceAgeMs,
        floorOwnerKey = null,
        floorEpoch = 0L,
        floorVersion = 0L,
        channelGated = input.channelGated
    )

    fun mapChannelReadiness(
        readiness: GroupTopologyReadiness,
        dialablePeerCount: Int
    ): ChannelReadiness = when (readiness) {
        GroupTopologyReadiness.OPERATIONAL -> ChannelReadiness.READY
        GroupTopologyReadiness.BUILDING -> ChannelReadiness.DIRECTORY_SYNC
        GroupTopologyReadiness.MEMBERSHIP_PENDING -> ChannelReadiness.DIRECTORY_SYNC
        GroupTopologyReadiness.DISCOVERING -> mapDiscoveringChannelReadiness(dialablePeerCount)
    }

    private fun mapDiscoveringChannelReadiness(dialablePeerCount: Int): ChannelReadiness =
        if (dialablePeerCount > 0) ChannelReadiness.CONNECTING else ChannelReadiness.DISCOVERING
}
