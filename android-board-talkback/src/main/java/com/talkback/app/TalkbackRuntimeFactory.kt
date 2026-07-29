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
import com.talkback.core.signaling.AndroidSignalingSocketBinder
import com.talkback.core.signaling.DiscoveryTransport
import com.talkback.core.signaling.DiscoveryUdpSocket
import com.talkback.core.signaling.SignalingChannel
import com.talkback.core.signaling.SignalingTransportManager
import com.talkback.core.signaling.link.LinkQualificationState
import com.talkback.core.signaling.UdpSignalingChannel
import com.talkback.core.signaling.peer.PeerEdgePrrHintCoordinator
import com.talkback.core.signaling.peer.PeerEdgeSignalingReadiness
import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.EndpointId
import com.talkback.core.model.RemoteEndpointInfo
import com.talkback.core.signaling.prr.DiscoveryPrrHelloTargetProvider
import com.talkback.core.signaling.prr.LocalEndpointSnapshot
import com.talkback.core.signaling.prr.PeerReachabilityReannounceController
import com.talkback.core.signaling.prr.UdpSignalingReannounceSender
import com.talkback.core.webrtc.MediaBearerScope
import com.talkback.core.webrtc.SessionMediaRegistry
import java.util.concurrent.Executors

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
        signalingChannel: SignalingChannel? = null,
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
        signalingChannel: SignalingChannel? = null,
        onLog: ((String) -> Unit)? = null
    ): TalkbackRuntimeBundle {
        val endpointRegistry = EndpointRegistry(config.localModuleId)
        val helloTargetProvider = DiscoveryPrrHelloTargetProvider(config.localModuleId)
        val transportManager = SignalingTransportManager()
        val socketBinder = AndroidSignalingSocketBinder()
        val resolvedDiscoveryTransport = discoveryTransport ?: DiscoveryUdpSocket(socketBinder = socketBinder).also {
            transportManager.attachBinding(it)
        }
        val resolvedSignalingChannel = signalingChannel ?: UdpSignalingChannel(
            lifecycleReporter = transportManager,
            linkQualificationFacts = transportManager.linkQualificationFacts(),
            signalingGeneration = transportManager.signalingGenerationAuthority(),
            socketBinder = socketBinder,
            localModuleId = config.localModuleId.value
        ).also {
            transportManager.attachSignalingBinding(it)
        }
        // Peer-edge readiness Hard gate requires stamped receiveGeneration (UDP accept path).
        // Injected InMemory channels used by JVM integration tests do not stamp; keep readiness null.
        val peerEdgeSignalingReadiness = if (signalingChannel == null) {
            PeerEdgeSignalingReadiness(
                moduleStaleMs = config.moduleStaleMs,
                localSnapshot = { transportManager.linkQualificationSnapshot() }
            ).also { readiness ->
                transportManager.wirePeerEdgeSignalingReadiness(readiness)
            }
        } else {
            null
        }
        val prrSender = UdpSignalingReannounceSender(
            signalingChannel = resolvedSignalingChannel,
            sharedSecret = config.sharedSecret,
            helloTargetProvider = helloTargetProvider
        )
        val prrController = PeerReachabilityReannounceController(
            sender = prrSender,
            endpointSnapshot = {
                val endpoints = endpointRegistry.allOnline().map {
                    RemoteEndpointInfo(
                        endpointId = it.address.endpointId.value,
                        displayName = it.displayName,
                        online = it.online,
                        priority = it.priority
                    )
                }
                val from = endpointRegistry.allOnline().firstOrNull()?.address
                    ?: EndpointAddress(config.localModuleId, EndpointId("E01"))
                LocalEndpointSnapshot(
                    localModuleId = config.localModuleId.value,
                    endpoints = endpoints,
                    fromAddress = from,
                    signalingPort = config.signalingPort
                )
            }
        )
        transportManager.wirePrrController(prrController)
        if (peerEdgeSignalingReadiness != null) {
            val peerEdgePrrHintScheduler = Executors.newSingleThreadScheduledExecutor { r ->
                Thread(r, "peer-edge-prr-hint").apply { isDaemon = true }
            }
            val peerEdgePrrHint = PeerEdgePrrHintCoordinator(
                scheduler = peerEdgePrrHintScheduler,
                isStillNotReady = { moduleId -> !peerEdgeSignalingReadiness.isReady(moduleId) },
                announcePeer = { moduleId ->
                    val target = helloTargetProvider.helloTargetFor(moduleId) ?: return@PeerEdgePrrHintCoordinator
                    val snap = transportManager.linkQualificationSnapshot()
                    prrController.onPeerEdgeSignalingHint(
                        remoteModuleId = moduleId,
                        target = target,
                        transportEpoch = snap.rebindGeneration,
                        socketId = snap.socketId,
                        networkId = snap.networkId
                    )
                }
            )
            peerEdgeSignalingReadiness.onPeerEdgeSignalingLost = peerEdgePrrHint::onPeerEdgeSignalingLost
        }
        val networkObserver = NetworkCapabilityObserver(context, transportManager, socketBinder)
        val staticDiscovery = StaticPeerDiscoveryService(staticPeers)
        val gossip = gossipDiscovery ?: MeshSweepGossipDiscovery(
            sharedSecret = { config.sharedSecret },
            subnetProvider = NetworkInterfaceSubnetProvider(),
            transport = resolvedDiscoveryTransport,
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
        resolvedDiscovery.onPresenceChanged { helloTargetProvider.updatePresence(it) }
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
            signalingChannel = resolvedSignalingChannel,
            mediaRegistry = mediaRegistry,
            localModuleId = config.localModuleId,
            endpointRegistry = endpointRegistry,
            config = coordinatorConfig,
            localDeviceHealth = AndroidBatteryHealthProvider(context),
            linkQualificationSnapshot = {
                transportManager.readLinkQualificationSnapshot("recovery_gate")
            },
            peerEdgeSignalingReadiness = peerEdgeSignalingReadiness,
            onLog = onLog
        )
        transportManager.onLinkQualificationStateChanged { _, newState ->
            if (newState == LinkQualificationState.BIDIRECTIONAL_READY) {
                coordinator.onLinkQualificationStateChanged()
            }
        }
        coordinator.updateStaticPeers(staticPeers)
        val runtime = TalkbackRuntime(
            config,
            coordinator,
            endpointRegistry,
            staticDiscovery,
            gossip,
            networkObserver
        )
        return TalkbackRuntimeBundle(runtime, gossip)
    }
}
