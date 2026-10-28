package com.talkback.core.webrtc.conferenceaudio

import com.talkback.core.session.AnchorAdmissionInput
import com.talkback.core.session.ConferenceTopologyAuthority
import com.talkback.core.session.ConferenceTopologyMode

/**
 * Phase 1b harness — topology authority → media edge authority → media bus (test only).
 *
 * Does not touch Coordinator, PeerConnection, recovery, or production Meeting.
 */
class TopologyMediaBridgeHarness(
    val topologyAuthority: ConferenceTopologyAuthority = ConferenceTopologyAuthority(),
    val mediaEdgeAuthority: MediaEdgeAuthority = MediaEdgeAuthority()
) {
    val busEvents = mutableListOf<MediaBusEvent>()

    fun publishAnchorStar(
        conferenceId: String = "conf-bridge",
        host: String = "M01",
        anchor: String = "M01",
        members: List<String> = listOf("M01", "M02", "M03", "M04")
    ): MediaEdgeAuthorityResult {
        val input = AnchorAdmissionInput(
            conferenceId = conferenceId,
            hostModuleId = host,
            anchorId = anchor,
            members = members
        )
        val publish = topologyAuthority.publishTopologyProjection(input)
        if (publish !is ConferenceTopologyAuthority.PublishResult.Published) {
            return MediaEdgeAuthorityResult.Rejected("topology publish failed: $publish")
        }
        return mediaEdgeAuthority.bind(publish.snapshot)
    }

    fun createBusFromBoundSnapshot(includeLocalMic: Boolean = false): Result<ConferenceMediaBusSession> {
        val spec = mediaEdgeAuthority.deriveAnchorStarBusSpec(includeLocalMic)
            .getOrElse { return Result.failure(it) }
        return ConferenceMediaBusSession.create(
            spec = spec,
            onEvent = { busEvents.add(it) }
        )
    }

    fun snapshotMode(): ConferenceTopologyMode? {
        val conferenceId = mediaEdgeAuthority.snapshot?.conferenceId ?: return null
        return topologyAuthority.currentSnapshot(conferenceId)?.topologyMode
    }
}
