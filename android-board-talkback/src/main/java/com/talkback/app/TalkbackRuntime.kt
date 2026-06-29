package com.talkback.app

import com.talkback.core.discovery.MeshSweepGossipConfig
import com.talkback.core.media.MediaTopologyPolicy
import com.talkback.core.discovery.MeshSweepGossipDiscovery
import com.talkback.core.discovery.StaticPeerDiscoveryService
import com.talkback.core.discovery.StaticPeerEntry
import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.EndpointId
import com.talkback.core.model.EndpointPriority
import com.talkback.core.model.ModuleId
import com.talkback.core.presence.ModulePresenceSnapshot
import com.talkback.core.presence.SessionPresenceSnapshot
import com.talkback.core.ptt.PttState
import com.talkback.core.registry.EndpointRegistry
import com.talkback.core.webrtc.MediaBearerScope
import com.talkback.core.session.ChannelReadiness
import com.talkback.core.sync.RemoteModuleState

data class TalkbackRuntimeConfig(
    val localModuleId: ModuleId,
    val signalingPort: Int,
    val autoAcceptIncoming: Boolean = true,
    val sessionIdleTimeoutMs: Long = 30_000L,
    val cleanupIntervalMs: Long = 5_000L,
    val heartbeatIntervalMs: Long = 2_000L,
    val autoReDialOnModuleRecovery: Boolean = true,
    val sharedSecret: String = "",
    val replayWindowMs: Long = 15_000L,
    val allowedModuleIds: Set<String> = emptySet(),
    val maxActiveSessions: Int = 1,
    val maxGroupModules: Int = MediaTopologyPolicy.DEFAULT_MAX_GROUP_MODULES,
    val maxConferenceModules: Int = MediaTopologyPolicy.DEFAULT_MAX_CONFERENCE_MODULES,
    val iceReconnectEnabled: Boolean = true,
    val moduleStaleMs: Long = 15_000L,
    val autoAcceptConferenceInvites: Boolean = true,
    val discoveryPort: Int = MeshSweepGossipConfig.DEFAULT_DISCOVERY_PORT,
    val sweepMaxHosts: Int = 256,
    val discoveryPeerTtlMs: Long = 45_000L,
    val discoveryAnnounceIntervalMs: Long = 10_000L,
    val conferenceHostIceReconnectGraceMs: Long = 5_000L,
    val conferenceInviteRingTimeoutMs: Long = 20_000L,
    /** ADR-0004 interim; Phase 3 enforces auto FLOOR_RELEASE on acquire timeout. */
    val acquireReleaseTimeoutMs: Long = 500L
)

/**
 * Runtime facade for LAN-only, decentralized talkback.
 */
