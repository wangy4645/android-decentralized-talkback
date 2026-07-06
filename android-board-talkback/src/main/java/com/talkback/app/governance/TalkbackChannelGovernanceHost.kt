package com.talkback.app.governance

import com.talkback.app.TalkbackCoordinator
import com.talkback.governance.capability.CapabilityReadiness
import com.talkback.governance.coordinator.ChannelGovernanceHost
import com.talkback.governance.coordinator.GroupChannelSnapshot
import com.talkback.governance.coordinator.TopologyReadinessLabel

internal class TalkbackChannelGovernanceHost(
    private val coordinator: TalkbackCoordinator
) : ChannelGovernanceHost {
    override fun acceptedGroupSession(channelId: String): GroupChannelSnapshot? =
        coordinator.governanceSnapshotForChannel(channelId)

    override fun conferenceAdmission(channelId: String): CapabilityReadiness =
        coordinator.governanceConferenceAdmission(channelId)

    override fun conferenceSessionMedia(channelId: String): CapabilityReadiness? =
        coordinator.governanceConferenceSessionMedia(channelId)

    override fun directoryReadiness(): CapabilityReadiness =
        coordinator.governanceDirectoryReadiness()

    override fun unicastRoutingReady(): CapabilityReadiness =
        coordinator.governanceUnicastRoutingReadiness()
}
