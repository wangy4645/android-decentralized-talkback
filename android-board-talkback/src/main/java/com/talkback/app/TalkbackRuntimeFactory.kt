package com.talkback.app

import android.content.Context
import android.net.ConnectivityManager
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
import com.talkback.core.network.SelectedOperationalNetworkRegistry
import com.talkback.core.signaling.prr.DiscoveryPrrHelloTargetProvider
import com.talkback.core.webrtc.WebRtcNetworkBridgeInstall
import com.talkback.core.conference.session.ConferenceSessionMediaBridge
import com.talkback.core.conference.session.ConferenceSessionMediaCoordinatorDelegate
import com.talkback.core.conference.session.ConferenceSessionMediaControlFactRegistry
import com.talkback.core.conference.session.ConferenceSessionMediaWiring
import com.talkback.core.conference.session.integration.MeetingProfile01AwareConferenceSessionMediaFactPort
import com.talkback.core.conference.session.integration.MeetingProfile01ConferenceSessionIndex
import com.talkback.core.conference.session.integration.MeetingProfile01CreationOriginBridge
import com.talkback.core.conference.session.integration.MeetingProfile01MembershipOriginBridge
import com.talkback.core.conference.session.profile01.wire.Profile01FieldEstablishedSignedFactSignerSource
import com.talkback.core.conference.session.profile01.wire.Profile01SignedFactSignerSource
import com.talkback.core.conference.session.integration.MeetingProfile01FactWireIngress
import com.talkback.core.conference.session.integration.MeetingProfile01IncompleteApplyContinuation
import com.talkback.core.conference.session.integration.MeetingProfile01MediaKeyPackageOriginBridge
import com.talkback.core.conference.session.integration.MeetingProfile01PreBindFactRetention
import com.talkback.core.conference.session.integration.MeetingProfile01SourceOriginBridge
import com.talkback.core.conference.session.integration.Profile01HostLocalMediaSupplementMaterializer
import com.talkback.core.conference.session.integration.Profile01HostLocalSessionFactProjection
import com.talkback.core.conference.session.integration.Profile01LocalConferenceSourceIdentityAuthority
import com.talkback.core.conference.session.integration.Profile01ShadowMemberBindingMaterializer
import com.talkback.core.conference.session.integration.Profile01ShadowMulticastReceiveSeam
import com.talkback.core.conference.session.integration.Profile01ShadowPlayoutClockSeam
import com.talkback.core.conference.session.integration.cutover.ReplacementCutoverRc1
import com.talkback.app.cutover.TalkbackCoordinatorAnchorAudiblePort
import com.talkback.core.conference.session.integration.Profile01ShadowMulticastTransmitSeam
import com.talkback.core.conference.session.gbc.ConferenceSessionMediaGbcPublisherBridge
import com.talkback.core.conference.session.profile01.Profile01ConferenceMediaFactIngress
import com.talkback.core.conference.session.profile01.Profile01ConferenceMediaFactValidator
import com.talkback.core.conference.session.profile01.Profile01ProductionSignedFactTrustBoundary
import com.talkback.core.conference.session.profile01.Profile01ProfileBackedModuleTrustLookup
import com.talkback.core.conference.session.profile01.Profile01SessionMediaSupplementRegistry
import com.talkback.core.conference.session.profile01.wire.Profile01ConferenceMediaKeyMaterialAuthority
import com.talkback.core.conference.session.profile01.wire.Profile01EstablishmentAuthoritySurface
import com.talkback.core.conference.session.profile01.wire.Profile01ProductionMediaKeyDecryptComposition
import com.talkback.core.conference.transport.Slice4MulticastNetworkConstants
import com.talkback.core.session.gbc.trust.profile.AcceptedLocalTrustStateStore
import com.talkback.core.signaling.prr.LocalEndpointSnapshot
import com.talkback.core.signaling.prr.PeerReachabilityReannounceController
import com.talkback.core.signaling.prr.UdpSignalingReannounceSender
import com.talkback.core.webrtc.MediaBearerScope
import com.talkback.core.webrtc.SessionMediaRegistry
import com.talkback.core.session.gbc.wiring.EstablishmentProductionComposition
import com.talkback.core.session.gbc.wiring.GbcProductionTrustComposition
import com.talkback.core.session.gbc.wiring.GbcTrustWiring
import com.talkback.core.session.gbc.wiring.ProductionGbcTrustWiring
import com.talkback.core.session.gbc.trust.profile.OperationalTrustAnchorSource
import com.talkback.core.session.gbc.wiring.TestGbcTrustWiring
import java.util.concurrent.Executors

