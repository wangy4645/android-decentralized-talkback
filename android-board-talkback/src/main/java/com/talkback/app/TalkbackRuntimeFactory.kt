package com.talkback.app

import android.content.Context
import com.talkback.core.discovery.CompositeModuleDiscoveryService
import com.talkback.core.discovery.MeshSweepGossipConfig
import com.talkback.core.discovery.MeshSweepGossipDiscovery
import com.talkback.core.discovery.ModuleDiscoveryService
import com.talkback.core.discovery.NetworkInterfaceSubnetProvider
import com.talkback.core.discovery.NsdModuleDiscoveryService
import com.talkback.core.discovery.StaticPeerDiscoveryService
import com.talkback.core.discovery.StaticPeerEntry
import com.talkback.core.registry.EndpointRegistry
import com.talkback.core.signaling.DiscoveryTransport
import com.talkback.core.signaling.DiscoveryUdpSocket
import com.talkback.core.signaling.SignalingChannel
import com.talkback.core.signaling.UdpSignalingChannel
import com.talkback.core.webrtc.MediaBearerScope
import com.talkback.core.webrtc.SessionMediaRegistry

enum class AudioEngineMode {
    REAL_WEBRTC,
    STUB
}

data class TalkbackRuntimeBundle(
    val runtime: TalkbackRuntime,
    val gossipDiscovery: MeshSweepGossipDiscovery?
)

object TalkbackRuntimeFactory {
    fun create(
        context: Context,
        config: TalkbackRuntimeConfig,
        mode: AudioEngineMode = AudioEngineMode.REAL_WEBRTC,
        staticPeers: List<StaticPeerEntry> = emptyList(),
        discoveryService: ModuleDiscoveryService? = null,
        gossipDiscovery: MeshSweepGossipDiscovery? = null,
        discoveryTransport: DiscoveryTransport? = null,
        signalingChannel: SignalingChannel = UdpSignalingChannel(),
        onLog: ((String) -> Unit)? = null
    ): TalkbackRuntime {
        return createBundle(
            context = context,
            config = config,
            mode = mode,
            staticPeers = staticPeers,
            discoveryService = discoveryService,
            gossipDiscovery = gossipDiscovery,
            discoveryTransport = discoveryTransport,
            signalingChannel = signalingChannel,
            onLog = onLog
        ).runtime
    }

    fun createBundle(
        context: Context,
        config: TalkbackRuntimeConfig,
        mode: AudioEngineMode = AudioEngineMode.REAL_WEBRTC,
        staticPeers: List<StaticPeerEntry> = emptyList(),
        discoveryService: ModuleDiscoveryService? = null,
        gossipDiscovery: MeshSweepGossipDiscovery? = null,
        discoveryTransport: DiscoveryTransport? = null,
        signalingChannel: SignalingChannel = UdpSignalingChannel(),
        onLog: ((String) -> Unit)? = null
    ): TalkbackRuntimeBundle {
        val endpointRegistry = EndpointRegistry(config.localModuleId)
        val staticDiscovery = StaticPeerDiscoveryService(staticPeers)
        val gossip = gossipDiscovery ?: MeshSweepGossipDiscovery(
            sharedSecret = { config.sharedSecret },
            subnetProvider = NetworkInterfaceSubnetProvider(),
            transport = discoveryTransport ?: DiscoveryUdpSocket(),
            config = MeshSweepGossipConfig(
                discoveryPort = config.discoveryPort,
                sweepMaxHosts = config.sweepMaxHosts,
                peerTtlMs = config.discoveryPeerTtlMs,
                announceIntervalMs = config.discoveryAnnounceIntervalMs,
                replayWindowMs = config.replayWindowMs
            )
        )
        val resolvedDiscovery = discoveryService ?: CompositeModuleDiscoveryService(
            staticDiscovery,
            gossip,
            NsdModuleDiscoveryService(context)
        )
        val coordinatorConfig = TalkbackCoordinatorConfig(
            autoAcceptIncoming = config.autoAcceptIncoming,
            sessionIdleTimeoutMs = config.sessionIdleTimeoutMs,
            cleanupIntervalMs = config.cleanupIntervalMs,
            heartbeatIntervalMs = config.heartbeatIntervalMs,
            autoReDialOnModuleRecovery = config.autoReDialOnModuleRecovery,
            sharedSecret = config.sharedSecret,
            replayWindowMs = config.replayWindowMs,
            allowedModuleIds = config.allowedModuleIds,
            maxActiveSessions = config.maxActiveSessions,
            maxGroupModules = config.maxGroupModules,
            maxConferenceModules = config.maxConferenceModules,
            useStubWebRtc = mode == AudioEngineMode.STUB,
            iceReconnectEnabled = config.iceReconnectEnabled,
            moduleStaleMs = config.moduleStaleMs,
            floorRetryMs = 400L,
            autoAcceptConferenceInvites = config.autoAcceptConferenceInvites,
            discoveryPort = config.discoveryPort,
            sweepMaxHosts = config.sweepMaxHosts,
            discoveryPeerTtlMs = config.discoveryPeerTtlMs,
            discoveryAnnounceIntervalMs = config.discoveryAnnounceIntervalMs,
            conferenceHostIceReconnectGraceMs = config.conferenceHostIceReconnectGraceMs,
            conferenceInviteRingTimeoutMs = config.conferenceInviteRingTimeoutMs,
            meshNegotiationGraceMs = config.meshNegotiationGraceMs,
            edgeRecoveryAttemptBudgetMs = config.edgeRecoveryAttemptBudgetMs,
            edgeRecoveryObservationWindowMs = config.edgeRecoveryObservationWindowMs,
            acquireReleaseTimeoutMs = config.acquireReleaseTimeoutMs
        )
        lateinit var coordinator: TalkbackCoordinator
        val mediaRegistry = SessionMediaRegistry(
            context,
            mode == AudioEngineMode.STUB,
            onMeshIce = { moduleId, state -> coordinator.onIceStateChanged(moduleId, state) },
            onUnicastIce = { sessionId, state ->
                coordinator.onIceStateChanged(MediaBearerScope.UNICAST, sessionId, state)
            }
        )
        coordinator = TalkbackCoordinator(
            discoveryService = resolvedDiscovery,
            signalingChannel = signalingChannel,
            mediaRegistry = mediaRegistry,
            localModuleId = config.localModuleId,
            endpointRegistry = endpointRegistry,
            config = coordinatorConfig,
            localDeviceHealth = AndroidBatteryHealthProvider(context),
            onLog = onLog
        )
        coordinator.updateStaticPeers(staticPeers)
        val runtime = TalkbackRuntime(config, coordinator, endpointRegistry, staticDiscovery, gossip)
        return TalkbackRuntimeBundle(runtime, gossip)
    }
}
