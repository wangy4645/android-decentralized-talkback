package com.talkback.governance.coordinator

import com.talkback.governance.capability.Capability
import com.talkback.governance.capability.CapabilityProbe
import com.talkback.governance.capability.CapabilityReadiness
import com.talkback.governance.capability.adapter.StubCapabilityProbe

internal class CoordinatorCapabilityProbes(host: ChannelGovernanceHost) {
    val membership = StubCapabilityProbe(Capability.Membership) { channelId ->
        val snap = host.acceptedGroupSession(channelId) ?: return@StubCapabilityProbe CapabilityReadiness.NOT_READY
        when {
            snap.membershipReconciled -> CapabilityReadiness.READY
            else -> CapabilityReadiness.RECONCILING
        }
    }

    val routing = StubCapabilityProbe(Capability.Routing) { channelId ->
        val snap = host.acceptedGroupSession(channelId) ?: return@StubCapabilityProbe CapabilityReadiness.NOT_READY
        when {
            snap.topologyReadiness == TopologyReadinessLabel.OPERATIONAL &&
                snap.transmitMissingPeers.isEmpty() -> CapabilityReadiness.READY
            else -> CapabilityReadiness.RECONCILING
        }
    }

    val authority = StubCapabilityProbe(Capability.Authority) { channelId ->
        val snap = host.acceptedGroupSession(channelId) ?: return@StubCapabilityProbe CapabilityReadiness.NOT_READY
        when {
            snap.floorAuthorityKnown && snap.membershipDigestAligned -> CapabilityReadiness.READY
            else -> CapabilityReadiness.RECONCILING
        }
    }

    val conference = StubCapabilityProbe(Capability.Conference) { channelId ->
        when (host.conferenceAdmission(channelId)) {
            CapabilityReadiness.READY -> CapabilityReadiness.READY
            CapabilityReadiness.RECONCILING -> CapabilityReadiness.RECONCILING
            else -> CapabilityReadiness.NOT_READY
        }
    }

    val media = StubCapabilityProbe(Capability.Media) { channelId ->
        host.conferenceSessionMedia(channelId)?.let { return@StubCapabilityProbe it }
        val snap = host.acceptedGroupSession(channelId) ?: return@StubCapabilityProbe CapabilityReadiness.NOT_READY
        when {
            snap.topologyReadiness == TopologyReadinessLabel.OPERATIONAL &&
                snap.transmitMissingPeers.isEmpty() -> CapabilityReadiness.READY
            snap.topologyReadiness == TopologyReadinessLabel.BUILDING -> CapabilityReadiness.RECONCILING
            else -> CapabilityReadiness.NOT_READY
        }
    }

    val directory = StubCapabilityProbe(Capability.Directory) { _ ->
        host.directoryReadiness()
    }

    val unicastRouting = StubCapabilityProbe(Capability.Routing) { _ ->
        host.unicastRoutingReady()
    }

    val channelProbes: List<CapabilityProbe> = listOf(
        membership,
        routing,
        authority,
        conference,
        media
    )

    val unicastProbes: List<CapabilityProbe> = listOf(directory, unicastRouting)
}