enum class AudioEngineMode {
    REAL_WEBRTC,
    STUB
}

data class TalkbackRuntimeBundle(
    val runtime: TalkbackRuntime,
    val gossipDiscovery: MeshSweepGossipDiscovery?,
    /** GBC / control-plane publish surface for conference multicast media facts. */
    val conferenceSessionMediaControlRegistry: ConferenceSessionMediaControlFactRegistry,
    /** Publishes converged GBC declarations into [conferenceSessionMediaControlRegistry]. */
    val conferenceSessionMediaGbcPublisherBridge: ConferenceSessionMediaGbcPublisherBridge,
    /** Profile 01 verified wire facts → publisher bridge (no direct wiring). */
    val profile01ConferenceMediaFactIngress: Profile01ConferenceMediaFactIngress,
    /** Meeting control-plane CONFERENCE_SIGNED_FACT → Profile01 ingress (Phase A shadow). */
    val meetingProfile01FactWireIngress: MeetingProfile01FactWireIngress,
    /** sessionId ↔ conferenceId index for Meeting / Profile01 bridge. */
    val meetingProfile01SessionIndex: MeetingProfile01ConferenceSessionIndex,
    /** P1-A: establishment key lookup + local establishment identity (no package emit). */
    val profile01EstablishmentAuthority: Profile01EstablishmentAuthoritySurface,
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
        gbcTrustWiring: GbcTrustWiring? = null,
        onLog: ((String) -> Unit)? = null,
        /**
         * JVM multi-node tests share one process-global F8 registry instance; production leaves null.
         */
        selectedOperationalNetworkRegistry: SelectedOperationalNetworkRegistry? = null,
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
            gbcTrustWiring = gbcTrustWiring,
            onLog = onLog,
            selectedOperationalNetworkRegistry = selectedOperationalNetworkRegistry,
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
        gbcTrustWiring: GbcTrustWiring? = null,
        onLog: ((String) -> Unit)? = null,
        selectedOperationalNetworkRegistry: SelectedOperationalNetworkRegistry? = null,
    ): TalkbackRuntimeBundle {
        val endpointRegistry = EndpointRegistry(config.localModuleId)
        val helloTargetProvider = DiscoveryPrrHelloTargetProvider(config.localModuleId)
        val transportManager = SignalingTransportManager()
        val connectivity =
            context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val operationalNetworkRegistry =
            selectedOperationalNetworkRegistry ?: SelectedOperationalNetworkRegistry(connectivity)
        val socketBinder = AndroidSignalingSocketBinder()
        socketBinder.attachRegistry(operationalNetworkRegistry)
        WebRtcNetworkBridgeInstall.register(operationalNetworkRegistry)
        operationalNetworkRegistry.seedFromActiveNetwork()
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
        val networkObserver = NetworkCapabilityObserver(context, transportManager, operationalNetworkRegistry)
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
        val resolvedGbcTrustWiring =
            gbcTrustWiring ?: run {
                val trustDir = context.filesDir.resolve("gbc-trust").also { it.mkdirs() }
                val domain =
                    config.deploymentTrustDomainId.ifBlank {
                        GbcProductionTrustComposition.DEFAULT_DEPLOYMENT_TRUST_DOMAIN
                    }
                val trustRuntime =
                    GbcProductionTrustComposition.create(
                        deploymentTrustDomainId = domain,
                        anchorFile = trustDir.resolve("operational-trust-anchor.bin").toPath(),
                        trustStateFile = trustDir.resolve("accepted-local-trust-state.bin").toPath(),
                    )
                val originRuntime =
                    com.talkback.core.session.gbc.wiring.GbcOriginComposition.createProductionOriginRuntime(
                        trustDir = trustDir.toPath(),
                        localModuleId = config.localModuleId.value,
                        trustSnapshot = trustRuntime.trustStore.currentSnapshot(),
                    )
                GbcProductionTrustComposition.createProductionWiring(
                    deploymentTrustDomainId = domain,
                    anchorFile = trustDir.resolve("operational-trust-anchor.bin").toPath(),
                    trustStateFile = trustDir.resolve("accepted-local-trust-state.bin").toPath(),
                    originRuntime = originRuntime,
                )
            }
        lateinit var coordinator: TalkbackCoordinator
        val mediaRegistry = SessionMediaRegistry(
            context,
            mode == AudioEngineMode.STUB,
            onMeshIce = { scope, moduleId, state ->
                coordinator.onIceStateChanged(scope, moduleId, state)
            },
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
            gbcTrustWiring = resolvedGbcTrustWiring,
            onLog = onLog
        )
        transportManager.onLinkQualificationStateChanged { _, newState ->
            if (newState == LinkQualificationState.BIDIRECTIONAL_READY) {
                coordinator.onLinkQualificationStateChanged()
            }
        }
        if (peerEdgeSignalingReadiness != null) {
            peerEdgeSignalingReadiness.onPeerEdgeSignalingReady = { moduleId ->
                coordinator.onPeerEdgeSignalingReady(moduleId)
            }
            val prrLostHandler = peerEdgeSignalingReadiness.onPeerEdgeSignalingLost
            peerEdgeSignalingReadiness.onPeerEdgeSignalingLost = { event ->
                prrLostHandler?.invoke(event)
                coordinator.onPeerEdgeSignalingLost(event)
            }
        }
        coordinator.updateStaticPeers(staticPeers)
        ConferenceSessionMediaBridge.wiring = ConferenceSessionMediaWiring.forShadow(context)
        val conferenceSessionMediaControlRegistry = ConferenceSessionMediaControlFactRegistry()
        val conferenceSessionMediaGbcPublisherBridge =
            ConferenceSessionMediaGbcPublisherBridge(conferenceSessionMediaControlRegistry)
        val meetingProfile01SessionIndex = MeetingProfile01ConferenceSessionIndex()
        val profile01SupplementRegistry = Profile01SessionMediaSupplementRegistry()
        val profile01TrustStore =
            when (resolvedGbcTrustWiring) {
                is ProductionGbcTrustWiring -> resolvedGbcTrustWiring.runtime.trustStore
                else -> AcceptedLocalTrustStateStore()
            }
        val profile01EstablishmentAuthority =
            composeProfile01EstablishmentAuthority(
                context = context,
                config = config,
                gbcTrustWiring = resolvedGbcTrustWiring,
            )
        val profile01MediaKeyDecrypt =
            Profile01ProductionMediaKeyDecryptComposition.compose(profile01EstablishmentAuthority)
        val profile01ConferenceMediaFactIngress =
            Profile01ConferenceMediaFactIngress(
                validator =
                    Profile01ConferenceMediaFactValidator(
                        Profile01ProductionSignedFactTrustBoundary(
                            Profile01ProfileBackedModuleTrustLookup(profile01TrustStore),
                        ),
                    ),
                publisherBridge = conferenceSessionMediaGbcPublisherBridge,
                mediaKeyDecrypt = profile01MediaKeyDecrypt,
                supplementRegistry = profile01SupplementRegistry,
                onLog = { message -> onLog?.invoke(message) },
            )
        val profile01AwareFactPort =
            MeetingProfile01AwareConferenceSessionMediaFactPort(
                conferenceSessionMediaControlRegistry,
                meetingProfile01SessionIndex,
            )
        ConferenceSessionMediaCoordinatorDelegate.factPort = profile01AwareFactPort
        val profile01IncompleteApplyContinuation = MeetingProfile01IncompleteApplyContinuation()
        val profile01MediaKeyAuthority = Profile01ConferenceMediaKeyMaterialAuthority()
        val profile01SignerSource: Profile01SignedFactSignerSource =
            Profile01FieldEstablishedSignedFactSignerSource(
                trustDir = context.filesDir.resolve("gbc-trust"),
                trustStore = profile01TrustStore,
                localModuleId = config.localModuleId.value,
            )
        val profile01HostLocalSupplementMaterializer =
            Profile01HostLocalMediaSupplementMaterializer(
                sessionIndex = meetingProfile01SessionIndex,
                mediaKeyAuthority = profile01MediaKeyAuthority,
                supplementRegistry = profile01SupplementRegistry,
                onSupplementReady = { conferenceId, mediaKeyEpoch ->
                    profile01ConferenceMediaFactIngress.notifySupplementReady(conferenceId, mediaKeyEpoch)
                },
                onLog = { message -> onLog?.invoke(message) },
            )
        val profile01CreationOriginBridge =
            MeetingProfile01CreationOriginBridge(
                sessionIndex = meetingProfile01SessionIndex,
                mediaKeyAuthority = profile01MediaKeyAuthority,
                signerSource = profile01SignerSource,
                membershipConvergence = profile01ConferenceMediaFactIngress.membershipRegistry(),
                onHostCreationAuthoritativeCommit = { sessionId ->
                    coordinator.onMeetingProfile01HostCreationAuthoritativeCommit(sessionId)
                },
                hostLocalSupplementMaterializer = profile01HostLocalSupplementMaterializer,
                onLog = { message -> onLog?.invoke(message) },
            )
        coordinator.attachMeetingProfile01CreationOriginBridge(profile01CreationOriginBridge)
        val profile01MembershipOriginBridge =
            MeetingProfile01MembershipOriginBridge(
                sessionIndex = meetingProfile01SessionIndex,
                creationOriginBridge = profile01CreationOriginBridge,
                mediaKeyAuthority = profile01MediaKeyAuthority,
                membershipConvergence = profile01ConferenceMediaFactIngress.membershipRegistry(),
                signerSource = profile01SignerSource,
                hostLocalSupplementMaterializer = profile01HostLocalSupplementMaterializer,
                onHostMembershipAuthoritativeCommit = { sessionId ->
                    coordinator.onMeetingProfile01HostMembershipAuthoritativeCommit(sessionId)
                },
                onLog = { message -> onLog?.invoke(message) },
            )
        coordinator.attachMeetingProfile01MembershipOriginBridge(profile01MembershipOriginBridge)
        val profile01MediaKeyPackageOriginBridge =
            MeetingProfile01MediaKeyPackageOriginBridge(
                sessionIndex = meetingProfile01SessionIndex,
                creationOrigin = profile01CreationOriginBridge,
                mediaKeyAuthority = profile01MediaKeyAuthority,
                establishmentLookup = profile01EstablishmentAuthority.recipientEstablishmentKeyLookup,
                signerSource = profile01SignerSource,
                membershipConvergence = profile01ConferenceMediaFactIngress.membershipRegistry(),
                onLog = { message -> onLog?.invoke(message) },
            )
        coordinator.attachMeetingProfile01MediaKeyPackageOriginBridge(profile01MediaKeyPackageOriginBridge)
        val profile01LocalConferenceSourceIdentityAuthority = Profile01LocalConferenceSourceIdentityAuthority()
        val profile01SourceOriginBridge =
            MeetingProfile01SourceOriginBridge(
                sessionIndex = meetingProfile01SessionIndex,
                mediaKeyAuthority = profile01MediaKeyAuthority,
                supplementRegistry = profile01SupplementRegistry,
                membershipConvergence = profile01ConferenceMediaFactIngress.membershipRegistry(),
                signerSource = profile01SignerSource,
                onLog = { message -> onLog?.invoke(message) },
            )
        coordinator.attachMeetingProfile01SourceOriginBridge(
            profile01SourceOriginBridge,
            profile01LocalConferenceSourceIdentityAuthority,
        )
        val profile01ShadowMemberBindingMaterializer =
            Profile01ShadowMemberBindingMaterializer(
                factPort = profile01AwareFactPort,
                ingress = profile01ConferenceMediaFactIngress,
                localModuleId = { config.localModuleId.value },
                readSignedLocalSource = profile01SourceOriginBridge::readSignedSourceFact,
                retryLocalSourceBuild = { sessionId ->
                    coordinator.onMeetingProfile01CreationWireAppliedForLocalSource(sessionId)
                },
            )
        profile01SourceOriginBridge.memberBindingMaterializer = profile01ShadowMemberBindingMaterializer
        val meetingProfile01FactWireIngress =
            MeetingProfile01FactWireIngress(
                ingress = profile01ConferenceMediaFactIngress,
                supplementRegistry = profile01SupplementRegistry,
                sessionIndex = meetingProfile01SessionIndex,
                preBindRetention = MeetingProfile01PreBindFactRetention(),
                incompleteApplyContinuation = profile01IncompleteApplyContinuation,
                networkInterfaceName = { Slice4MulticastNetworkConstants.DEFAULT_IFACE },
                localModuleId = { config.localModuleId.value },
                localEstablishmentKeyVersion = profile01EstablishmentAuthority.localEstablishmentKeyVersion,
                memberBindingMaterializer = profile01ShadowMemberBindingMaterializer,
            )
        meetingProfile01FactWireIngress.onPeerSourceDeclarationWireApplied = { sessionId, remoteModuleId ->
            coordinator.onMeetingProfile01PeerSourceDeclarationWireApplied(sessionId, remoteModuleId)
        }
        coordinator.attachMeetingProfile01FactWire(
            meetingProfile01FactWireIngress,
            meetingProfile01SessionIndex,
        )
        val profile01HostLocalSessionFactProjection =
            Profile01HostLocalSessionFactProjection.fromOriginBridges(
                creationOriginBridge = profile01CreationOriginBridge,
                sourceOriginBridge = profile01SourceOriginBridge,
                ingress = profile01ConferenceMediaFactIngress,
                supplementRegistry = profile01SupplementRegistry,
                sessionIndex = meetingProfile01SessionIndex,
                registry = conferenceSessionMediaControlRegistry,
                networkInterfaceName = { Slice4MulticastNetworkConstants.DEFAULT_IFACE },
            )
        val profile01ShadowMulticastTransmitSeam =
            Profile01ShadowMulticastTransmitSeam(
                factPort = profile01AwareFactPort,
                registry = conferenceSessionMediaControlRegistry,
                sessionIndex = meetingProfile01SessionIndex,
                localSourceAuthority = profile01LocalConferenceSourceIdentityAuthority,
                hostProjection = profile01HostLocalSessionFactProjection,
                localModuleId = { config.localModuleId.value },
            )
        coordinator.attachProfile01ShadowTransmit(
            profile01HostLocalSessionFactProjection,
            profile01ShadowMulticastTransmitSeam,
        )
        val profile01ShadowMulticastReceiveSeam = Profile01ShadowMulticastReceiveSeam()
        val profile01ShadowPlayoutClockSeam = Profile01ShadowPlayoutClockSeam()
        com.talkback.core.conference.session.ConferenceSessionMediaCoordinatorDelegate
            .profile01ShadowReceiveSeam = profile01ShadowMulticastReceiveSeam
        com.talkback.core.conference.session.ConferenceSessionMediaCoordinatorDelegate
            .profile01ShadowPlayoutClockSeam = profile01ShadowPlayoutClockSeam
        com.talkback.core.conference.session.ConferenceSessionMediaCoordinatorDelegate
            .localModuleIdProvider = { config.localModuleId.value }
        ReplacementCutoverRc1.install(TalkbackCoordinatorAnchorAudiblePort(coordinator))
        profile01ConferenceMediaFactIngress.onMediaSupplementReady = { conferenceId, mediaKeyEpoch ->
            meetingProfile01FactWireIngress.onSupplementReadyForConference(conferenceId, mediaKeyEpoch)
            coordinator.onMeetingProfile01MediaSupplementReady(conferenceId, mediaKeyEpoch)
        }
        profile01ConferenceMediaFactIngress.onCreationMembershipEstablished = { conferenceId ->
            coordinator.onMeetingProfile01PeerCreationMembershipEstablished(conferenceId)
        }
        val runtime = TalkbackRuntime(
            config,
            coordinator,
            endpointRegistry,
            staticDiscovery,
            gossip,
            networkObserver
        )
        return TalkbackRuntimeBundle(
            runtime,
            gossip,
            conferenceSessionMediaControlRegistry,
            conferenceSessionMediaGbcPublisherBridge,
            profile01ConferenceMediaFactIngress,
            meetingProfile01FactWireIngress,
            meetingProfile01SessionIndex,
            profile01EstablishmentAuthority,
        )
    }

    private fun composeProfile01EstablishmentAuthority(
        context: Context,
        config: TalkbackRuntimeConfig,
        gbcTrustWiring: GbcTrustWiring,
    ): Profile01EstablishmentAuthoritySurface {
        val domain =
            config.deploymentTrustDomainId.ifBlank {
                GbcProductionTrustComposition.DEFAULT_DEPLOYMENT_TRUST_DOMAIN
            }
        return when (gbcTrustWiring) {
            is ProductionGbcTrustWiring -> {
                val trustDir = context.filesDir.resolve("gbc-trust").also { it.mkdirs() }
                EstablishmentProductionComposition.create(
                    deploymentTrustDomainId = domain,
                    anchorSource = gbcTrustWiring.runtime.anchorSource,
                    localModuleId = config.localModuleId.value,
                    establishmentStateFile =
                        trustDir.resolve("accepted-local-establishment-trust-state.bin").toPath(),
                ).authoritySurface
            }
            else ->
                EstablishmentProductionComposition.create(
                    deploymentTrustDomainId = domain,
                    anchorSource = OperationalTrustAnchorSource { null },
                    localModuleId = config.localModuleId.value,
                    establishmentStateFile = null,
                ).authoritySurface
        }
    }

}
