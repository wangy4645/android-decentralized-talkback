package com.talkback.governance.coordinator

import com.talkback.governance.capability.CapabilityReadiness
import com.talkback.governance.capability.CapabilitySnapshot
import com.talkback.governance.gate.GateDecision
import com.talkback.governance.gate.Operation
import com.talkback.governance.transition.BeginTransitionResult
import com.talkback.governance.transition.TransitionRecord
import com.talkback.governance.transition.TransitionTrigger

/**
 * Read-only bridge from TalkbackCoordinator runtime state into governance probes (ADR-0015).
 */
interface ChannelGovernanceHost {
    fun acceptedGroupSession(channelId: String): GroupChannelSnapshot?
    fun conferenceAdmission(channelId: String): CapabilityReadiness
    fun conferenceSessionMedia(channelId: String): CapabilityReadiness?
    fun directoryReadiness(): CapabilityReadiness
    fun unicastRoutingReady(): CapabilityReadiness
}

data class GroupChannelSnapshot(
    val channelId: String,
    val membershipReconciled: Boolean,
    val membershipDigestAligned: Boolean,
    val topologyReadiness: TopologyReadinessLabel,
    val transmitMissingPeers: Set<String>,
    val floorAuthorityKnown: Boolean,
    val identityStable: Boolean
)

enum class TopologyReadinessLabel {
    DISCOVERING,
    MEMBERSHIP_PENDING,
    BUILDING,
    OPERATIONAL
}