class TalkbackRuntime(
    private val config: TalkbackRuntimeConfig,
    private val coordinator: TalkbackCoordinator,
    private val endpointRegistry: EndpointRegistry,
    private val staticDiscovery: StaticPeerDiscoveryService,
    private val gossipDiscovery: MeshSweepGossipDiscovery? = null
) {
    fun acquireReleaseTimeoutMs(): Long = config.acquireReleaseTimeoutMs

    fun start() {
        coordinator.start(config.signalingPort)
    }

    fun stop() {
        coordinator.stop()
    }

    fun updateStaticPeers(peers: List<StaticPeerEntry>) {
        staticDiscovery.updatePeers(peers)
        coordinator.updateStaticPeers(peers)
    }

    fun resetDiscovery() {
        gossipDiscovery?.resetAndSweep()
    }

    fun call(from: EndpointAddress, to: EndpointAddress, channelId: String? = null): String =
        coordinator.call(from, to, channelId)

    fun groupCall(
        from: EndpointAddress,
        remoteEndpoints: List<EndpointAddress>,
        channelId: String
    ): String? = coordinator.groupCall(from, remoteEndpoints, channelId)

    fun conferenceCall(
        from: EndpointAddress,
        remoteEndpoints: List<EndpointAddress>,
        channelId: String
    ): String? = coordinator.conferenceCall(from, remoteEndpoints, channelId)

    fun sendConferenceInvites(sessionId: String, invitees: List<EndpointAddress>): Int =
        coordinator.sendConferenceInvites(sessionId, invitees)

    fun sendConferenceRejoin(
        channelId: String,
        authority: EndpointAddress,
        hostSessionId: String
    ): Boolean = coordinator.sendConferenceRejoin(channelId, authority, hostSessionId)

    fun rejoinableConference(channelId: String): RejoinableConferenceSnapshot? =
        runCatching { coordinator.rejoinableConference(channelId) }.getOrNull()

    fun isConferenceRejoinInProgress(channelId: String): Boolean =
        runCatching { coordinator.isConferenceRejoinInProgress(channelId) }.getOrElse { false }

    fun isConferenceReconnecting(channelId: String): Boolean =
        runCatching { coordinator.isConferenceReconnecting(channelId) }.getOrElse { false }

    fun isConferenceReconnectFailed(channelId: String): Boolean =
        runCatching { coordinator.isConferenceReconnectFailed(channelId) }.getOrElse { false }

    fun pressPtt(sessionId: String, priority: EndpointPriority = EndpointPriority.NORMAL): PttState =
        coordinator.onPttPressed(sessionId, priority)

    fun releasePtt(sessionId: String) {
        coordinator.onPttReleased(sessionId)
    }

    fun hangup(sessionId: String) {
        coordinator.hangup(sessionId)
    }

    fun leaveConference(sessionId: String) {
        coordinator.leaveConference(sessionId)
    }

    fun clearConferencePttCooldown(channelId: String) {
        coordinator.clearConferencePttCooldown(channelId)
    }

    fun acceptCall(sessionId: String) {
        coordinator.acceptCall(sessionId)
    }

    fun rejectCall(sessionId: String, reason: String = "DECLINED") {
        coordinator.rejectCall(sessionId, reason)
    }

    fun setCallMuted(sessionId: String, muted: Boolean) {
        coordinator.setCallMuted(sessionId, muted)
    }

    fun setAutoAcceptConferenceInvites(enabled: Boolean) {
        coordinator.setAutoAcceptConferenceInvites(enabled)
    }

    fun setMeetingPreferred(preferred: Boolean, channelId: String? = null) {
        coordinator.setMeetingPreferred(preferred, channelId)
    }

    fun channelMode(channelId: String): com.talkback.core.session.ChannelMode? =
        runCatching { coordinator.channelMode(channelId) }.getOrNull()

    fun isConferenceHostForChannel(channelId: String): Boolean =
        runCatching { coordinator.isConferenceHostForChannel(channelId) }.getOrElse { false }

    fun pendingConferenceInvite(channelId: String? = null): ConferenceInviteSnapshot? =
        runCatching { coordinator.pendingConferenceInvite(channelId) }.getOrNull()

    fun acceptPendingConferenceInvite(channelId: String): Boolean =
        runCatching { coordinator.acceptPendingConferenceInvite(channelId) }.getOrElse { false }

    fun rejectPendingConferenceInvite(channelId: String, reason: String = "DECLINED"): Boolean =
        runCatching { coordinator.rejectPendingConferenceInvite(channelId, reason) }.getOrElse { false }

    fun activeUnicastSession(): TalkbackSessionSnapshot? =
        runCatching { coordinator.activeUnicastSession() }.getOrNull()

    fun activeSessionIds(): List<String> = runCatching { coordinator.activeSessionIds() }.getOrElse { emptyList() }
    fun sessionSnapshots(): List<TalkbackSessionSnapshot> =
        runCatching { coordinator.sessionSnapshots() }.getOrElse { emptyList() }

    fun sessionSnapshotForChannel(channelId: String): TalkbackSessionSnapshot? =
        runCatching { coordinator.sessionSnapshotForChannel(channelId) }.getOrNull()

    fun sessionPresenceSnapshot(sessionId: String): SessionPresenceSnapshot? =
        runCatching { coordinator.sessionPresenceSnapshot(sessionId) }.getOrNull()

    fun sessionPresenceSnapshots(): List<SessionPresenceSnapshot> =
        runCatching { coordinator.sessionPresenceSnapshots() }.getOrElse { emptyList() }

    fun modulePresenceSnapshot(): ModulePresenceSnapshot =
        runCatching { coordinator.modulePresenceSnapshot() }
            .getOrElse {
                ModulePresenceSnapshot(
                    localUplinkGrant = false,
                    activeCaptureEndpointKey = null,
                    iceByPeer = emptyMap()
                )
            }

    fun channels(): List<com.talkback.core.channel.Channel> =
        runCatching { coordinator.channels() }.getOrElse { emptyList() }

    internal fun testEvictGroupMember(sessionId: String, moduleId: String) =
        coordinator.testEvictGroupMember(sessionId, moduleId)

    fun configureChannelMembership(channelId: String, moduleIds: List<String>) {
        coordinator.configureChannelMembership(channelId, moduleIds)
    }

    fun channelMemberModuleIds(channelId: String): Set<String> =
        runCatching { coordinator.channelMemberModuleIds(channelId) }.getOrElse { emptySet() }

    fun networkQualityLabel(): String = runCatching { coordinator.networkQualityLabel() }.getOrElse { "N/A" }
    fun onlineModuleCount(): Int = runCatching { coordinator.onlineModuleCount() }.getOrElse { 0 }
    fun qosSummary(): String = runCatching { coordinator.qosSummary() }.getOrElse { "" }

    fun qosSnapshotForModule(moduleId: String): com.talkback.core.qos.QosSnapshot? =
        runCatching { coordinator.qosSnapshotForModule(moduleId) }.getOrNull()

    fun isCurrentSpeakerReachable(channelId: String): Boolean =
        runCatching { coordinator.isCurrentSpeakerReachable(channelId) }.getOrElse { false }

    fun remotePlaybackEnabledForModule(moduleId: String): Boolean? =
        runCatching { coordinator.remotePlaybackEnabledForModule(moduleId) }.getOrNull()

    fun remoteModuleStates(): List<RemoteModuleState> =
        runCatching { coordinator.remoteModuleStates() }.getOrElse { emptyList() }

    fun peerDisplayRoster(): List<PeerDisplayRow> =
        runCatching { coordinator.peerDisplayRoster() }.getOrElse { emptyList() }

    fun isRemoteModuleReachable(moduleId: String): Boolean =
        runCatching { coordinator.isRemoteModuleReachable(moduleId) }.getOrElse { false }

    fun isRemoteModuleDialable(moduleId: String): Boolean =
        runCatching { coordinator.isRemoteModuleDialable(moduleId) }.getOrElse { false }

    fun conferenceAuthorityModuleId(channelId: String): String? =
        runCatching { coordinator.conferenceAuthorityModuleId(channelId) }.getOrNull()

    fun shouldLocalInitiateConference(channelId: String): Boolean =
        runCatching { coordinator.shouldLocalInitiateConference(channelId) }.getOrElse { true }

    fun primaryEndpointIdForModule(moduleId: String): String? =
        runCatching { coordinator.primaryEndpointIdForModule(moduleId) }.getOrElse { null }

    fun isChannelMediaReady(channelId: String): Boolean =
        runCatching { coordinator.isChannelMediaReady(channelId) }.getOrElse { false }

    fun isChannelConnecting(channelId: String): Boolean =
        runCatching { coordinator.isChannelConnecting(channelId) }.getOrElse { false }

    fun channelReadiness(channelId: String): ChannelReadiness =
        runCatching { coordinator.channelReadiness(channelId) }.getOrElse { ChannelReadiness.NO_SERVICE }

    fun meetingSpeakerAudioLevel(channelId: String, speakerEndpointKey: String): Float =
        runCatching { coordinator.meetingSpeakerAudioLevel(channelId, speakerEndpointKey) }.getOrElse { 0f }

    fun isGroupSessionTrulyIdle(channelId: String): Boolean =
        runCatching { coordinator.isGroupSessionTrulyIdle(channelId) }.getOrElse { true }

    fun refreshStaleGroupSession(channelId: String) {
        runCatching { coordinator.refreshStaleGroupSession(channelId) }
    }

    fun reconcileGroupMesh(channelId: String) {
        runCatching { coordinator.reconcileGroupMesh(channelId) }
    }

    fun refreshStaleConferenceSession(channelId: String) {
        runCatching { coordinator.refreshStaleConferenceSession(channelId) }
    }

    fun upsertLocalEndpoint(
        endpointId: EndpointId,
        displayName: String,
        online: Boolean,
        priority: EndpointPriority = EndpointPriority.NORMAL
    ) {
        endpointRegistry.upsertLocalEndpoint(endpointId, displayName, online, priority)
        coordinator.rebroadcastHello()
    }

    fun consumeFloorPreempted(sessionId: String): Boolean =
        coordinator.consumeFloorPreempted(sessionId)

    fun consumeAcquireTimedOut(sessionId: String): Boolean =
        coordinator.consumeAcquireTimedOut(sessionId)

    fun onlineEndpoints() = endpointRegistry.allOnline()

    internal fun simulateRemoteIceState(remoteModuleId: String, state: String) {
        coordinator.onIceStateChanged(remoteModuleId, state)
    }

    internal fun simulateUnicastIceState(sessionId: String, state: String) {
        coordinator.onIceStateChanged(MediaBearerScope.UNICAST, sessionId, state)
    }

    internal fun testForceRemotePlayback(moduleId: String, enabled: Boolean) {
        coordinator.testForceRemotePlayback(moduleId, enabled)
    }

    internal fun testInvariantF1BreakCount(): Int = coordinator.testInvariantF1BreakCount()

    internal fun testIsSessionCapturing(sessionId: String): Boolean =
        coordinator.testIsSessionCapturing(sessionId)

    internal fun testRefreshIceReachability(remoteModuleId: String, state: String) {
        coordinator.testRefreshIceReachability(remoteModuleId, state)
    }

    internal fun testForceGroupAnchorTopology(channelId: String) {
        coordinator.testForceGroupAnchorTopology(channelId)
    }

    internal fun testSeedDuplicateGroupSession(
        channelId: String,
        sessionId: String,
        initiatorModuleId: String,
        connectedPeerModuleIds: List<String> = emptyList()
    ) {
        coordinator.testSeedDuplicateGroupSession(
            channelId,
            sessionId,
            initiatorModuleId,
            connectedPeerModuleIds
        )
    }

    internal fun hasGroupMediaEngine(remoteModuleId: String): Boolean =
        coordinator.hasGroupMediaEngine(remoteModuleId)

    internal fun testInjectGroupInvite(
        callerModuleId: String,
        channelId: String,
        sessionId: String,
        initiatorModuleId: String,
        fromPeer: com.talkback.core.signaling.PeerTarget,
        memberModuleIds: List<String> = listOf("M01", "M02", "M03"),
        sdp: String = "v=0"
    ): Boolean = coordinator.testInjectGroupInvite(
        callerModuleId,
        channelId,
        sessionId,
        initiatorModuleId,
        fromPeer,
        memberModuleIds,
        sdp
    )

    internal fun testRunConferenceHealthCleanup(channelId: String) {
        coordinator.testRunConferenceHealthCleanup(channelId)
    }
}
