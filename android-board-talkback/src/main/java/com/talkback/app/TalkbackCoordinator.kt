package com.talkback.app

import com.talkback.core.audio.AudioRouter
import com.talkback.core.audio.DefaultAudioRouter
import com.talkback.core.audio.ModuleAudioMixer
import com.talkback.core.channel.ChannelManager
import com.talkback.core.channel.ChannelMembershipSnapshot
import com.talkback.core.grouphealth.GroupConvergenceTracker
import com.talkback.core.grouphealth.GroupRuntimeHealthInput
import com.talkback.core.grouphealth.GroupRuntimeHealthProjector
import com.talkback.core.grouphealth.GroupTopologyReadiness
import com.talkback.core.grouphealth.TopologySnapshotLogger
import com.talkback.core.grouphealth.TopologySnapshotReason
import com.talkback.core.presence.ModulePresenceSnapshot
import com.talkback.core.presence.PresenceProjector
import com.talkback.core.presence.SessionPresenceSnapshot
import com.talkback.core.contacts.CallableModuleGate
import com.talkback.core.contacts.ContactEndpointRow
import com.talkback.core.contacts.ContactsProjection
import com.talkback.core.discovery.CompositeModuleDiscoveryService
import com.talkback.core.discovery.DiscoverySignalHandler
import com.talkback.core.discovery.GossipDiscoveryControl
import com.talkback.core.discovery.ModuleDiscoveryService
import com.talkback.core.discovery.ModulePresence
import com.talkback.core.discovery.StaticPeerEntry
import java.util.concurrent.CopyOnWriteArrayList
import com.talkback.core.model.ConferenceJoinIntent
import com.talkback.core.model.ConferenceRejoinPayload
import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.EndpointId
import com.talkback.core.model.EndpointPriority
import com.talkback.core.model.FloorPayload
import com.talkback.core.model.FloorSnapshotDigest
import com.talkback.core.media.ConferenceRecoveryController
import com.talkback.core.media.MediaTopologyPolicy
import com.talkback.core.model.GroupSessionPayload
import com.talkback.core.model.MembershipSnapshot
import com.talkback.core.model.MeshSessionMode
import com.talkback.core.model.HelloPayload
import com.talkback.core.model.ModuleId
import com.talkback.core.model.RecoveryReattachRequest
import com.talkback.core.model.RemoteEndpointInfo
import com.talkback.core.model.SignalEnvelope
import com.talkback.core.model.SignalType
import com.talkback.core.ptt.FloorArbitrator
import com.talkback.core.ptt.FloorCommitResult
import com.talkback.core.ptt.FloorGrantCompletion
import com.talkback.core.ptt.FloorGrantResult
import com.talkback.core.ptt.FloorOperationIdentity
import com.talkback.core.ptt.FloorPriorityPolicy
import com.talkback.core.ptt.GroupFloorController
import com.talkback.core.ptt.SnapshotResult
import com.talkback.core.ptt.PttEvent
import com.talkback.core.ptt.PttState
import com.talkback.core.session.AnchorAuthority
import com.talkback.core.session.AuthorityMembershipMutationSource
import com.talkback.core.session.AnchorElection
import com.talkback.core.session.AnchorHealthSnapshot
import com.talkback.core.session.AnchorRanking
import com.talkback.core.session.ChannelMeshHostElection
import com.talkback.core.session.ChannelLifecycleEvent
import com.talkback.core.session.ChannelLifecyclePolicy
import com.talkback.core.session.ChannelMode
import com.talkback.core.session.ChannelModeFsm
import com.talkback.core.session.ChannelReadiness
import com.talkback.core.session.ConferenceEdgeRecoveryController
import com.talkback.core.session.ConferenceBootstrapDeferral
import com.talkback.core.session.ConferenceEdgeKey
import com.talkback.core.session.ConferenceHostEndpointResolver
import com.talkback.core.session.ConferenceIceConnectedSideEffects
import com.talkback.core.session.ConferenceBarrierDiagnostics
import com.talkback.core.session.ConferenceMediaTransmitGate
import com.talkback.core.session.ConferenceReceivePlaybackPolicy
import com.talkback.core.session.ConferenceParticipantManager
import com.talkback.core.session.EdgeReachabilitySnapshot
import com.talkback.core.session.EdgeRecoveryEligibility
import com.talkback.core.session.EdgeRecoveryFacts
import com.talkback.core.session.ObligationCloseReason
import com.talkback.core.session.ReattachDispatchOutcome
import com.talkback.core.session.RecoveryCapabilitySignature
import com.talkback.core.session.RecoveryReason
import com.talkback.core.session.RecoveryReevaluateTrigger
import com.talkback.core.session.RecoverySource
import com.talkback.core.session.projectRecoveryCapabilitySignature
import com.talkback.core.session.ConferenceParticipantProjector
import com.talkback.core.session.ConferenceRuntimeProjectionLogger
import com.talkback.core.session.ConferenceNetworkIndicator
import com.talkback.core.session.ConferenceNetworkIndicatorProjector
import com.talkback.core.session.ConferenceJoinLatencyTracker
import com.talkback.core.session.ConferencePresenceProjector
import com.talkback.core.session.ConferencePresenceProjection
import com.talkback.core.session.ConferenceMembershipLifecycle
import com.talkback.core.session.ConferenceMemberDecisionTrace
import com.talkback.core.session.ConferenceRejoinEligibility
import com.talkback.core.session.ConferenceRuntimeProjector
import com.talkback.core.session.ConferenceRuntimeState
import com.talkback.core.session.ConferenceSnapshot
import com.talkback.core.session.GroupMediaTopology
import com.talkback.core.session.GroupIdentityStability
import com.talkback.core.session.IdentityResolver
import com.talkback.core.session.GroupMemberReachability
import com.talkback.core.session.GroupMembershipSupport
import com.talkback.core.model.GroupResyncRequestPayload
import com.talkback.core.model.TopologyDigest
import com.talkback.core.session.GroupMeshReconciler
import com.talkback.core.session.GroupMeshPlanner
import com.talkback.core.session.GroupRoomId
import com.talkback.core.runtime.ForegroundActivityAdmission
import com.talkback.core.runtime.ModuleActivityStack
import com.talkback.core.runtime.SessionDispositionTransitions
import com.talkback.core.runtime.isForegroundActive
import com.talkback.core.runtime.isForegroundSuspended
import com.talkback.core.session.SessionDisposition
import com.talkback.core.session.DefaultLocalDeviceHealthProvider
import com.talkback.core.session.InviteState
import com.talkback.core.session.LocalDeviceHealthProvider
import com.talkback.core.session.MediaState
import com.talkback.core.session.MediaTopology
import com.talkback.core.session.MemberView
import com.talkback.core.session.MeshTopology
import com.talkback.core.session.ParticipantState
import com.talkback.core.session.ReachabilitySnapshot
import com.talkback.core.session.ReachabilityView
import com.talkback.core.qos.IceConnectivity
import com.talkback.core.qos.NetworkQualityMonitor
import com.talkback.core.registry.EndpointRegistry
import com.talkback.core.security.SignalSecurity
import com.talkback.core.session.SessionType
import com.talkback.core.session.isMeshSession
import com.talkback.core.session.usesFloorControl
import com.talkback.core.session.TalkbackSession
import com.talkback.core.session.UnicastCallPhase
import com.talkback.core.signaling.PeerTarget
import com.talkback.core.signaling.SignalingChannel
import com.talkback.core.sync.RemoteModuleState
import com.talkback.core.sync.StateSyncManager
import com.talkback.core.util.ConferenceAuditTimelineLog
import com.talkback.core.util.ConferenceRecoveryOwnershipLog
import com.talkback.core.util.MediaRecoveryCausalTrace
import com.talkback.core.util.FloorTrace
import com.talkback.core.util.GroupTransitionReadinessLog
import com.talkback.core.util.MeetingRecoveryLog
import com.talkback.core.util.PttTimingLog
import com.talkback.core.util.TalkbackLog
import com.talkback.app.governance.TalkbackChannelGovernanceHost
import com.talkback.governance.capability.CapabilityReadiness
import com.talkback.governance.coordinator.ChannelGovernanceRuntime
import com.talkback.governance.coordinator.GroupChannelSnapshot
import com.talkback.governance.coordinator.TopologyReadinessLabel
import com.talkback.governance.gate.GateDecision
import com.talkback.governance.gate.Operation
import com.talkback.governance.GovernanceObservabilityLog
import com.talkback.governance.transition.InviteDispatchError
import com.talkback.governance.transition.InviteDispatcher
import com.talkback.governance.transition.InviteDispatchOutcome
import com.talkback.governance.transition.InviteDispatchSendResult
import com.talkback.governance.transition.MeetingStartCompletion
import com.talkback.governance.transition.MeetingStartDeclaration
import com.talkback.governance.transition.MeetingStartDeclarationWindow
import com.talkback.governance.transition.MeetingMode
import com.talkback.governance.transition.PolicyConfigurationException
import com.talkback.governance.transition.TransitionPolicy
import com.talkback.governance.transition.TransitionRecord
import com.talkback.governance.transition.TransitionTerminalState
import com.talkback.governance.transition.TransitionTrigger
import com.talkback.core.webrtc.ConferenceAudioBus
import com.talkback.core.webrtc.ReceivePathLivenessObserver
import com.talkback.core.webrtc.MediaBearerScope
import com.talkback.core.webrtc.SessionMediaRegistry
import com.talkback.core.webrtc.ProgramAudioBus
import com.talkback.core.webrtc.WebRtcAudioEngine
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

data class TalkbackCoordinatorConfig(
    val autoAcceptIncoming: Boolean = true,
    val sessionIdleTimeoutMs: Long = 30_000L,
    val cleanupIntervalMs: Long = 5_000L,
    val heartbeatIntervalMs: Long = 2_000L,
    val helloIntervalMs: Long = 3_000L,
    val helloBootstrapIntervalMs: Long = 1_000L,
    val helloBootstrapDurationMs: Long = 30_000L,
    val autoReDialOnModuleRecovery: Boolean = true,
    val sharedSecret: String = "",
    val replayWindowMs: Long = 15_000L,
    val allowedModuleIds: Set<String> = emptySet(),
    val maxActiveSessions: Int = 1,
    val maxGroupModules: Int = MediaTopologyPolicy.DEFAULT_MAX_GROUP_MODULES,
    val maxConferenceModules: Int = MediaTopologyPolicy.DEFAULT_MAX_CONFERENCE_MODULES,
    val useStubWebRtc: Boolean = false,
    val iceReconnectEnabled: Boolean = true,
    /** UI liveness / reachability: requires a recent HELLO within this window. */
    val moduleStaleMs: Long = 15_000L,
    val floorRetryMs: Long = 400L,
    val iceNegotiationTimeoutMs: Long = 20_000L,
  /** Do not tear down mesh sessions while ICE is still negotiating within this window. */
    val meshNegotiationGraceMs: Long = 15_000L,
    /** Wait this long before ending a conference when the host ICE enters DISCONNECTED. */
    val conferenceHostIceReconnectGraceMs: Long = 15_000L,
    /** Grace before host prunes a disconnected conference participant. */
    val conferenceParticipantPruneGraceMs: Long = 15_000L,
    /** Per-edge recovery attempt watchdog budget (ADR-0022 R28-F). */
    val edgeRecoveryAttemptBudgetMs: Long = 15_000L,
    /**
     * Observation window after failed-media residency before obligation deadline close
     * (ADR-0022 R28-H). Must exceed soak premature-prune class (~4s); tests may shorten.
     */
    val edgeRecoveryObservationWindowMs: Long = 30_000L,
    /** UI timeout before showing reconnect-failed on a conference session. */
    val conferenceReconnectTimeoutMs: Long = 60_000L,
    /** When false, incoming Meeting (conference) invites are rejected until the user taps Join. */
    val autoAcceptConferenceInvites: Boolean = true,
    val discoveryPort: Int = com.talkback.core.discovery.MeshSweepGossipConfig.DEFAULT_DISCOVERY_PORT,
    val sweepMaxHosts: Int = 256,
    val discoveryPeerTtlMs: Long = 45_000L,
    val discoveryAnnounceIntervalMs: Long = 10_000L,
    /** Pending conference invite TTL before auto-expire on callee (ms). */
    val conferenceInvitePendingTtlMs: Long = 30_000L,
    /** Host-side ring timeout before marking invitee EXPIRED (ms). */
    val conferenceInviteRingTimeoutMs: Long = 20_000L,
    /** How long a voluntary leaver may silently rejoin the same host conference (ms). */
    val rejoinableConferenceTtlMs: Long = 30 * 60 * 1000L,
    /** Outgoing/incoming unicast ring/connect timeout before auto hangup (ms). */
    val unicastRingTimeoutMs: Long = 60_000L,
    /** Minimum anchor tenure before elective handover (ms). */
    val anchorTenureMs: Long = 30 * 60 * 1000L,
    /** Battery below this triggers anchor failover/handover. */
    val anchorLowBatteryPercent: Int = 10,
    /** GROUP: no recent HELLO before marking a roster member SUSPECT (default 3× hello interval). */
    val groupMemberSuspectHelloMs: Long = 9_000L,
    /** ADR-0004 interim acquire timeout (ms). Phase 3 enforces auto FLOOR_RELEASE. */
    val acquireReleaseTimeoutMs: Long = 500L,
    /** GROUP: ICE not connected before marking SUSPECT. */
    val groupMemberSuspectIceMs: Long = 10_000L,
    /** GROUP: SUSPECT duration before EVICTED (default 27s). */
    val groupMemberEvictSuspectMs: Long = 27_000L,
    /** BUILDING stall before PERIODIC_BUILDING snapshot (ADR-0008). */
    val buildingStallThresholdMs: Long = 10_000L,
    /** Minimum gap between PERIODIC_BUILDING snapshots per session. */
    val periodicBuildingWindowMs: Long = 30_000L,
    /** Throttle ICE_STATE_CHANGED topology snapshots per peer. */
    val iceTopologySnapshotThrottleMs: Long = 2_000L
)

private data class ReDialRecord(
    val local: EndpointAddress,
    val remote: EndpointAddress,
    val channelId: String? = null,
    val groupMembers: List<EndpointAddress>? = null,
    val meshSessionType: SessionType = SessionType.GROUP,
    val lastAttemptMs: Long = 0L
)

private data class PendingGroupJoin(
    val signal: SignalEnvelope,
    val fromPeer: PeerTarget
)

private data class PendingConferenceInvite(
    val signal: SignalEnvelope,
    val fromPeer: PeerTarget,
    val receivedAtMs: Long
)

private data class RejoinableConferenceRecord(
    val channelId: String,
    val hostSessionId: String,
    val hostModuleId: String,
    val hostKey: String,
    val leftAtMs: Long,
    val membershipEpoch: Long
) {
    fun isExpired(now: Long = System.currentTimeMillis(), ttlMs: Long): Boolean =
        now - leftAtMs > ttlMs
}

private sealed class UnicastOutgoingPrep {
    data object Ready : UnicastOutgoingPrep()
    data class Blocked(val reason: String) : UnicastOutgoingPrep()
}

class TalkbackCoordinator(
    private val discoveryService: ModuleDiscoveryService,
    private val signalingChannel: SignalingChannel,
    private val mediaRegistry: SessionMediaRegistry,
    private val localModuleId: ModuleId,
    private val endpointRegistry: EndpointRegistry,
    private val config: TalkbackCoordinatorConfig = TalkbackCoordinatorConfig(),
    private val channelManager: ChannelManager = ChannelManager(),
    private val stateSync: StateSyncManager = StateSyncManager(),
    private val audioRouter: AudioRouter = DefaultAudioRouter(),
    private val qosMonitor: NetworkQualityMonitor = NetworkQualityMonitor(),
    private val localDeviceHealth: LocalDeviceHealthProvider = DefaultLocalDeviceHealthProvider,
    private val moduleMixer: ModuleAudioMixer = ModuleAudioMixer(),
    private val onLog: ((String) -> Unit)? = null
) {
    private val receivePathLivenessObserver = ReceivePathLivenessObserver()
    private val programAudioBus = ProgramAudioBus(mediaRegistry::getGroup)
    private val conferenceAudioBus = ConferenceAudioBus(
        mediaRegistry::getGroup,
        onInboundPcm = { sessionId, sourceModuleId ->
            receivePathLivenessObserver.onInboundPcm(sessionId, sourceModuleId)
        }
    )
    private val conferenceRecoveryController: ConferenceRecoveryController by lazy {
        ConferenceRecoveryController(
            sessionManager = mediaRegistry.sessionManager,
            onIceRestart = { moduleId -> attemptConferenceIceRestartForRecovery(moduleId) },
            onAfterRecreate = { moduleId -> negotiateConferencePeerAfterRecreate(moduleId) }
        )
    }
    private val conferenceEdgeRecoveryController: ConferenceEdgeRecoveryController by lazy {
        ConferenceEdgeRecoveryController(
            localModuleId = localModuleId.value,
            scheduler = scheduler,
            onLog = { message -> log(message) },
            onRequestReattach = { sessionId, channelId, remoteModuleId ->
                var outcome = ReattachDispatchOutcome.DEFERRED
                runOnCoordinatorSync {
                    val session = sessions[sessionId] ?: return@runOnCoordinatorSync
                    val hostId = session.initiatorModuleId?.value ?: return@runOnCoordinatorSync
                    if (remoteModuleId != hostId || isConferenceHostSession(session)) return@runOnCoordinatorSync
                    val hostSessionId = resolveHostSessionIdForChannel(channelId, hostId) ?: return@runOnCoordinatorSync
                    val authority = resolveConferenceHostEndpoint(session, hostId)
                    outcome = dispatchRecoveryReattachOutcome(channelId, authority, hostSessionId)
                }
                outcome
            },
            onIceRestart = { sessionId, remoteModuleId ->
                if (onCoordinatorThread.get()) {
                    val session = sessions[sessionId]
                    if (session == null) {
                        false
                    } else {
                        val remote = meshRoster(session).firstOrNull { it.moduleId.value == remoteModuleId }
                            ?: resolveConferenceHostEndpoint(session, remoteModuleId)
                        attemptConferencePeerIceRestart(session, remoteModuleId, remote)
                    }
                } else {
                    // Recovery scheduler thread must not block on coordinatorExecutor.get()
                    // while coordinator may be waiting on inbound signal work.
                    coordinatorExecutor.execute {
                        if (stopped) return@execute
                        onCoordinatorThread.set(true)
                        try {
                            val session = sessions[sessionId] ?: return@execute
                            val remote = meshRoster(session).firstOrNull { it.moduleId.value == remoteModuleId }
                                ?: resolveConferenceHostEndpoint(session, remoteModuleId)
                            attemptConferencePeerIceRestart(session, remoteModuleId, remote)
                        } finally {
                            onCoordinatorThread.set(false)
                        }
                    }
                    true
                }
            },
            isIceConnected = { _, remoteModuleId ->
                qosMonitor.isGroupConnected(remoteModuleId)
            },
            canDispatchRecoveryMediaAction = { sessionId, remoteModuleId ->
                var ready = false
                runOnCoordinatorSync {
                    val session = sessions[sessionId] ?: return@runOnCoordinatorSync
                    val channelId = session.channelId ?: return@runOnCoordinatorSync
                    ready = buildRecoveryEdgeReachabilitySnapshot(channelId, session, remoteModuleId)
                        .canDispatchRecoverySignal()
                }
                ready
            },
            onRecoveryStateChanged = { sessionId ->
                runOnCoordinatorSync {
                    val session = sessions[sessionId] ?: return@runOnCoordinatorSync
                    applyConferenceTransmitBarrier(session, "recovery_state_changed")
                    if (session.type == SessionType.CONFERENCE && session.accepted) {
                        refreshConferenceReceivePlayback(session, "recovery_state_changed")
                    }
                    emitConferenceRuntimeProjection(session)
                }
            },
            onAttemptLineageObservation = { sessionId, remoteModuleId, trigger, supersededFromAttempt ->
                runOnCoordinatorSync {
                    val session = sessions[sessionId] ?: return@runOnCoordinatorSync
                    emitConferenceRecoveryOwnership(
                        reason = trigger,
                        session = session,
                        participantId = remoteModuleId,
                        supersededFromAttempt = supersededFromAttempt
                    )
                }
            },
            attemptBudgetMs = config.edgeRecoveryAttemptBudgetMs,
            observationWindowMs = config.edgeRecoveryObservationWindowMs
        )
    }
    private val groupMeshReconciler = GroupMeshReconciler()
    private val conferenceParticipantManager = ConferenceParticipantManager()
    private val conferenceJoinLatencyTracker = ConferenceJoinLatencyTracker()
    private val reachabilityView: ReachabilityView = object : ReachabilityView {
        override fun snapshot(sessionId: String): ReachabilitySnapshot {
            val session = sessions[sessionId] ?: return ReachabilitySnapshot.EMPTY
            val online = linkedSetOf<ModuleId>()
            val suspect = linkedSetOf<ModuleId>()
            val evicted = linkedSetOf<ModuleId>()
            GroupMembershipSupport.canonicalMemberModuleIds(session).forEach { moduleId ->
                when (GroupMembershipSupport.membershipState(session, moduleId.value)) {
                    GroupMemberReachability.ONLINE -> online.add(moduleId)
                    GroupMemberReachability.SUSPECT -> suspect.add(moduleId)
                    GroupMemberReachability.EVICTED -> evicted.add(moduleId)
                }
            }
            return ReachabilitySnapshot(online, suspect, evicted)
        }
    }
    private val remoteHealthByModule = ConcurrentHashMap<String, AnchorHealthSnapshot>()
    private val lastAuthorityReachableBySession = ConcurrentHashMap<String, Boolean>()
    /** Per-edge last [RecoveryCapabilitySignature] for materiality detection (ADR-0022 R28-G). */
    private val lastRecoveryCapabilityByEdge = ConcurrentHashMap<ConferenceEdgeKey, RecoveryCapabilitySignature>()
    /** Dedup key for [CONFERENCE_RUNTIME_DECISION] (Issue2 probe). */
    private val lastConferenceRuntimeDecisionBySession = ConcurrentHashMap<String, String>()
    /** Dedup key for [CONFERENCE_RUNTIME_MISSING] (Gate-R1-B). */
    private val lastConferenceRuntimeMissingByPeer = ConcurrentHashMap<String, String>()
    @Volatile
    private var invariantF1BreakCount = 0

    private val channelGovernance: ChannelGovernanceRuntime by lazy {
        GovernanceObservabilityLog.transitionTerminalObserver = { record ->
            runOnCoordinator { onGovernanceTransitionTerminal(record) }
        }
        ChannelGovernanceRuntime(TalkbackChannelGovernanceHost(this))
    }

    private fun groupLocalIdentity(session: TalkbackSession): EndpointAddress =
        IdentityResolver.local(session, localModuleId.value)

    private fun isLocalIdentity(session: TalkbackSession, endpoint: EndpointAddress): Boolean =
        IdentityResolver.isLocal(session, endpoint, localModuleId.value)

    private fun isLocalFloorHolder(session: TalkbackSession): Boolean {
        val owner = session.floor.owner() ?: return false
        return isLocalIdentity(session, owner)
    }

    private fun releaseLocalFloorIfHeld(session: TalkbackSession): Boolean {
        val owner = session.floor.owner() ?: return false
        if (!isLocalIdentity(session, owner)) return false
        return session.floor.release(owner)
    }

    private fun meshEngineFor(session: TalkbackSession, moduleId: String): WebRtcAudioEngine =
        when (session.type) {
            SessionType.CONFERENCE -> mediaRegistry.conferenceEngine(moduleId)
            else -> mediaRegistry.groupEngine(moduleId)
        }

    private fun isConferenceSession(session: TalkbackSession): Boolean =
        session.type == SessionType.CONFERENCE

    private fun meshRoster(session: TalkbackSession): List<EndpointAddress> =
        if (isConferenceSession(session)) {
            conferenceParticipantManager.roster(session.id)
        } else {
            session.groupMembers
        }

    private fun resolveConferenceHostEndpoint(session: TalkbackSession, hostModuleId: String): EndpointAddress =
        ConferenceHostEndpointResolver.resolve(
            roster = meshRoster(session),
            remote = session.remote,
            hostModuleId = hostModuleId,
            fallbackEndpointId = session.local.endpointId,
        )

    private fun meshParticipant(session: TalkbackSession, moduleId: String): ParticipantState =
        if (isConferenceSession(session)) {
            conferenceParticipantManager.participant(session.id, moduleId)
        } else {
            session.participant(moduleId)
        }

    private fun markMeshLinkCompleted(session: TalkbackSession, moduleId: String) {
        session.meshCompletedModules.add(moduleId)
        // everConnected + media CONNECTED are owned by ICE CONNECTED (ADR-0025 R30-H).
    }

    private fun conferenceSnapshot(session: TalkbackSession): ConferenceSnapshot =
        conferenceParticipantManager.snapshot(session.id, localModuleId)

    private fun initConferenceParticipantState(
        session: TalkbackSession,
        members: List<EndpointAddress>
    ) {
        conferenceParticipantManager.initSession(session.id, session.local, members)
    }

    private fun disposeConferenceParticipantState(sessionId: String) {
        conferenceParticipantManager.removeSession(sessionId)
        conferenceJoinLatencyTracker.removeSession(sessionId)
    }

    private fun getMeshEngine(moduleId: String): WebRtcAudioEngine? = mediaRegistry.getGroup(moduleId)

    private fun meshEngineForSession(session: TalkbackSession, moduleId: String): WebRtcAudioEngine? =
        when (session.type) {
            SessionType.CONFERENCE -> mediaRegistry.getConference(moduleId)
            else -> mediaRegistry.getGroup(moduleId)
        }

    private fun mediaBearerScopeFor(session: TalkbackSession): MediaBearerScope =
        when (session.type) {
            SessionType.CONFERENCE -> MediaBearerScope.CONFERENCE
            SessionType.UNICAST -> MediaBearerScope.UNICAST
            else -> MediaBearerScope.GROUP
        }

    private fun mediaRecoveryTraceContext(
        session: TalkbackSession,
        remoteModuleId: String,
        remoteEndpointId: String? = null,
        iceRestart: Boolean = false
    ): MediaRecoveryCausalTrace.Context {
        val mediaState = mediaRegistry.meshSessionState(remoteModuleId)
        val generation = mediaState?.generation
        val conferenceGen = if (session.type == SessionType.CONFERENCE) session.rosterEpoch else null
        val attemptId = if (session.type == SessionType.CONFERENCE) {
            conferenceEdgeRecoveryController.attemptLineageObservation(session.id, remoteModuleId)?.attemptId
        } else {
            null
        }
        return MediaRecoveryCausalTrace.Context(
            sessionId = session.id,
            sessionTraceId = session.traceId,
            scope = mediaState?.scope ?: mediaBearerScopeFor(session),
            remoteModuleId = remoteModuleId,
            remoteEndpointId = remoteEndpointId,
            recoveryAttemptId = attemptId,
            conferenceGeneration = conferenceGen,
            pcGeneration = generation,
            transportGeneration = generation,
            iceRestart = iceRestart
        )
    }

    private fun getOrCreateMeshEngine(session: TalkbackSession, moduleId: String): WebRtcAudioEngine {
        val existing = meshEngineForSession(session, moduleId)
        if (existing != null) return existing
        if (isConferenceSession(session)) {
            conferenceJoinLatencyTracker.onPeerConnectionCreated(session.id, moduleId)
        }
        return meshEngineFor(session, moduleId)
    }

    /**
     * Conference / fresh mesh invites must not inherit a wedged GROUP peer connection.
     * Reconnect paths may reuse a live CONNECTED link or ICE-restart the existing PC.
     */
    private fun acquireMeshEngine(
        session: TalkbackSession,
        moduleId: String,
        forReconnect: Boolean
    ): WebRtcAudioEngine {
        val existing = meshEngineForSession(session, moduleId)
        if (forReconnect && existing != null) {
            val ice = qosMonitor.snapshot(moduleId)?.iceState
            if (IceConnectivity.isConnected(ice)) {
                return existing
            }
        }
        if (existing != null) {
            qosMonitor.resetGroup(moduleId)
            session.meshCompletedModules.remove(moduleId)
        }
        if (isConferenceSession(session)) {
            conferenceJoinLatencyTracker.onPeerConnectionCreated(session.id, moduleId)
        }
        return meshEngineFor(session, moduleId)
    }

    private fun unicastEngine(session: TalkbackSession): WebRtcAudioEngine =
        mediaRegistry.unicastEngine(session.id)

    private val meshRetryScheduledSessionIds = ConcurrentHashMap.newKeySet<String>()
    @Volatile
    private var autoAcceptConferenceInvites: Boolean = config.autoAcceptConferenceInvites
    /** UI overlay: prefer conference on a channel (legacy global fallback when channelId omitted). */
    @Volatile
    private var meetingPreferred: Boolean = false
    private val uiMeetingPreferredChannels = ConcurrentHashMap<String, Boolean>()
    private val channelModeByChannel = ConcurrentHashMap<String, ChannelModeFsm>()

    private val floorArbitrator = FloorArbitrator()
    private val pendingGroupPttRecoveryByChannel = ConcurrentHashMap<String, Boolean>()
    private val sessions = ConcurrentHashMap<String, TalkbackSession>()
    private val discoveredByModule = ConcurrentHashMap<String, PeerTarget>()
    /** Modules verified via gossip or signed HELLO — Callable Roster gate (ADR-0005). */
    private val callableModuleGate = CallableModuleGate()
    /** UDP return path learned from inbound signals; must survive partial mDNS refresh. */
    private val signalPeersByModule = ConcurrentHashMap<String, PeerTarget>()
    /** Last verified signal or HELLO from each remote module (not refreshed on UI reads). */
    /** Last application HELLO received from a remote module (liveness only; not discovery). */
    private val moduleLastHelloMs = ConcurrentHashMap<String, Long>()
    /** Primary endpoint id learned from HELLO; kept after module goes offline. */
    private val lastKnownPrimaryEndpointByModule = ConcurrentHashMap<String, String>()
    private val staticPeers = CopyOnWriteArrayList<StaticPeerEntry>()
    private val reDialByRemoteModule = ConcurrentHashMap<String, ReDialRecord>()
    private val pendingGroupJoinsBySession = ConcurrentHashMap<String, MutableList<PendingGroupJoin>>()
    private val pendingConferenceInvitesByChannel = ConcurrentHashMap<String, PendingConferenceInvite>()
    /** Participant-side memory after voluntary conference leave (stable room rejoin). */
    private val lastRejoinableConferenceByChannel = ConcurrentHashMap<String, RejoinableConferenceRecord>()
    /** Host session id we are trying to re-enter (suppress first-time invite UX). */
    private val pendingRejoinByChannel = ConcurrentHashMap<String, String>()
    private val conferenceRejoinStartedAtByChannel = ConcurrentHashMap<String, Long>()
    private val pendingIceBySession = ConcurrentHashMap<String, ConcurrentHashMap<String, MutableList<String>>>()
    private val pendingConferenceHostIceHangupBySession = ConcurrentHashMap<String, ScheduledFuture<*>>()
    private val pendingParticipantPruneBySession = ConcurrentHashMap<String, ConcurrentHashMap<String, ScheduledFuture<*>>>()
    private val pendingHostRejoinByChannel = ConcurrentHashMap<String, ScheduledFuture<*>>()
    private val acquireReleaseWatchdog by lazy {
        FloorAcquireReleaseWatchdog(
            timeoutMs = { config.acquireReleaseTimeoutMs },
            scheduler = scheduler,
            onTimeout = { sessionId -> runOnCoordinator { onAcquireReleaseTimeout(sessionId) } }
        )
    }
    private val activityStack = ModuleActivityStack()
    private val foregroundAdmission by lazy {
        ForegroundActivityAdmission(
            stack = activityStack,
            maxActiveSessions = { config.maxActiveSessions },
            foregroundActiveCount = { activeSessionCount() }
        )
    }
    private val hostRejoinAttemptByChannel = ConcurrentHashMap<String, Int>()
    private val conferenceReconnectStartedAtBySession = ConcurrentHashMap<String, Long>()
    private val lastConferenceBarrierCanPublishBySession = ConcurrentHashMap<String, Boolean>()
    private val lastGroupMeshReconnectMsByPeer = ConcurrentHashMap<String, Long>()
    private val lastSeenAuthorityDigestByChannel = ConcurrentHashMap<String, TopologyDigest>()
    /** Last HELLO floor snapshot from the session floor authority (diagnostics only). */
    private val lastSeenAuthorityFloorSnapshotByChannel = ConcurrentHashMap<String, FloorSnapshotDigest>()
    private val lastGroupTopologyReadinessBySession = ConcurrentHashMap<String, GroupTopologyReadiness>()
    private val lastConvergenceAnchorMsBySession = ConcurrentHashMap<String, Long>()
    private val lastPeriodicBuildingMsBySession = ConcurrentHashMap<String, Long>()
    private val lastIceTopologySnapshotMsByPeer = ConcurrentHashMap<String, Long>()
    private val groupAppStartSnapshotEmitted = ConcurrentHashMap.newKeySet<String>()
    private val suppressMeshRepairUntilMsByChannel = ConcurrentHashMap<String, Long>()
    private val lastPlaybackEnabledBySession = ConcurrentHashMap<String, Boolean>()
    private val lastEnsureCanonicalInviteMsByModule = ConcurrentHashMap<String, Long>()
    private val seenNoncesByModule = ConcurrentHashMap<String, MutableMap<String, Long>>()
    private val pendingMeetingStartIntentByChannel =
        ConcurrentHashMap<String, Pair<MeetingMode, Set<EndpointId>>>()
    private val meetingStartDeclarationByChannel =
        ConcurrentHashMap<String, MeetingStartDeclarationWindow>()
    private val coordinatorExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "talkback-coordinator")
    }
    private val onCoordinatorThread = ThreadLocal.withInitial { false }
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "talkback-scheduler")
    }
    @Volatile
    private var stopped = false
    private var coordinatorBootstrapStartedAtMs: Long = 0L

    fun setAutoAcceptConferenceInvites(enabled: Boolean) {
        autoAcceptConferenceInvites = enabled
    }

    fun setMeetingPreferred(preferred: Boolean, channelId: String? = null) {
        if (channelId != null) {
            if (preferred) {
                uiMeetingPreferredChannels[channelId] = true
            } else {
                val wasPreferred = uiMeetingPreferredChannels.remove(channelId) == true
                runOnCoordinator {
                    releaseChannelModeIfIdle(channelId)
                    if (wasPreferred) {
                        onChannelLifecycleEvent(channelId, ChannelLifecycleEvent.MeetingTabReleased)
                    }
                }
            }
        } else {
            meetingPreferred = preferred
        }
    }

    fun channelMode(channelId: String): ChannelMode =
        runOnCoordinatorSync { channelModeFsm(channelId).mode }

    private fun channelModeFsm(channelId: String): ChannelModeFsm =
        channelModeByChannel.getOrPut(channelId) { ChannelModeFsm(channelId) }

    private fun channelGateState(channelId: String): ChannelLifecyclePolicy.ChannelGateState =
        ChannelLifecyclePolicy.ChannelGateState(
            channelMode = channelModeFsm(channelId).mode,
            pendingConferenceInvite = pendingConferenceInvitesByChannel.containsKey(channelId),
            meetingPreferredOnChannel = uiMeetingPreferredChannels[channelId] == true || meetingPreferred
        )

    private fun blocksGroupOnChannel(channelId: String): Boolean =
        ChannelLifecyclePolicy.blocksNewGroupMesh(channelGateState(channelId))

    private fun logGroupInviteBlocked(channelId: String, joinPath: String = "invite") {
        val gate = channelGateState(channelId)
        log(
            "Rejecting GROUP_$joinPath ch=$channelId " +
                "mode=${gate.channelMode} pending=${gate.pendingConferenceInvite} " +
                "preferred=${gate.meetingPreferredOnChannel}"
        )
    }

    /**
     * Explicit CONFERENCE teardown: return channel to IDLE and schedule GROUP mesh reconcile.
     * All participants should run this when the last conference session on the channel ends.
     */
    private fun releaseConferenceChannelForGroupPtt(channelId: String, reason: String) {
        pendingConferenceInvitesByChannel.remove(channelId)
        clearConferenceRejoinState(channelId)
        uiMeetingPreferredChannels.remove(channelId)
        cancelHostRejoinRetry(channelId)
        channelModeFsm(channelId).reset()
        log("Conference channel released for GROUP PTT ch=$channelId reason=$reason")
        MeetingRecoveryLog.onConferenceReleased(channelId, reason)
        channelGovernance.beginTransition(TransitionTrigger.MEETING_END, channelId)
        val meetingEndBaseline = (dialableRemoteModuleIds().map { it.value } + localModuleId.value)
            .distinct()
            .sorted()
        val meetingEndSnapshot = buildGroupTransitionReadinessSnapshot(
            channelId = channelId,
            meshRecoveryState = "conference_released"
        )
        GroupTransitionReadinessLog.onMeetingEndBegin(
            channelId = channelId,
            moduleId = localModuleId.value,
            reason = reason,
            membershipBaseline = meetingEndBaseline,
            snapshot = meetingEndSnapshot
        )
        onChannelLifecycleEvent(channelId, ChannelLifecycleEvent.ConferenceEnded)
        emitGroupTopologyForChannel(TopologySnapshotReason.CONFERENCE_END, channelId)
    }

    /**
     * Remote/lifecycle terminal event for a conference channel.
     *
     * Must always clear rejoin hints (zombie rejoin guard), and only tear down channel runtime when
     * no active conference session remains.
     */
    private fun releaseConferenceRuntimeAfterRemoteTermination(channelId: String, reason: String) {
        conferenceEdgeRecoveryController.cancelChannel(channelId, reason)
        clearConferenceRejoinState(channelId)
        ConferenceAuditTimelineLog.lifecycle(
            event = "REMOTE_TERMINATION",
            channelId = channelId,
            sessionId = null,
            writer = "releaseConferenceRuntimeAfterRemoteTermination",
            cause = reason
        )
        log("CONFERENCE_TERMINATED ch=$channelId reason=$reason clearRejoinState=true")
        if (sessions.values.any { it.channelId == channelId && it.type == SessionType.CONFERENCE }) {
            return
        }
        releaseConferenceChannelForGroupPtt(channelId, reason)
    }

    private fun notifySoftLeftParticipantsMeetingEnded(session: TalkbackSession) {
        if (session.type != SessionType.CONFERENCE) return
        val leftEndpoints = linkedMapOf<String, EndpointAddress>()
        conferenceParticipantManager.leftMemberEndpoints(session.id)?.let { leftEndpoints.putAll(it) }
        session.leftMemberEndpoints.forEach { (moduleId, endpoint) ->
            leftEndpoints.putIfAbsent(moduleId, endpoint)
        }
        if (leftEndpoints.isEmpty()) return
        var notified = 0
        leftEndpoints.forEach { (moduleId, endpoint) ->
            if (moduleId == localModuleId.value) return@forEach
            val peer = session.remotePeersByModule[moduleId] ?: discoveredByModule[moduleId] ?: return@forEach
            sendSignal(
                peer,
                buildSignedEnvelope(
                    SignalType.CALL_REJECT,
                    session.local,
                    endpoint,
                    session.id,
                    "MEETING_ENDED"
                )
            )
            notified++
        }
        if (notified > 0) {
            log("[${session.traceId}] Notified $notified soft-left participant(s) of meeting end")
        }
    }

    private fun onChannelLifecycleEvent(channelId: String, event: ChannelLifecycleEvent) {
        when (event) {
            ChannelLifecycleEvent.PeersDiscovered -> maybeBootstrapGroupOnPeerDiscovery(channelId)
            else -> {
                if (!ChannelLifecyclePolicy.shouldScheduleGroupRecovery(event)) return
                scheduleGroupPttRecovery(channelId)
            }
        }
    }

    private var lastDialablePeerCount = 0

    private fun maybeNotifyDialablePeersChanged() {
        val dialable = countDialableRemoteModules()
        if (dialable <= lastDialablePeerCount) return
        lastDialablePeerCount = dialable
        val channelIds = linkedSetOf<String>()
        channelIds += channelManager.all().map { it.channelId }
        channelIds += channelModeByChannel.keys
        channelIds += sessions.values.mapNotNull { it.channelId }
        channelIds.forEach { onChannelLifecycleEvent(it, ChannelLifecycleEvent.PeersDiscovered) }
        notifyConferenceRecoveryReachabilityOnLinkOrDiscovery(RecoveryReevaluateTrigger.PEER_DISCOVERED)
    }

    private fun maybeBootstrapGroupOnPeerDiscovery(channelId: String) {
        if (blocksGroupOnChannel(channelId)) return
        if (isMeshRepairSuppressed(channelId)) return
        if (sessions.values.any { it.channelId == channelId }) return
        val dialable = dialableRemoteModuleIds()
        if (dialable.isEmpty()) return
        val primary = resolveBootstrapPrimary(dialable + localModuleId) ?: return
        if (localModuleId != primary) return
        reconcileGroupMeshInternal(channelId)
    }

    private fun scheduleGroupPttRecovery(channelId: String) {
        if (pendingGroupPttRecoveryByChannel.putIfAbsent(channelId, true) != null) return
        scheduler.schedule({
            runOnCoordinator {
                pendingGroupPttRecoveryByChannel.remove(channelId)
                if (sessions.values.any { it.channelId == channelId && it.type == SessionType.CONFERENCE }) {
                    return@runOnCoordinator
                }
                endStaleConferenceBlockingGroup(channelId)
                reconcileGroupMeshInternal(channelId)
            }
        }, GROUP_PTT_RECOVERY_DELAY_MS, TimeUnit.MILLISECONDS)
    }

    private fun enterChannelMode(channelId: String, mode: ChannelMode, ownerModuleId: String) {
        channelModeFsm(channelId).requestMode(mode, ownerModuleId)
    }

    private fun leaveChannelMode(channelId: String) {
        channelModeFsm(channelId).requestMode(ChannelMode.IDLE, localModuleId.value)
        uiMeetingPreferredChannels.remove(channelId)
    }

    /** Drop stale CONFERENCE channel mode when no session or pending invite remains. */
    private fun releaseChannelModeIfIdle(channelId: String) {
        if (sessions.values.any { it.channelId == channelId }) return
        if (pendingConferenceInvitesByChannel.containsKey(channelId)) return
        if (channelModeFsm(channelId).mode != ChannelMode.IDLE) {
            leaveChannelMode(channelId)
        }
    }

    fun pendingConferenceInvite(channelId: String? = null): ConferenceInviteSnapshot? =
        runOnCoordinatorSync {
            val pending = when {
                channelId != null -> pendingConferenceInvitesByChannel[channelId]
                else -> pendingConferenceInvitesByChannel.values.firstOrNull()
            } ?: return@runOnCoordinatorSync null
            toConferenceInviteSnapshot(pending)
        }

    fun rejoinableConference(channelId: String): RejoinableConferenceSnapshot? = runOnCoordinatorSync {
        val record = lastRejoinableConferenceByChannel[channelId] ?: return@runOnCoordinatorSync null
        if (record.isExpired(ttlMs = config.rejoinableConferenceTtlMs)) {
            clearConferenceRejoinState(channelId)
            return@runOnCoordinatorSync null
        }
        RejoinableConferenceSnapshot(
            hostSessionId = record.hostSessionId,
            channelId = record.channelId,
            hostModuleId = record.hostModuleId,
            hostKey = record.hostKey,
            leftAtMs = record.leftAtMs,
            membershipEpoch = record.membershipEpoch
        )
    }

    fun isConferenceRejoinInProgress(channelId: String): Boolean = runOnCoordinatorSync {
        pendingRejoinByChannel.containsKey(channelId) ||
            conferenceRejoinStartedAtByChannel[channelId]?.let {
                System.currentTimeMillis() - it < config.conferenceReconnectTimeoutMs
            } == true
    }

    fun isConferenceReconnecting(channelId: String): Boolean = runOnCoordinatorSync {
        val session = meshSessionForChannel(channelId) ?: return@runOnCoordinatorSync false
        if (session.type != SessionType.CONFERENCE || !session.accepted) return@runOnCoordinatorSync false
        if (isConferenceUiReady(session)) return@runOnCoordinatorSync false
        conferenceReconnectStartedAtBySession.containsKey(session.id) ||
            conferenceParticipantManager.participants(session.id)
                .any { it.media == MediaState.RECONNECTING } ||
            pendingHostRejoinByChannel.containsKey(channelId)
    }

    fun isConferenceReconnectFailed(channelId: String): Boolean = runOnCoordinatorSync {
        val session = meshSessionForChannel(channelId) ?: return@runOnCoordinatorSync false
        if (session.type != SessionType.CONFERENCE || !session.accepted) return@runOnCoordinatorSync false
        if (isConferenceUiReady(session)) return@runOnCoordinatorSync false
        val startedAt = conferenceReconnectStartedAtBySession[session.id]
            ?: conferenceRejoinStartedAtByChannel[channelId]
            ?: return@runOnCoordinatorSync false
        System.currentTimeMillis() - startedAt >= config.conferenceReconnectTimeoutMs
    }

    fun sendConferenceRejoin(
        channelId: String,
        authority: EndpointAddress,
        hostSessionId: String
    ): Boolean = runOnCoordinatorSync {
        sendConferenceRejoinIntentInternal(channelId, authority, hostSessionId)
    }

    fun acceptPendingConferenceInvite(channelId: String): Boolean = runOnCoordinatorSync {
        val pending = pendingConferenceInvitesByChannel[channelId]
        if (pending == null) {
            log("Conference accept skipped: no pending invite on $channelId")
            return@runOnCoordinatorSync false
        }
        if (!canAcceptGroupInvite(pending.signal, pending.fromPeer)) {
            log("Conference accept deferred: invite not yet actionable ch=$channelId")
            return@runOnCoordinatorSync false
        }
        sessions.values
            .filter {
                it.channelId == channelId &&
                    it.id != pending.signal.sessionId &&
                    it.type == SessionType.CONFERENCE &&
                    it.initiatorModuleId == localModuleId
            }
            .toList()
            .forEach {
                log("[${it.traceId}] Yielding local solo conference to host invite on $channelId")
                hangupInternal(it.id)
            }
        if (!prepareForGroupInvite(pending.signal, channelId, pending.signal.from.moduleId.value)) {
            log("Conference accept blocked after yield ch=$channelId")
            return@runOnCoordinatorSync false
        }
        if (!acceptGroupInvite(pending.signal, pending.fromPeer)) {
            log("Conference accept failed ch=$channelId")
            return@runOnCoordinatorSync false
        }
        pendingConferenceInvitesByChannel.remove(channelId)
        true
    }

    fun rejectPendingConferenceInvite(channelId: String, reason: String = "DECLINED"): Boolean =
        runOnCoordinatorSync {
            val pending = pendingConferenceInvitesByChannel.remove(channelId)
                ?: return@runOnCoordinatorSync false
            val signal = pending.signal
            val caller = signal.from
            val callee = signal.to ?: return@runOnCoordinatorSync false
            sendSignal(
                pending.fromPeer,
                buildSignedEnvelope(SignalType.CALL_REJECT, callee, caller, signal.sessionId, reason)
            )
            if (sessions.values.none { it.channelId == channelId && it.type == SessionType.CONFERENCE }) {
                leaveChannelMode(channelId)
            }
            log("Conference invite declined reason=$reason")
            true
        }

    fun start(localSignalingPort: Int) {
        stopped = false
        coordinatorBootstrapStartedAtMs = System.currentTimeMillis()
        log("Floor acquire timeout config: acquireReleaseTimeoutMs=${config.acquireReleaseTimeoutMs} (enforced)")
        lastDialablePeerCount = 0
        signalingChannel.start(localSignalingPort)
        signalingChannel.onMessage { signal, peer -> runOnCoordinator { handleSignal(signal, peer) } }
        discoveryService.start(localModuleId, localSignalingPort)
        discoveryService.onPresenceChanged { modules ->
            runOnCoordinator {
                val incomingKeys = modules.map { it.moduleId.value }.toSet()
                modules.forEach {
                    discoveredByModule[it.moduleId.value] = PeerTarget(it.host, it.port)
                    applyDiscoveryCallableGate(it.moduleId.value)
                }
                // Never clear() — Huawei mDNS often publishes a subset and would drop HELLO-learned peers.
                discoveredByModule.keys.toList().forEach { key ->
                    if (key !in incomingKeys && !signalPeersByModule.containsKey(key)) {
                        discoveredByModule.remove(key)
                    }
                }
                stateSync.updatePresence(modules)
                modules.forEach { presence ->
                    val moduleId = presence.moduleId.value
                    if (moduleId != localModuleId.value) {
                        tryReinviteGroupPeerPairwise(moduleId)
                    }
                }
                sessions.values
                    .mapNotNull { it.channelId }
                    .distinct()
                    .forEach { reconcileGroupMeshInternal(it) }
                retryIncompleteGroupMesh()
                maybeNotifyDialablePeersChanged()
            }
        }
        scheduler.scheduleAtFixedRate(
            {
                runOnCoordinator {
                    channelGovernance.transitionCoordinator.expireTimeouts()
                        .forEach(GovernanceObservabilityLog::transitionTerminal)
                    cleanupExpiredSessions()
                    maybeEmitPeriodicBuildingSnapshots()
                }
            },
            config.cleanupIntervalMs,
            config.cleanupIntervalMs,
            TimeUnit.MILLISECONDS
        )
        scheduler.scheduleAtFixedRate(
            { runOnCoordinator { refreshGroupReceivePlaybackAll() } },
            config.cleanupIntervalMs,
            config.cleanupIntervalMs,
            TimeUnit.MILLISECONDS
        )
        scheduler.scheduleAtFixedRate(
            { runOnCoordinator { sendSessionHeartbeats() } },
            config.heartbeatIntervalMs,
            config.heartbeatIntervalMs,
            TimeUnit.MILLISECONDS
        )
        runOnCoordinator { broadcastHello() }
        scheduleHelloLoop()
        scheduler.scheduleAtFixedRate(
            { runOnCoordinator { refreshConferenceAudioLevels() } },
            100L,
            100L,
            TimeUnit.MILLISECONDS
        )
        log("Coordinator started port=$localSignalingPort trace=boot")
    }

    fun stop() {
        if (stopped) return
        stopped = true
        acquireReleaseWatchdog.cancelAll()
        activityStack.clear()
        scheduler.shutdownNow()
        runCatching { signalingChannel.stop() }
        runCatching { discoveryService.stop() }
        runCatching { coordinatorExecutor.shutdownNow() }
        sessions.clear()
        callableModuleGate.clear()
        pendingGroupJoinsBySession.clear()
        runCatching { mediaRegistry.releaseAll() }
        log("Coordinator stopped")
    }

    fun call(local: EndpointAddress, remote: EndpointAddress, channelId: String? = null): String =
        runOnCoordinatorSync {
            when (val gate = channelGovernance.canStart(
                Operation.SINGLE_CALL,
                ChannelGovernanceRuntime.UNICAST_CHANNEL_ID
            )) {
                is GateDecision.Blocked -> error("GATE_BLOCKED:${gate.primaryReason.code}")
                is GateDecision.Allow -> Unit
            }
            when (val prep = prepareForUnicastOutgoing()) {
                is UnicastOutgoingPrep.Ready -> Unit
                is UnicastOutgoingPrep.Blocked -> error(prep.reason)
            }
            placeCallInternal(local, remote, channelId, SessionType.UNICAST)
        }

    fun groupCall(
        local: EndpointAddress,
        remoteEndpoints: List<EndpointAddress>,
        channelId: String
    ): String? = runOnCoordinatorSync {
        meshCallInternal(
            local,
            remoteEndpoints,
            channelId,
            SessionType.GROUP,
            MeshSessionMode.GROUP,
            config.maxGroupModules
        )
    }

    /**
     * Symmetric mesh reconciliation: bootstrap node (min moduleId) creates session with pairwise
     * invites; all nodes expand roster and complete mesh links for localId < peerId.
     */
    fun reconcileGroupMesh(channelId: String) = runOnCoordinator {
        reconcileGroupMeshInternal(channelId)
    }

    fun conferenceCall(
        local: EndpointAddress,
        remoteEndpoints: List<EndpointAddress>,
        channelId: String
    ): String? = runOnCoordinatorSync {
        meshCallInternal(
            local,
            remoteEndpoints,
            channelId,
            SessionType.CONFERENCE,
            MeshSessionMode.CONFERENCE,
            config.maxConferenceModules
        )
    }

    /**
     * ADR-0017: submit MEETING_START business intent before host conference mesh creation.
     * App layer must not own declaration fields directly.
     */
    fun submitMeetingStartIntent(
        channelId: String,
        mode: MeetingMode,
        expectedInviteTargets: Set<EndpointId>
    ): Boolean = runOnCoordinatorSync {
        if (MeetingStartDeclaration.validateConsistency(mode, expectedInviteTargets) != null) {
            return@runOnCoordinatorSync false
        }
        pendingMeetingStartIntentByChannel[channelId] = mode to expectedInviteTargets.toSet()
        true
    }

    /**
     * Sends conference invites without adding invitees to the host mesh yet.
     * Keeps solo-host media ready (Waiting) until a peer accepts via GROUP_ACCEPT.
     */
    fun sendConferenceInvites(sessionId: String, invitees: List<EndpointAddress>): Int =
        runOnCoordinatorSync { sendConferenceInvitesInternal(sessionId, invitees) }

    /**
     * Internal (coordinator-thread) variant of sendConferenceInvites — call this when already
     * running on the coordinator executor to avoid a re-entrancy deadlock.
     */
    private fun sendConferenceInvitesInternal(
        sessionId: String,
        invitees: List<EndpointAddress>,
        rejoin: Boolean = false
    ): Int {
        val session = sessions[sessionId] ?: return 0
        if (session.type != SessionType.CONFERENCE) return 0
        if (invitees.isEmpty()) return 0

        val local = session.local
        val channelId = session.channelId ?: return 0
        val existingKeys = meshRoster(session).map { it.key }.toSet()
        val targets = invitees.filter { it.moduleId != local.moduleId }
        if (targets.isEmpty()) return 0

        val toAdd = targets.filter { it.key !in existingKeys }
        toAdd.forEach { remote ->
            session.memberModules.add(remote.moduleId)
        }
        if (toAdd.isNotEmpty()) {
            conferenceParticipantManager.addToRoster(session.id, toAdd)
        }

        val reconnectTargets = targets.filter { target ->
            val moduleId = target.moduleId.value
            val disconnected = !qosMonitor.isGroupConnected(moduleId)
            when {
                target.key !in existingKeys -> false
                rejoin && moduleId in (
                    conferenceParticipantManager.leftMemberEndpoints(session.id)?.keys ?: emptySet()
                    ) -> true
                disconnected -> true
                else -> false
            }
        }
        val inviteTargets = (toAdd + reconnectTargets).distinctBy { it.key }
        if (inviteTargets.isEmpty()) return 0
        if (!ensureMeetingStartDeclarationForInviteDispatch(channelId, inviteTargets, local)) {
            failMeetingStartDeclaration(channelId, "INVALID_DECLARATION")
            return 0
        }
        val usePolicyDispatch = meetingStartDeclaration(channelId)?.isFrozen == false
        if (!usePolicyDispatch) {
            session.channelId?.let { channelId ->
                when (val gate = channelGovernance.canStart(Operation.MEETING_INVITE, channelId)) {
                    is GateDecision.Blocked -> {
                        log(
                            "CONFERENCE_INVITE_GATE blocked ch=$channelId " +
                                "primary=${gate.primaryReason.code} transition=${gate.transitionId}"
                        )
                        return 0
                    }
                    is GateDecision.Allow -> Unit
                }
            }
        }

        val allMembers = meshRoster(session)
        applyGroupTopology(session, allMembers.size)
        val payloadBase = groupPayloadBase(session).copy(rejoin = rejoin)

        val sent = if (usePolicyDispatch) {
            dispatchMeetingStartInvitesWithPolicy(
                session = session,
                channelId = channelId,
                sessionId = sessionId,
                inviteTargets = inviteTargets,
                rejoin = rejoin,
                allMembers = allMembers,
                payloadBase = payloadBase
            )
        } else {
            dispatchConferenceInvitesDirect(
                session = session,
                channelId = channelId,
                sessionId = sessionId,
                inviteTargets = inviteTargets,
                rejoin = rejoin,
                allMembers = allMembers,
                payloadBase = payloadBase
            )
        }
        session.channelId?.let { maybeEvaluateMeetingStartCompletion(it) }
        if (rejoin && sent > 0) {
            inviteTargets.forEach { remote ->
                ConferenceRecoveryOwnershipLog.emitMembershipMutationDecision(
                    session = session,
                    localModuleId = localModuleId.value,
                    participantId = remote.moduleId.value,
                    controller = conferenceEdgeRecoveryController,
                    type = ConferenceRecoveryOwnershipLog.MembershipMutationDecisionType.REJOIN_REQUIRED,
                    reason = "conference_rejoin_invite_sent"
                )
            }
        }
        return sent
    }

    private fun dispatchMeetingStartInvitesWithPolicy(
        session: TalkbackSession,
        channelId: String,
        sessionId: String,
        inviteTargets: List<EndpointAddress>,
        rejoin: Boolean,
        allMembers: List<EndpointAddress>,
        payloadBase: GroupSessionPayload
    ): Int {
        val policy = try {
            TransitionPolicy.meetingStartInviteDispatch()
        } catch (_: PolicyConfigurationException) {
            failMeetingStartDeclaration(channelId, "INVALID_POLICY")
            return 0
        }
        val dispatcher = InviteDispatcher(
            policy = policy,
            // Coordinator thread must not block on backoff; immediate re-attempts only.
            sleeper = { }
        )
        val result = dispatcher.dispatch(inviteTargets) { remote ->
            trySendSingleConferenceInvite(
                session = session,
                channelId = channelId,
                sessionId = sessionId,
                remote = remote,
                rejoin = rejoin,
                allMembers = allMembers,
                payloadBase = payloadBase
            )
        }
        GovernanceObservabilityLog.inviteDispatchCompleted(channelId, result)
        return when (result.outcome) {
            InviteDispatchOutcome.SUCCESS -> {
                onMeetingStartInviteDispatchCompleted(channelId, inviteTargets.size, result.sentCount)
                result.sentCount
            }
            InviteDispatchOutcome.FAILED_NON_RETRYABLE,
            InviteDispatchOutcome.FAILED_RETRY_EXHAUSTED -> {
                failMeetingStartDeclaration(channelId, "INVITE_DISPATCH_FAILED")
                result.sentCount
            }
        }
    }

    private fun dispatchConferenceInvitesDirect(
        session: TalkbackSession,
        channelId: String,
        sessionId: String,
        inviteTargets: List<EndpointAddress>,
        rejoin: Boolean,
        allMembers: List<EndpointAddress>,
        payloadBase: GroupSessionPayload
    ): Int {
        var sent = 0
        inviteTargets.forEach { remote ->
            when (
                trySendSingleConferenceInvite(
                    session = session,
                    channelId = channelId,
                    sessionId = sessionId,
                    remote = remote,
                    rejoin = rejoin,
                    allMembers = allMembers,
                    payloadBase = payloadBase
                )
            ) {
                InviteDispatchSendResult.Sent -> sent++
                is InviteDispatchSendResult.Failed -> Unit
            }
        }
        onMeetingStartInviteDispatchCompleted(channelId, inviteTargets.size, sent)
        return sent
    }

    private fun trySendSingleConferenceInvite(
        session: TalkbackSession,
        channelId: String,
        sessionId: String,
        remote: EndpointAddress,
        rejoin: Boolean,
        allMembers: List<EndpointAddress>,
        payloadBase: GroupSessionPayload
    ): InviteDispatchSendResult {
        if (meetingStartDeclaration(channelId)?.isFrozen == false) {
            when (channelGovernance.canStart(Operation.MEETING_INVITE, channelId)) {
                is GateDecision.Blocked ->
                    return InviteDispatchSendResult.Failed(InviteDispatchError.GATE_BLOCKED)
                is GateDecision.Allow -> Unit
            }
        }
        val moduleId = remote.moduleId.value
        conferenceParticipantManager.leftMemberEndpoints(session.id)?.remove(moduleId)
        if (!canSendConferenceInvite(session, moduleId, forReconnect = rejoin)) {
            return InviteDispatchSendResult.Failed(InviteDispatchError.TRANSPORT_NOT_READY)
        }
        session.meshCompletedModules.remove(moduleId)
        val peer = resolvePeerForModule(moduleId)
            ?: run {
                log("[${session.traceId}] Conference invite skipped: $moduleId not discovered")
                return InviteDispatchSendResult.Failed(InviteDispatchError.UNKNOWN_ENDPOINT)
            }
        session.remotePeersByModule[moduleId] = peer
        conferenceParticipantManager.onInviteSent(
            session.id,
            moduleId,
            System.currentTimeMillis(),
            rejoin
        )
        // ADR-0019: signaling retry must not acquire/recreate media; first attach only when no PC exists.
        val existingEngine = meshEngineForSession(session, moduleId)
        val signalingRetry = existingEngine != null
        val engine = try {
            existingEngine ?: acquireMeshEngine(session, moduleId, forReconnect = rejoin)
        } catch (e: Exception) {
            log("[${session.traceId}] Conference invite SDP failed for $moduleId: ${e.message}")
            return InviteDispatchSendResult.Failed(InviteDispatchError.SDP_BUILD_FAILED)
        }
        wireIceCallback(session, moduleId, engine)
        val offer = try {
            if (signalingRetry) {
                engine.rollbackNegotiation()
            }
            engine.createOffer(iceRestart = rejoin || signalingRetry)
        } catch (e: Exception) {
            log("[${session.traceId}] Conference invite offer failed for $moduleId: ${e.message}")
            return InviteDispatchSendResult.Failed(InviteDispatchError.SDP_BUILD_FAILED)
        }
        drainPendingIce(session.id, moduleId, engine)
        sendSignal(
            peer,
            buildSignedEnvelope(
                SignalType.GROUP_INVITE,
                session.local,
                remote,
                sessionId,
                payloadBase.copy(sdp = offer).encode()
            )
        )
        reDialByRemoteModule[moduleId] =
            ReDialRecord(session.local, remote, channelId, allMembers, SessionType.CONFERENCE)
        val label = if (rejoin) "Conference rejoin invite" else "Conference invite"
        log("[${session.traceId}] $label sent -> ${remote.key}")
        return InviteDispatchSendResult.Sent
    }

    /** Add peers to an existing GROUP mesh (host pull-in / counter-invite). */
    private fun sendGroupMeshInvitesInternal(sessionId: String, invitees: List<EndpointAddress>): Int {
        val session = sessions[sessionId] ?: return 0
        if (session.type != SessionType.GROUP) return 0
        if (invitees.isEmpty()) return 0

        val local = session.local
        val channelId = session.channelId ?: return 0
        val existingKeys = canonicalAndPendingMemberKeys(session)
        val newInvitees = invitees.filter { invitee ->
            invitee.moduleId != local.moduleId && invitee.key !in existingKeys
        }
        if (newInvitees.isEmpty()) {
            val reconnect = invitees.filter { invitee ->
                invitee.moduleId != local.moduleId &&
                    (qosMonitor.snapshot(invitee.moduleId.value)?.iceState != "CONNECTED")
            }
            if (reconnect.isEmpty()) return 0
            return reconnectExistingGroupMeshPeers(session, reconnect)
        }

        newInvitees.forEach { remote ->
            session.memberModules.add(remote.moduleId)
            session.pendingInviteeEndpoints[remote.moduleId.value] = remote
        }
        applyGroupTopology(session, session.groupMembers.size)
        val payloadBase = groupPayloadBase(session)

        var sent = 0
        newInvitees.forEach { remote ->
            val moduleId = remote.moduleId.value
            if (moduleId in session.remotePeersByModule &&
                qosMonitor.isGroupConnected(moduleId)
            ) {
                return@forEach
            }
            session.meshCompletedModules.remove(moduleId)
            val peer = resolvePeerForModule(moduleId)
            if (peer == null) {
                log("[${session.traceId}] Group invite skipped: $moduleId not discovered")
                return@forEach
            }
            session.remotePeersByModule[moduleId] = peer
            meshParticipant(session,moduleId).apply {
                invite = InviteState.INVITING
                invitedAtMs = System.currentTimeMillis()
            }
            val engine = acquireMeshEngine(session, moduleId, forReconnect = false)
            wireIceCallback(session, moduleId, engine)
            val offer = engine.createOffer()
            drainPendingIce(session.id, moduleId, engine)
            sendSignal(
                peer,
                buildSignedEnvelope(
                    SignalType.GROUP_INVITE,
                    local,
                    remote,
                    sessionId,
                    payloadBase.copy(sdp = offer).encode()
                )
            )
            reDialByRemoteModule[moduleId] =
                ReDialRecord(local, remote, channelId, session.groupMembers, SessionType.GROUP)
            sent++
            log("[${session.traceId}] Group invite sent -> ${remote.key}")
        }
        if (sent > 0 && session.mediaTopology == GroupMediaTopology.MESH) {
            completeGroupMesh(session)
        }
        if (sent > 0) {
            emitGroupTopologySnapshot(TopologySnapshotReason.MESH_OFFERED, session)
        }
        return sent
    }

    /** Re-offer to roster members that are already in the mesh but lost ICE (GROUP_JOIN only). */
    private fun reconnectExistingGroupMeshPeers(
        session: TalkbackSession,
        reconnect: List<EndpointAddress>
    ): Int {
        if (session.type != SessionType.GROUP) return 0
        val channelId = session.channelId ?: return 0
        if (isMeshRepairSuppressed(channelId)) return 0
        var sent = 0
        reconnect.forEach { remote ->
            val moduleId = remote.moduleId.value
            val ice = qosMonitor.snapshot(moduleId)?.iceState
            if (!groupMeshReconciler.canReconnect(channelId, moduleId, ice)) {
                return@forEach
            }
            val now = System.currentTimeMillis()
            val lastMs = lastGroupMeshReconnectMsByPeer[moduleId] ?: 0L
            if (now - lastMs < GROUP_MESH_RECONNECT_THROTTLE_MS) {
                return@forEach
            }
            lastGroupMeshReconnectMsByPeer[moduleId] = now
            val peer = discoveredByModule[moduleId]
            if (peer == null) {
                log("${sessionTag(session)} Group reconnect skipped: $moduleId not discovered ice=$ice")
                return@forEach
            }
            session.remotePeersByModule[moduleId] = peer
            meshParticipant(session,moduleId).apply {
                invite = InviteState.INVITING
                invitedAtMs = System.currentTimeMillis()
                media = MediaState.RECONNECTING
            }
            val engine = getOrCreateMeshEngine(session, moduleId)
            wireIceCallback(session, moduleId, engine)
            val payloadBase = groupPayloadBase(session)
            val offer = engine.createOffer(iceRestart = true)
            drainPendingIce(session.id, moduleId, engine)
            sendSignal(
                peer,
                buildSignedEnvelope(
                    SignalType.GROUP_JOIN,
                    session.local,
                    remote,
                    session.id,
                    payloadBase.copy(sdp = offer).encode()
                )
            )
            groupMeshReconciler.markReconnectAttempt(channelId, moduleId)
            sent++
            log("${sessionTag(session)} Group reconnect join offered -> ${remote.key} ice=$ice")
        }
        return sent
    }

    private fun meshCallInternal(
        local: EndpointAddress,
        remoteEndpoints: List<EndpointAddress>,
        channelId: String,
        sessionType: SessionType,
        sessionMode: MeshSessionMode,
        maxModules: Int
    ): String? {
        val soloConference = sessionType == SessionType.CONFERENCE && remoteEndpoints.isEmpty()
        require(soloConference || remoteEndpoints.isNotEmpty()) {
            "meshCall requires at least one remote endpoint"
        }
        val meshOperation = when (sessionType) {
            SessionType.CONFERENCE -> Operation.MEETING_JOIN
            SessionType.GROUP -> Operation.GROUP_BOOTSTRAP
            else -> null
        }
        meshOperation?.let { op ->
            when (val gate = channelGovernance.canStart(op, channelId)) {
                is GateDecision.Blocked -> {
                    log(
                        "MESH_GATE blocked op=$op ch=$channelId primary=${gate.primaryReason.code} " +
                            "transition=${gate.transitionId}"
                    )
                    return null
                }
                is GateDecision.Allow -> Unit
            }
        }
        if (sessionType == SessionType.CONFERENCE) {
            channelGovernance.supersedeMeetingEndForNewMeeting(channelId)
            meetingStartDeclarationByChannel.remove(channelId)
            channelGovernance.beginTransition(TransitionTrigger.MEETING_START, channelId)
            resolveMeetingStartIntent(channelId, remoteEndpoints)?.let { (mode, targets) ->
                if (openMeetingStartDeclaration(channelId, mode, targets) == null) {
                    failMeetingStartDeclaration(channelId, "INVALID_DECLARATION")
                    return null
                }
                consumeMeetingStartIntent(channelId)
            }
        }
        if (sessionType == SessionType.GROUP) {
            endStaleConferenceBlockingGroup(channelId)
            if (sessions.values.any { it.channelId == channelId && it.type == SessionType.CONFERENCE }) {
                log("Blocked GROUP mesh while CONFERENCE active on $channelId")
                return null
            }
        }
        if (sessionType == SessionType.GROUP && blocksGroupOnChannel(channelId)) {
            // Heal stale CONFERENCE FSM left after remote hangup before blocking PTT setup.
            if (!sessions.values.any { it.channelId == channelId && it.type == SessionType.CONFERENCE } &&
                !pendingConferenceInvitesByChannel.containsKey(channelId) &&
                uiMeetingPreferredChannels[channelId] != true &&
                !meetingPreferred
            ) {
                releaseChannelModeIfIdle(channelId)
            }
        }
        if (sessionType == SessionType.GROUP && blocksGroupOnChannel(channelId)) {
            log("Blocked GROUP mesh while conference preferred or pending on $channelId")
            return null
        }
        if (!channelModeFsm(channelId).requestMode(
                if (sessionType == SessionType.CONFERENCE) ChannelMode.CONFERENCE else ChannelMode.GROUP_PTT,
                local.moduleId.value
            )
        ) {
            log("Blocked ${sessionType.name} mesh: channel mode conflict on $channelId")
            return null
        }
        val groupMeshModulesForBarrier = if (sessionType == SessionType.CONFERENCE) {
            groupMeshModulesOnChannel(channelId)
        } else {
            emptySet()
        }
        endConflictingMeshSessions(channelId, sessionType)
        if (sessionType == SessionType.CONFERENCE && groupMeshModulesForBarrier.isNotEmpty()) {
            val barrier = mediaRegistry.resetMeshForMeetingBarrier(groupMeshModulesForBarrier, channelId)
            if (barrier.unresolvedCount > 0) {
                log(
                    "MEDIA_BARRIER unresolved=${barrier.unresolvedModuleIds} " +
                        "ch=$channelId modules=${barrier.moduleCount}"
                )
            }
        }
        findReusableMeshSession(channelId, sessionType)?.let { existing ->
            log("[${existing.traceId}] Reuse ${sessionType.name} session for channel=$channelId")
            if (sessionType == SessionType.CONFERENCE) {
                auditConferenceSessionLifecycle(
                    event = "SESSION_REUSED",
                    session = existing,
                    writer = "meshCallInternal",
                    cause = "reuse_existing"
                )
            }
            return existing.id
        }
        if (sessionType == SessionType.GROUP) {
            val allModuleIds = (remoteEndpoints.map { it.moduleId } + local.moduleId).toSet()
            val primary = resolveBootstrapPrimary(allModuleIds)
            if (primary != null && local.moduleId != primary) {
                log("Deferring GROUP mesh create to primary ${primary.value} on $channelId")
                reconcileGroupMeshInternal(channelId)
                return sessions.values.firstOrNull {
                    it.channelId == channelId && it.type == SessionType.GROUP
                }?.id
            }
        }
        if (activeSessionCount() >= config.maxActiveSessions) {
            error("Active session limit reached: ${config.maxActiveSessions}")
        }
        val channelSnap = ChannelMembershipSnapshot.capture(channelManager, channelId)
        val deferConferenceInviteDispatch = soloConference &&
            meetingStartDeclaration(channelId)?.let {
                it.mode == MeetingMode.MULTI_PARTY && it.expectedInviteTargets.isNotEmpty()
            } == true
        val effectiveChannelSnap = if (deferConferenceInviteDispatch) emptySet() else channelSnap
        val allMembers = ChannelMembershipSnapshot.resolveInitialInvites(
            configured = effectiveChannelSnap,
            local = local,
            explicitRemotes = remoteEndpoints,
            resolveModule = { mid -> endpointForDialableModule(mid) }
        )
        val remoteModuleIds = allMembers.map { it.moduleId }.filter { it != local.moduleId }.distinct()
        require(remoteModuleIds.size + 1 <= maxModules) {
            "${sessionType.name} exceeds maxModules=$maxModules (including local module)"
        }
        val sessionId = when (sessionType) {
            SessionType.GROUP -> GroupRoomId.forChannel(channelId)
            else -> UUID.randomUUID().toString()
        }
        if (sessionType == SessionType.CONFERENCE) {
            conferenceJoinLatencyTracker.onInviteAccepted(
                sessionId = sessionId,
                channelId = channelId,
                role = "host"
            )
        }
        val session = TalkbackSession(sessionId, sessionType, local, channelId)
        freezeChannelMemberSnapshot(session)
        session.localInitiated = true
        session.initiatorModuleId = local.moduleId
        session.floorAuthorityModuleId = local.moduleId
        if (sessionType == SessionType.CONFERENCE) {
            initConferenceParticipantState(session, allMembers)
        } else {
            session.groupMembers = allMembers
        }
        session.rosterEpochMs = System.currentTimeMillis()
        session.memberModules.add(local.moduleId)
        val inviteRemotes = allMembers.filter { it.moduleId != local.moduleId }
        inviteRemotes.forEach { remote ->
            val peer = discoveredByModule[remote.moduleId.value]
                ?: error("Remote module not discovered: ${remote.moduleId.value}")
            session.memberModules.add(remote.moduleId)
            session.remotePeersByModule[remote.moduleId.value] = peer
        }
        if (inviteRemotes.isNotEmpty()) {
            session.remote = inviteRemotes.first()
            session.remotePeer = session.remotePeersByModule[session.remote!!.moduleId.value]
        }
        session.accepted = true
        sessions[sessionId] = session
        if (sessionType == SessionType.CONFERENCE) {
            auditConferenceSessionLifecycle(
                event = "SESSION_CREATED",
                session = session,
                writer = "meshCallInternal",
                cause = if (soloConference) "solo_host" else "host_with_invites"
            )
        }
        if (sessionType == SessionType.GROUP) {
            ensureConvergenceAnchor(session)
            maybeEmitAppStartSnapshot(session)
        }
        if (sessionType == SessionType.GROUP || sessionType == SessionType.CONFERENCE) {
            applyGroupTopology(session, allMembers.size)
        }
        if (sessionType == SessionType.CONFERENCE) {
            conferenceParticipantManager.syncParticipantsFromMembers(session.id, local.moduleId)
        } else {
            session.syncParticipantsFromMembers(local.moduleId)
        }
        val inviteTargets = groupInviteTargets(session, sessionType, local, inviteRemotes)
        inviteTargets.forEach { remote ->
            if (sessionType == SessionType.CONFERENCE) {
                conferenceParticipantManager.onInviteSent(
                    session.id,
                    remote.moduleId.value,
                    System.currentTimeMillis(),
                    forReconnect = false
                )
            } else {
                meshParticipant(session, remote.moduleId.value).apply {
                    invite = InviteState.INVITING
                    invitedAtMs = System.currentTimeMillis()
                }
            }
        }

        val payloadBase = groupPayloadBase(session)
        inviteTargets.forEach { remote ->
            val peer = session.remotePeersByModule[remote.moduleId.value]!!
            val engine = acquireMeshEngine(session, remote.moduleId.value, forReconnect = false)
            wireIceCallback(session, remote.moduleId.value, engine)
            val offer = engine.createOffer()
            drainPendingIce(session.id, remote.moduleId.value, engine)
            sendSignal(
                peer,
                buildSignedEnvelope(
                    SignalType.GROUP_INVITE,
                    local,
                    remote,
                    sessionId,
                    payloadBase.copy(sdp = offer).encode()
                )
            )
        }
        inviteTargets.forEach { remote ->
            reDialByRemoteModule[remote.moduleId.value] =
                ReDialRecord(local, remote, channelId, allMembers, sessionType)
        }
        val label = if (sessionType == SessionType.CONFERENCE) "Conference" else "Group call"
        if (sessionType == SessionType.CONFERENCE) {
            onMeetingStartInviteDispatchCompleted(channelId, inviteTargets.size, inviteTargets.size)
            if (soloConference) {
                tryEnsureConferenceDuplex(session)
                log("[${session.traceId}] $label ${local.key} solo on ch=$channelId")
            } else {
                log("[${session.traceId}] $label ${local.key} -> ${inviteTargets.size} targets ch=$channelId topology=${session.mediaTopology}")
            }
            maybeEvaluateMeetingStartCompletion(channelId)
        } else {
            log("[${session.traceId}] $label ${local.key} -> ${inviteTargets.size} targets ch=$channelId topology=${session.mediaTopology}")
        }
        return sessionId
    }

    fun onPttPressed(sessionId: String, priority: EndpointPriority = EndpointPriority.NORMAL): PttState =
        runOnCoordinatorSync {
            onPttPressedInternal(sessionId, priority)
            sessions[sessionId]?.ptt?.state ?: PttState.IDLE
        }

    fun onPttReleased(sessionId: String): PttState = runOnCoordinatorSync {
        onPttReleasedInternal(sessionId)
        sessions[sessionId]?.ptt?.state ?: PttState.IDLE
    }

    private fun onPttPressedInternal(sessionId: String, priority: EndpointPriority = EndpointPriority.NORMAL) {
        PttTimingLog.pttDown(sessionId)
        val session = sessions[sessionId] ?: return
        if (!session.type.usesFloorControl()) return
        session.channelId?.let { channelId ->
            if (session.type == SessionType.GROUP) {
                when (val gate = channelGovernance.canStart(Operation.PTT, channelId)) {
                    is GateDecision.Blocked -> {
                        log(
                            "PTT_GATE blocked ch=$channelId primary=${gate.primaryReason.code} " +
                                "category=${gate.category} transition=${gate.transitionId}"
                        )
                        return
                    }
                    is GateDecision.Allow -> Unit
                }
            }
        }
        session.localAcquireTimedOut = false
        val currentOwner = session.floor.owner()
        log(
            "PTT_GATE ${sessionTag(session)} sid=$sessionId " +
                "hash=${System.identityHashCode(session)} " +
                "owner=${currentOwner?.key ?: "null"} " +
                "version=${session.floor.version()} epoch=${session.floor.epoch()} " +
                "ptt=${session.ptt.state}"
        )
        if (currentOwner != null && !isLocalIdentity(session, currentOwner)) {
            log(
                "PTT_GATE ${sessionTag(session)} silent-return-owner " +
                    "owner=${currentOwner.key} version=${session.floor.version()} " +
                    "epoch=${session.floor.epoch()}"
            )
            return
        }
        if (session.type == SessionType.GROUP) {
            val identity = evaluateGroupIdentityStability(session)
            if (!identity.stable) {
                log(
                    "PTT_GATE ${sessionTag(session)} sid=$sessionId identity_unstable " +
                        "reason=${identity.reason} detail=${identity.detail ?: "-"}"
                )
                emitGroupTopologySnapshot(TopologySnapshotReason.PTT_BLOCKED, session)
                return
            }
        }
        val reqState = session.ptt.onEvent(PttEvent.Press)
        if (reqState != PttState.REQUEST_FLOOR) {
            log(
                "PTT_GATE ${sessionTag(session)} silent-return-state " +
                    "ptt=$reqState version=${session.floor.version()} epoch=${session.floor.epoch()}"
            )
            return
        }
        val version = session.floor.nextRequestVersion()
        val localIdentity = groupLocalIdentity(session)
        session.floorOwner.createRequestToken(session.id, localIdentity, version)
        val traceId = FloorTrace.nextId()
        val payload = FloorPayload.forRequest(
            localIdentity,
            version,
            session.floor.epoch(),
            priority,
            traceId = traceId
        ).encode()
        session.lastFloorRequestMs = System.currentTimeMillis()
        session.lastFloorRequestEpoch = session.floor.epoch()
        dispatchFloorRequest(session, payload, traceId)
    }

    private fun onPttReleasedInternal(sessionId: String) {
        PttTimingLog.pttUp(sessionId)
        val session = sessions[sessionId] ?: return
        if (!session.type.usesFloorControl()) return
        acquireReleaseWatchdog.onFloorLost(sessionId)
        val stateBefore = session.ptt.state
        val state = session.ptt.onEvent(PttEvent.Release)
        if (stateBefore == PttState.REQUEST_FLOOR) {
            val identity = FloorOperationIdentity(session.id, groupLocalIdentity(session).key)
            val requestVersion = session.floorOwner.tokens.lookup(identity)?.version
            session.floorOwner.invalidateRequestToken(identity)
            if (requestVersion != null) {
                dispatchFloorRequestCancel(session, requestVersion)
            }
        }
        session.pendingTransmit = false
        stopSessionCapture(session)
        moduleMixer.setActiveCapture(null)
        if (state == PttState.RELEASE_FLOOR && releaseLocalFloorIfHeld(session)) {
            broadcastFloorRelease(session)
        }
        updateSessionReceivePlayback(session, "ptt_released")
    }

    fun hangup(sessionId: String) = runOnCoordinatorSync { hangupInternal(sessionId) }

    /** Leave a conference locally without ending it for remaining participants. */
    fun leaveConference(
        sessionId: String,
        reason: String = "UNSPECIFIED",
        caller: String = "UNKNOWN"
    ) = runOnCoordinator { leaveConferenceInternal(sessionId, reason, caller) }

    /** User left meeting UI: kick GROUP mesh recovery (event-driven). */
    fun clearConferencePttCooldown(channelId: String) = runOnCoordinator {
        onChannelLifecycleEvent(channelId, ChannelLifecycleEvent.PttRecoveryRequested)
    }

    fun acceptCall(sessionId: String) = runOnCoordinatorSync {
        val session = sessions[sessionId] ?: error("Session not found: $sessionId")
        check(session.type == SessionType.UNICAST) { "Not a unicast session" }
        check(!session.accepted) { "Call already accepted" }
        val offerSdp = session.pendingRemoteOfferSdp ?: error("No pending invite SDP")
        val remote = session.remote ?: error("No remote endpoint")
        val peer = session.remotePeer ?: error("No remote peer")
        acceptInviteWithSdp(session.id, peer, session.local, remote, offerSdp)
    }

    fun rejectCall(sessionId: String, reason: String = "DECLINED") = runOnCoordinatorSync {
        val session = sessions.remove(sessionId) ?: return@runOnCoordinatorSync
        val wasUnicast = session.type == SessionType.UNICAST
        pendingIceBySession.remove(sessionId)
        session.remote?.moduleId?.value?.let { reDialByRemoteModule.remove(it) }
        val peer = session.remotePeer
        val remote = session.remote
        if (peer != null && remote != null) {
            sendSignal(
                peer,
                buildSignedEnvelope(SignalType.CALL_REJECT, session.local, remote, sessionId, reason)
            )
        }
        releaseSessionMedia(session)
        log("${sessionTag(session)} Call rejected locally reason=$reason")
        if (wasUnicast) {
            resumeGroupSessionsAfterUnicast(sessionId)
        }
    }

    fun setCallMuted(sessionId: String, muted: Boolean, reason: String = "unspecified") =
        runOnCoordinatorSync {
            val session = sessions[sessionId] ?: return@runOnCoordinatorSync
            if (session.type != SessionType.UNICAST && session.type != SessionType.CONFERENCE) {
                return@runOnCoordinatorSync
            }
            val old = session.muted
            if (old != muted) {
                logCallMuteChanged(
                    session = session,
                    old = old,
                    new = muted,
                    reason = reason,
                    caller = callMuteMutationCaller()
                )
            }
            session.muted = muted
            if (muted) {
                session.conferenceTransmitSuspendedByBarrier = false
            }
            sessionMediaEngines(session).forEach { it.setMuted(muted) }
            if (session.type == SessionType.CONFERENCE && session.accepted && isSessionTransmitReady(session)) {
                if (muted) {
                    stopSessionCapture(session)
                } else {
                    ensureConferenceDuplex(session)
                }
            }
        }

    fun activeUnicastSession(): TalkbackSessionSnapshot? = runOnCoordinatorSync {
        sessions.values.firstOrNull { it.type == SessionType.UNICAST }?.let { toSessionSnapshot(it) }
    }

    fun activeSessionIds(): List<String> = sessions.keys().toList()

    fun sessionSnapshots(): List<TalkbackSessionSnapshot> = runOnCoordinatorSync {
        sessions.values.map { toSessionSnapshot(it) }
    }

    fun sessionSnapshotForChannel(channelId: String): TalkbackSessionSnapshot? = runOnCoordinatorSync {
        val session = bestSessionForChannel(channelId) ?: return@runOnCoordinatorSync null
        toSessionSnapshot(session)
    }

    fun sessionPresenceSnapshot(sessionId: String): SessionPresenceSnapshot? = runOnCoordinatorSync {
        val session = sessions[sessionId] ?: return@runOnCoordinatorSync null
        val conferenceSnap = if (isConferenceSession(session)) conferenceSnapshot(session) else null
        PresenceProjector.sessionSnapshot(session, conferenceSnap)
    }

    fun sessionPresenceSnapshots(): List<SessionPresenceSnapshot> = runOnCoordinatorSync {
        sessions.values.map { session ->
            val conferenceSnap = if (isConferenceSession(session)) conferenceSnapshot(session) else null
            PresenceProjector.sessionSnapshot(session, conferenceSnap)
        }
    }

    fun modulePresenceSnapshot(): ModulePresenceSnapshot = runOnCoordinatorSync {
        PresenceProjector.moduleSnapshot(
            moduleMixer = moduleMixer,
            localUplinkGrant = isLocalUplinkGranted(),
            iceByPeer = qosMonitor.all().associate { it.remoteModuleId to it.iceState },
            stackTopSessionId = activityStack.topSessionId()
        )
    }

    fun isChannelMediaReady(channelId: String): Boolean = runOnCoordinatorSync {
        val session = meshSessionForChannel(channelId) ?: return@runOnCoordinatorSync false
        if (session.type == SessionType.CONFERENCE) {
            return@runOnCoordinatorSync isConferenceUiReady(session)
        }
        isSessionTransmitReady(session)
    }

    fun isChannelConnecting(channelId: String): Boolean = runOnCoordinatorSync {
        val session = meshSessionForChannel(channelId) ?: return@runOnCoordinatorSync false
        if (session.type == SessionType.CONFERENCE && isConferenceUiReady(session)) {
            return@runOnCoordinatorSync false
        }
        isSessionMediaNegotiating(session)
    }

    /** True when no GROUP session is negotiating, suspended, or holding live ICE on the channel. */
    fun isGroupSessionTrulyIdle(channelId: String): Boolean = runOnCoordinatorSync {
        val session = sessions.values.firstOrNull {
            it.channelId == channelId && it.type == SessionType.GROUP
        } ?: return@runOnCoordinatorSync true
        if (session.isForegroundSuspended()) return@runOnCoordinatorSync false
        if (pendingGroupJoinsBySession[session.id]?.isNotEmpty() == true) return@runOnCoordinatorSync false
        if (isSessionMediaNegotiating(session)) return@runOnCoordinatorSync false
        val remoteIds = remoteModuleIds(session)
        if (remoteIds.isEmpty()) return@runOnCoordinatorSync true
        return@runOnCoordinatorSync remoteIds.none { id ->
            when (qosMonitor.snapshot(id)?.iceState) {
                "CHECKING", "CONNECTED", "COMPLETED" -> true
                else -> false
            }
        }
    }

    fun channelReadiness(channelId: String): ChannelReadiness = runOnCoordinatorSync {
        resolveChannelReadiness(channelId)
    }

    private fun resolveChannelReadiness(channelId: String): ChannelReadiness {
        if (stopped) return ChannelReadiness.NO_SERVICE
        val dialablePeers = countDialableRemoteModules()
        val session = meshSessionForChannel(channelId)
        return when {
            session != null && (
                isSessionTransmitReady(session) ||
                    (session.type == SessionType.CONFERENCE && isConferenceUiReady(session))
                ) -> ChannelReadiness.READY
            session != null && isSessionMediaNegotiating(session) -> ChannelReadiness.CONNECTING
            session != null -> ChannelReadiness.DIRECTORY_SYNC
            dialablePeers > 0 && !isLocalBootstrapPrimary() -> ChannelReadiness.AWAITING_PRIMARY
            dialablePeers > 0 -> ChannelReadiness.DIRECTORY_SYNC
            else -> ChannelReadiness.DISCOVERING
        }
    }

    private fun isLocalBootstrapPrimary(): Boolean {
        val dialable = dialableRemoteModuleIds()
        if (dialable.isEmpty()) return true
        return resolveBootstrapPrimary(dialable + localModuleId) == localModuleId
    }

    private fun countDialableRemoteModules(): Int =
        mergeRemoteModuleViews().count { isModuleDialable(it.presence.moduleId.value) }

    private fun isHelloBootstrapPhase(): Boolean =
        System.currentTimeMillis() - coordinatorBootstrapStartedAtMs < config.helloBootstrapDurationMs

    private fun scheduleHelloLoop() {
        if (stopped) return
        val delay = if (isHelloBootstrapPhase()) {
            config.helloBootstrapIntervalMs
        } else {
            config.helloIntervalMs
        }
        scheduler.schedule({
            if (stopped) return@schedule
            runOnCoordinator { broadcastHello() }
            scheduleHelloLoop()
        }, delay, TimeUnit.MILLISECONDS)
    }

    fun isConferenceHostForChannel(channelId: String): Boolean = runOnCoordinatorSync {
        val session = meshSessionForChannel(channelId) ?: return@runOnCoordinatorSync false
        isConferenceHostSession(session)
    }

    /**
     * Linear 0..1 audio level for the meeting center speaker (WebRTC stats, not animated).
     */
    fun meetingSpeakerAudioLevel(channelId: String, speakerEndpointKey: String): Float =
        runOnCoordinatorSync {
            val session = meshSessionForChannel(channelId) ?: return@runOnCoordinatorSync 0f
            if (session.type != SessionType.CONFERENCE || !session.accepted || session.muted) {
                return@runOnCoordinatorSync 0f
            }
            val speakerModule = moduleIdFromEndpointKey(speakerEndpointKey) ?: return@runOnCoordinatorSync 0f
            val engines = enginesForAudioLevel(session)
            val raw = if (speakerModule == localModuleId.value) {
                engines.maxOfOrNull { it.outboundAudioLevel() } ?: 0f
            } else {
                getMeshEngine(speakerModule)?.inboundAudioLevel() ?: 0f
            }
            displayAudioLevel(raw)
        }

    fun refreshStaleGroupSession(channelId: String) = runOnCoordinator {
        refreshStaleMeshSession(channelId, SessionType.GROUP)
        reconcileGroupMeshInternal(channelId)
    }

    fun refreshStaleConferenceSession(channelId: String) = runOnCoordinator {
        refreshStaleMeshSession(channelId, SessionType.CONFERENCE)
        resendConferenceInvitesToUnconnected(channelId)
    }

    /** Re-invite roster members who are not yet connected (e.g. after ring timeout). */
    private fun resendConferenceInvitesToUnconnected(channelId: String) {
        val session = sessions.values.firstOrNull {
            it.channelId == channelId &&
                it.type == SessionType.CONFERENCE &&
                it.accepted &&
                it.initiatorModuleId == localModuleId
        } ?: return
        val targets = conferencePendingInviteTargets(session)
        if (targets.isEmpty()) return
        val sent = sendConferenceInvitesInternal(session.id, targets)
        if (sent > 0) {
            log("[${session.traceId}] Re-sent conference invites to $sent unconnected peer(s)")
        }
    }

    /** Peers still ringing or evicted by ring-timeout — not voluntary leavers. */
    private fun conferencePendingInviteTargets(session: TalkbackSession): List<EndpointAddress> {
        val pendingFromRoster = meshRoster(session).filter { remote ->
            remote.moduleId != session.local.moduleId &&
                !qosMonitor.isGroupConnected(remote.moduleId.value) &&
                meshParticipant(session, remote.moduleId.value).invite in
                setOf(InviteState.INVITING, InviteState.RINGING, InviteState.EXPIRED)
        }
        val pendingEvicted = conferenceParticipantManager.leftMemberEndpoints(session.id)
            ?.values
            ?.filter { remote ->
                !qosMonitor.isGroupConnected(remote.moduleId.value) &&
                    meshParticipant(session, remote.moduleId.value).invite == InviteState.EXPIRED
            }
            ?: emptyList()
        return (pendingFromRoster + pendingEvicted).distinctBy { it.moduleId.value }
    }

    private fun refreshStaleMeshSession(channelId: String, sessionType: SessionType) {
        val session = sessions.values.firstOrNull {
            it.channelId == channelId && it.type == sessionType
        } ?: return
        if (isMeshSessionStuck(session)) {
            log("[${session.traceId}] Stale ${sessionType.name} session on $channelId, forcing reconnect")
            hangupInternal(session.id)
        }
    }

    private fun toSessionSnapshot(session: TalkbackSession): TalkbackSessionSnapshot {
        val conferenceSnap = if (isConferenceSession(session)) conferenceSnapshot(session) else null
        val memberViews = conferenceSnap?.memberViews ?: buildMemberViews(session)
        val projection = conferenceSnap?.let {
            val leftModuleIds = conferenceParticipantManager.leftMemberEndpoints(session.id)?.keys
                ?: session.leftMemberEndpoints.keys
            ConferenceParticipantProjector.project(
                ConferenceParticipantProjector.Input(
                    localModuleId = localModuleId,
                    localKey = session.local.key,
                    sessionAccepted = session.accepted,
                    roster = meshRoster(session),
                    memberViews = memberViews,
                    leftModuleIds = leftModuleIds.toSet()
                )
            )
        }
        val runtimeState = if (isConferenceSession(session)) {
            projectConferenceRuntimeState(session, projection)
        } else {
            null
        }
        val presenceState = if (isConferenceSession(session)) {
            projectConferencePresenceState(session, projection)
        } else {
            null
        }
        if (isConferenceSession(session) && presenceState != null) {
            val joined = presenceState.joinedCount
            val connected = presenceState.connectedCount
            conferenceJoinLatencyTracker.onJoinedCountChanged(session.id, joined, session.channelId)
            conferenceJoinLatencyTracker.onFullMeshReached(session.id, joined, connected)
        }
        return TalkbackSessionSnapshot(
        sessionId = session.id,
        type = session.type,
        channelId = session.channelId,
        protocolFloorOwnerKey = session.floor.owner()?.key,
        localPttState = session.ptt.state,
        memberKeys = conferenceSnap?.roster?.map { it.key } ?: session.groupMembers.map { it.key }.ifEmpty {
            listOfNotNull(session.local.key, session.remote?.key)
        },
        channelMemberModuleIds = session.channelMemberSnapshot.sorted(),
        memberViews = memberViews,
        visibleParticipants = projection?.visibleParticipants ?: emptyList(),
        visibleParticipantCount = projection?.visibleParticipantCount ?: 0,
        joinedParticipantCount = projection?.joinedParticipantCount ?: 0,
        pendingInviteeCount = projection?.pendingInviteeCount ?: 0,
        awaitingAdditionalParticipants = projection?.awaitingAdditionalParticipants ?: false,
        conferenceRuntimeState = runtimeState,
        conferencePresenceProjection = presenceState,
        conferenceEverConnectedModuleIds = conferenceSnap?.everConnectedModules
            ?.map { it.value }
            ?.toSet()
            ?: emptySet(),
        meshConnectedPeerCount = countConnectedRemotes(session),
        connectedRemoteCount = countConnectedRemotes(session),
        callPhase = session.unicastPhase,
        remoteKey = session.remote?.key,
        localInitiated = session.localInitiated,
        muted = session.muted
    )
    }

    private fun isMeetingStartTransitionReady(session: TalkbackSession): Boolean {
        val channelId = session.channelId ?: return false
        val active = channelGovernance.activeTransition(channelId)
        if (active?.isActive == true && active.trigger == TransitionTrigger.MEETING_START) {
            return false
        }
        return true
    }

    private fun projectConferenceRuntimeState(
        session: TalkbackSession,
        participantProjection: ConferenceParticipantProjector.Output?
    ): ConferenceRuntimeState {
        val edgeFacts = conferenceEdgeRecoveryController.factsForSession(session.id)
        val transitionTerminalReady = isMeetingStartTransitionReady(session)
        val connectedRemoteMediaCount = countConnectedRemotes(session)
        val isHost = isConferenceHostSession(session)
        val authorityReachable = isConferenceAuthorityReachable(session)
        val mediaRecovering = isConferenceAuthorityMediaRecovering(session)
        val runtime = ConferenceRuntimeProjector.project(
            ConferenceRuntimeProjector.Input(
                transitionTerminalReady = transitionTerminalReady,
                connectedRemoteMediaCount = connectedRemoteMediaCount,
                sessionAccepted = session.accepted,
                awaitingAdditionalParticipants = participantProjection?.awaitingAdditionalParticipants ?: false,
                mediaRecovering = mediaRecovering,
                edgeRecovering = edgeFacts.anyRecovering,
                edgeRecoveryFailed = edgeFacts.anyFailedMediaRecovery,
                isConferenceHost = isHost,
                authorityReachable = authorityReachable
            )
        )
        maybeLogConferenceRuntimeDecision(
            session = session,
            runtime = runtime,
            transitionTerminalReady = transitionTerminalReady,
            connectedRemoteMediaCount = connectedRemoteMediaCount,
            isHost = isHost,
            authorityReachable = authorityReachable,
            mediaRecovering = mediaRecovering,
            edgeRecovering = edgeFacts.anyRecovering,
            edgeRecoveryFailed = edgeFacts.anyFailedMediaRecovery
        )
        return runtime
    }

    private fun projectConferencePresenceState(
        session: TalkbackSession,
        participantProjection: ConferenceParticipantProjector.Output?
    ): ConferencePresenceProjection {
        val edgeFacts = conferenceEdgeRecoveryController.factsForSession(session.id)
        return ConferencePresenceProjector.project(
            ConferencePresenceProjector.Input(
                sessionAccepted = session.accepted,
                joinedParticipantCount = participantProjection?.joinedParticipantCount ?: 0,
                connectedRemoteModuleIds = connectedMeshPeerIds(session),
                recoveringRemoteModuleIds = edgeFacts.recoveringRemoteModuleIds,
                mediaUnavailableRemoteModuleIds = edgeFacts.mediaUnavailableRemoteModuleIds
            )
        )
    }

    /**
     * Issue2 probe: dump projector inputs whenever the decision signature changes.
     * Lives on the snapshot path (UI poll) so it fires even when emit-only projection logs are absent.
     */
    private fun maybeLogConferenceRuntimeDecision(
        session: TalkbackSession,
        runtime: ConferenceRuntimeState,
        transitionTerminalReady: Boolean,
        connectedRemoteMediaCount: Int,
        isHost: Boolean,
        authorityReachable: Boolean,
        mediaRecovering: Boolean,
        edgeRecovering: Boolean,
        edgeRecoveryFailed: Boolean
    ) {
        val hostModuleId = session.initiatorModuleId?.value
        val hostIce = hostModuleId?.let { qosMonitor.snapshot(it)?.iceState }
        val hostEnginePresent = hostModuleId != null && mediaRegistry.getMesh(hostModuleId) != null
        val hostConferenceEngine =
            hostModuleId != null && mediaRegistry.getConference(hostModuleId) != null
        val meshIcePeers = connectedMeshPeerIds(session).sorted().joinToString(",")
        val uiReady = isConferenceUiReady(session)
        val localConferenceReady = session.accepted && transitionTerminalReady
        val conferenceGeneration = session.rosterEpoch
        val signature = listOf(
            runtime.phase.name,
            isHost,
            session.accepted,
            localConferenceReady,
            transitionTerminalReady,
            authorityReachable,
            hostIce ?: "null",
            hostEnginePresent,
            hostConferenceEngine,
            meshIcePeers,
            connectedRemoteMediaCount,
            edgeRecovering,
            edgeRecoveryFailed,
            runtime.conferenceDegraded,
            mediaRecovering,
            uiReady,
            conferenceGeneration
        ).joinToString("|")
        val previous = lastConferenceRuntimeDecisionBySession.put(session.id, signature)
        if (previous == signature) return
        log(
            ConferenceRuntimeProjectionLogger.formatDecision(
                sessionId = session.id,
                channelId = session.channelId,
                phase = runtime.phase,
                isConferenceHost = isHost,
                sessionAccepted = session.accepted,
                localConferenceReady = localConferenceReady,
                transitionTerminalReady = transitionTerminalReady,
                authorityReachable = authorityReachable,
                hostModuleId = hostModuleId,
                hostIce = hostIce,
                hostEnginePresent = hostEnginePresent,
                hostConferenceEngine = hostConferenceEngine,
                meshIcePeers = meshIcePeers,
                connectedRemoteMediaCount = connectedRemoteMediaCount,
                edgeRecovering = edgeRecovering,
                edgeRecoveryFailed = edgeRecoveryFailed,
                conferenceDegraded = runtime.conferenceDegraded,
                mediaRecovering = mediaRecovering,
                conferenceUiReady = uiReady,
                conferenceSessionPresent = true,
                conferenceGeneration = conferenceGeneration
            )
        )
    }

    /**
     * Gate-R1-B: mesh ICE recovered while no Conference session exists.
     * Makes the A/B fork explicit — forbids "ICE up + silence".
     */
    private fun maybeLogConferenceRuntimeMissing(remoteModuleId: String, iceState: String) {
        val conferenceSessions = sessions.values.filter { it.type == SessionType.CONFERENCE }
        if (conferenceSessions.isNotEmpty()) return
        val channelId = sessions.values
            .mapNotNull { it.channelId }
            .groupingBy { it }
            .eachCount()
            .maxByOrNull { it.value }
            ?.key
        val signature = listOf(channelId ?: "null", remoteModuleId, iceState, conferenceSessions.size).joinToString("|")
        val previous = lastConferenceRuntimeMissingByPeer.put(remoteModuleId, signature)
        if (previous == signature) return
        log(
            ConferenceRuntimeProjectionLogger.formatMissing(
                channelId = channelId,
                peerModuleId = remoteModuleId,
                iceState = iceState,
                reason = "no_conference_session",
                conferenceSessionCount = 0
            )
        )
    }

    private fun isConferenceAuthorityReachable(session: TalkbackSession): Boolean {
        val hostModuleId = session.initiatorModuleId?.value ?: return false
        return isPeerMediaConnected(hostModuleId)
    }

    private fun auditConferenceSessionLifecycle(
        event: String,
        session: TalkbackSession,
        writer: String,
        cause: String
    ) {
        if (!isConferenceSession(session)) return
        ConferenceAuditTimelineLog.lifecycle(
            event = event,
            channelId = session.channelId,
            sessionId = session.id,
            writer = writer,
            cause = cause
        )
        if (event == "SESSION_CREATED") {
            conferenceJoinLatencyTracker.onSessionCreated(session.id)
        }
    }

    private fun maybeAuditAuthorityTransition(session: TalkbackSession, authorityReachable: Boolean) {
        if (!isConferenceSession(session) || !session.accepted || isConferenceHostSession(session)) return
        val hostModuleId = session.initiatorModuleId?.value ?: return
        val previous = lastAuthorityReachableBySession.put(session.id, authorityReachable)
        if (previous == authorityReachable) return
        val hostIceState = qosMonitor.snapshot(hostModuleId)?.iceState
        ConferenceAuditTimelineLog.authority(
            sessionId = session.id,
            channelId = session.channelId,
            hostModuleId = hostModuleId,
            reachable = authorityReachable,
            hostIceState = hostIceState,
            writer = "emitConferenceRuntimeProjection",
            cause = if (authorityReachable) "host_ice_connected" else "host_ice_lost"
        )
        maybeNotifyRecoveryReachabilityChanged(
            session,
            hostModuleId,
            if (authorityReachable) {
                RecoveryReevaluateTrigger.AUTHORITY_REACHABLE
            } else {
                RecoveryReevaluateTrigger.AUTHORITY_LOST
            }
        )
    }

    private fun conferenceRecoveryRemoteModuleIds(session: TalkbackSession): Set<String> {
        val remotes = linkedSetOf<String>()
        remotes.addAll(conferenceMemberRemoteIds(session))
        session.initiatorModuleId?.value?.let { remotes.add(it) }
        return remotes
    }

    private fun notifyConferenceRecoveryReachabilityOnLinkOrDiscovery(trigger: RecoveryReevaluateTrigger) {
        sessions.values
            .filter { isConferenceSession(it) && it.accepted }
            .forEach { session ->
                conferenceRecoveryRemoteModuleIds(session).forEach { remoteModuleId ->
                    maybeNotifyRecoveryReachabilityChanged(session, remoteModuleId, trigger)
                }
            }
    }

    private fun maybeNotifyRecoveryReachabilityForRemote(
        remoteModuleId: String,
        trigger: RecoveryReevaluateTrigger
    ) {
        sessions.values
            .filter {
                isConferenceSession(it) &&
                    it.accepted &&
                    remoteModuleId in conferenceRecoveryRemoteModuleIds(it)
            }
            .forEach { session ->
                maybeNotifyRecoveryReachabilityChanged(session, remoteModuleId, trigger)
            }
    }

    /**
     * Coordinator-owned materiality comparator (ADR-0022 R28-G).
     * Notifies recovery controller only when [RecoveryCapabilitySignature] changes.
     */
    private fun maybeNotifyRecoveryReachabilityChanged(
        session: TalkbackSession,
        remoteModuleId: String,
        trigger: RecoveryReevaluateTrigger
    ) {
        val channelId = session.channelId ?: return
        val initiatesReattach = !isConferenceHostSession(session) &&
            remoteModuleId == session.initiatorModuleId?.value
        val controlPlaneStarted = conferenceEdgeRecoveryController.isControlPlaneStarted(
            session.id,
            remoteModuleId
        )
        val snapshot = buildRecoveryEdgeReachabilitySnapshot(channelId, session, remoteModuleId)
        val signature = projectRecoveryCapabilitySignature(
            snapshot,
            initiatesReattach,
            controlPlaneStarted
        )
        val edgeKey = ConferenceEdgeKey(session.id, remoteModuleId)
        val before = lastRecoveryCapabilityByEdge[edgeKey]
        val failedResidency =
            remoteModuleId in conferenceEdgeRecoveryController.factsForSession(session.id).failedRemoteModuleIds
        val failedResidencyReevaluate = failedResidency && when (trigger) {
            RecoveryReevaluateTrigger.ICE_CHECKING,
            RecoveryReevaluateTrigger.PEER_DISCOVERED,
            RecoveryReevaluateTrigger.REMOTE_MODULE_RECOVERED,
            RecoveryReevaluateTrigger.ROUTE_CONVERGED,
            RecoveryReevaluateTrigger.AUTHORITY_REACHABLE -> true
            else -> false
        }
        val deferredWakeupMatch = conferenceEdgeRecoveryController.hasDeferredWakeupForTrigger(
            session.id,
            remoteModuleId,
            trigger
        )
        if (
            !signature.isMaterialChangeFrom(before) &&
            !failedResidencyReevaluate &&
            !deferredWakeupMatch
        ) {
            return
        }
        lastRecoveryCapabilityByEdge[edgeKey] = signature
        conferenceEdgeRecoveryController.onRecoveryReachabilityChanged(
            sessionId = session.id,
            channelId = channelId,
            remoteModuleId = remoteModuleId,
            snapshot = snapshot,
            signature = signature,
            capabilityBefore = before,
            trigger = trigger
        )
    }

    private fun isConferenceAuthorityMediaRecovering(session: TalkbackSession): Boolean {
        val modules = linkedSetOf<String>()
        modules.addAll(conferenceMemberRemoteIds(session))
        session.initiatorModuleId?.value?.let { modules.add(it) }
        return modules.any { conferenceRecoveryController.isMediaRecovering(it) }
    }

    private fun buildEdgeRecoveryEligibility(
        session: TalkbackSession,
        remoteModuleId: String
    ): EdgeRecoveryEligibility {
        val left = conferenceParticipantManager.leftMemberEndpoints(session.id)?.keys
            ?: session.leftMemberEndpoints.keys
        val localJoined = session.accepted && localModuleId.value !in left
        val remoteJoined = when {
            remoteModuleId in left -> false
            remoteModuleId == session.initiatorModuleId?.value -> session.accepted
            else -> conferenceParticipantManager.containsParticipant(session.id, remoteModuleId) ||
                remoteModuleId in conferenceMemberRemoteIds(session)
        }
        return EdgeRecoveryEligibility(
            lifecycleEstablished = session.accepted && isMeetingStartTransitionReady(session),
            localJoined = localJoined,
            remoteJoined = remoteJoined,
            conferenceTerminated = conferenceEdgeRecoveryController.isSessionCancelled(session.id)
        )
    }

    private fun resolveHostSessionIdForChannel(channelId: String, hostModuleId: String): String? {
        lastRejoinableConferenceByChannel[channelId]?.hostSessionId?.takeIf { it.isNotBlank() }?.let { return it }
        return sessions.values.firstOrNull {
            it.channelId == channelId &&
                it.type == SessionType.CONFERENCE &&
                it.initiatorModuleId?.value == hostModuleId
        }?.id
    }

    private fun notifyConferenceEdgeIceState(
        session: TalkbackSession,
        remoteModuleId: String,
        iceState: String
    ) {
        val channelId = session.channelId ?: return
        val hostId = session.initiatorModuleId?.value ?: return
        val initiatesReattach = !isConferenceHostSession(session) && remoteModuleId == hostId
        conferenceEdgeRecoveryController.onIceStateChanged(
            sessionId = session.id,
            channelId = channelId,
            remoteModuleId = remoteModuleId,
            iceState = iceState,
            eligibility = buildEdgeRecoveryEligibility(session, remoteModuleId),
            initiatesReattach = initiatesReattach
        )
        applyConferenceTransmitBarrier(session, "ice_state_changed")
        emitConferenceRuntimeProjection(session)
    }

    private fun emitConferenceRuntimeProjection(session: TalkbackSession) {
        if (!isConferenceSession(session)) return
        val conferenceSnap = conferenceSnapshot(session) ?: return
        val memberViews = conferenceSnap.memberViews
        val leftModuleIds = conferenceParticipantManager.leftMemberEndpoints(session.id)?.keys
            ?: session.leftMemberEndpoints.keys
        val projection = ConferenceParticipantProjector.project(
            ConferenceParticipantProjector.Input(
                localModuleId = localModuleId,
                localKey = session.local.key,
                sessionAccepted = session.accepted,
                roster = meshRoster(session),
                memberViews = memberViews,
                leftModuleIds = leftModuleIds.toSet()
            )
        )
        val runtime = projectConferenceRuntimeState(session, projection)
        val channelId = session.channelId
        val channelReady = channelId?.let { resolveChannelReadiness(it) }
        val uiReady = isConferenceUiReady(session)
        val isHost = isConferenceHostSession(session)
        val authorityReachable = isConferenceAuthorityReachable(session)
        maybeAuditAuthorityTransition(session, authorityReachable)
        log(
            ConferenceRuntimeProjectionLogger.format(
                sessionId = session.id,
                channelId = channelId,
                runtime = runtime,
                channelReadiness = channelReady,
                conferenceUiReady = uiReady,
                isConferenceHost = isHost,
                authorityReachable = authorityReachable,
                joinedParticipantCount = projection.joinedParticipantCount,
                pendingInviteeCount = projection.pendingInviteeCount
            )
        )
    }

    fun conferenceNetworkIndicator(): ConferenceNetworkIndicator = runOnCoordinatorSync {
        ConferenceNetworkIndicatorProjector.project(qosMonitor.all().map { it.iceState })
    }

    fun receivePathLive(sessionId: String, remoteModuleId: String): Boolean = runOnCoordinatorSync {
        receivePathLivenessObserver.receivePathLive(sessionId, remoteModuleId)
    }

    fun mediaEverLive(sessionId: String, remoteModuleId: String): Boolean = runOnCoordinatorSync {
        receivePathLivenessObserver.mediaEverLive(sessionId, remoteModuleId)
    }

    fun conferenceParticipantRecordExists(sessionId: String, moduleId: String): Boolean =
        runOnCoordinatorSync {
            conferenceParticipantManager.containsParticipant(sessionId, moduleId)
        }

    fun conferenceParticipantMedia(sessionId: String, moduleId: String): MediaState =
        runOnCoordinatorSync {
            conferenceParticipantManager.participantMedia(sessionId, moduleId)
        }

    /** Read-only; probe only. Participant view of host media reachability. */
    fun conferenceAuthorityReachable(sessionId: String): Boolean = runOnCoordinatorSync {
        sessions[sessionId]?.let { isConferenceAuthorityReachable(it) } ?: false
    }

    /** Read-only; probe only. ConferenceEdgeRecoveryController owner state. */
    fun conferenceEdgeRecoveryLineage(
        sessionId: String,
        remoteModuleId: String
    ): com.talkback.core.session.EdgeAttemptLineageRaw? = runOnCoordinatorSync {
        conferenceEdgeRecoveryController.attemptLineageObservation(sessionId, remoteModuleId)
    }

    /** Read-only; ADR-0030 per-peer fact: active recovery attempt on this edge. */
    fun conferenceEdgeRecovering(sessionId: String, remoteModuleId: String): Boolean =
        runOnCoordinatorSync {
            conferenceEdgeRecoveryController.isEdgeRecovering(sessionId, remoteModuleId)
        }

    /** Read-only; ADR-0030 per-peer fact: failed-media residency (e.g. FAILED_MEDIA_RECOVERY). */
    fun conferenceMediaUnavailable(sessionId: String, remoteModuleId: String): Boolean =
        runOnCoordinatorSync {
            conferenceEdgeRecoveryController.isMediaUnavailable(sessionId, remoteModuleId)
        }

    fun networkQualityLabel(): String = conferenceNetworkIndicator().toQualityLabel()

    fun onlineModuleCount(): Int = mergeRemoteModuleViews().size
    fun qosSummary(): String = qosMonitor.formatSummary()

    fun qosSnapshotForModule(moduleId: String): com.talkback.core.qos.QosSnapshot? =
        runOnCoordinatorSync { qosMonitor.snapshot(moduleId) }

    /**
     * Presentation semantic API: can the current floor holder's audio reach us right now.
     *
     * This is the Anti-Corruption boundary for the UI: callers learn "speaker reachable"
     * without depending on the transport implementation (ICE today, AudioBus/decoder/jitter
     * tomorrow). The Presentation layer MUST consume this instead of reading iceState.
     * Returns false when no GROUP floor owner exists; true when the local device holds it.
     */
    fun isCurrentSpeakerReachable(channelId: String): Boolean = runOnCoordinatorSync {
        val session = bestSessionForChannel(channelId) ?: return@runOnCoordinatorSync false
        if (session.type != SessionType.GROUP) return@runOnCoordinatorSync false
        val owner = session.floor.owner() ?: return@runOnCoordinatorSync false
        if (isLocalIdentity(session, owner)) return@runOnCoordinatorSync true
        isFloorHolderAudioReachable(session, owner.moduleId.value)
    }

    fun remotePlaybackEnabledForModule(moduleId: String): Boolean? = runOnCoordinatorSync {
        getMeshEngine(moduleId)?.isRemotePlaybackEnabled()
    }

    internal fun testForceRemotePlayback(moduleId: String, enabled: Boolean) = runOnCoordinatorSync {
        getMeshEngine(moduleId)?.setRemotePlaybackEnabled(enabled)
    }

    internal fun testEvictGroupMember(sessionId: String, moduleId: String) = runOnCoordinatorSync {
        val session = sessions[sessionId] ?: return@runOnCoordinatorSync
        removeGroupMember(session, moduleId)
    }

    internal fun testInvariantF1BreakCount(): Int = runOnCoordinatorSync { invariantF1BreakCount }

    /** Test-only: local authority belief for [channelId], not resolved system authority. */
    internal fun testAuthorityBeliefModuleId(channelId: String): String? = runOnCoordinatorSync {
        val session = bestSessionForChannel(channelId) ?: return@runOnCoordinatorSync null
        session.floorAuthorityModuleId?.value ?: session.initiatorModuleId?.value
    }

    internal fun testIsSessionCapturing(sessionId: String): Boolean = runOnCoordinatorSync {
        val session = sessions[sessionId] ?: return@runOnCoordinatorSync false
        sessionMediaEngines(session).any { it.isCapturing() }
    }

    internal fun testCanPublishConferenceAudio(sessionId: String): Boolean = runOnCoordinatorSync {
        val session = sessions[sessionId] ?: return@runOnCoordinatorSync false
        canPublishAudio(session)
    }

    internal fun testIsSessionPlaybackEnabled(sessionId: String): Boolean = runOnCoordinatorSync {
        lastPlaybackEnabledBySession[sessionId] ?: false
    }

    internal fun testRefreshConferenceReceivePlayback(
        sessionId: String,
        reason: String = "test_refresh"
    ) = runOnCoordinatorSync {
        val session = sessions[sessionId] ?: return@runOnCoordinatorSync
        refreshConferenceReceivePlayback(session, reason)
    }

    internal fun testSetSessionPlaybackEnabled(
        sessionId: String,
        enabled: Boolean,
        reason: String
    ) = runOnCoordinatorSync {
        val session = sessions[sessionId] ?: return@runOnCoordinatorSync
        setPlaybackEnabled(session, enabled, reason)
    }

    internal fun testConferenceBarrierSnapshot(sessionId: String) = runOnCoordinatorSync {
        val session = sessions[sessionId] ?: return@runOnCoordinatorSync null
        conferenceBarrierSnapshot(session)
    }

    internal fun testResolverLocalKey(sessionId: String): String? = runOnCoordinatorSync {
        val session = sessions[sessionId] ?: return@runOnCoordinatorSync null
        IdentityResolver.localKey(session, localModuleId.value)
    }

    internal fun testFloorRequestVersion(sessionId: String): Long? = runOnCoordinatorSync {
        val session = sessions[sessionId] ?: return@runOnCoordinatorSync null
        session.floorOwner.tokens.lookup(
            FloorOperationIdentity(sessionId, groupLocalIdentity(session).key)
        )?.version
    }

    internal fun testInjectFloorGranted(
        sessionId: String,
        authority: EndpointAddress,
        grantee: EndpointAddress,
        floorVersion: Long,
        floorEpoch: Long = 0L
    ) = runOnCoordinatorSync {
        val payload = FloorPayload.forRequest(
            grantee,
            floorVersion,
            floorEpoch,
            EndpointPriority.NORMAL
        ).encode()
        handleFloorGranted(
            SignalEnvelope(
                type = SignalType.FLOOR_GRANTED,
                from = authority,
                to = grantee,
                sessionId = sessionId,
                timestampMs = System.currentTimeMillis(),
                payload = payload,
                nonce = "",
                signature = ""
            )
        )
    }

    internal fun testArmFloorRequest(sessionId: String): Long = runOnCoordinatorSync {
        val session = sessions[sessionId] ?: return@runOnCoordinatorSync -1L
        if (!session.type.usesFloorControl()) return@runOnCoordinatorSync -1L
        val reqState = session.ptt.onEvent(PttEvent.Press)
        if (reqState != PttState.REQUEST_FLOOR) return@runOnCoordinatorSync -1L
        val version = session.floor.nextRequestVersion()
        session.floorOwner.createRequestToken(session.id, groupLocalIdentity(session), version)
        version
    }

    internal fun testCancelFloorRequest(sessionId: String) = runOnCoordinatorSync {
        val session = sessions[sessionId] ?: return@runOnCoordinatorSync
        if (!session.type.usesFloorControl()) return@runOnCoordinatorSync
        if (session.ptt.state != PttState.REQUEST_FLOOR) return@runOnCoordinatorSync
        session.floorOwner.invalidateRequestToken(
            FloorOperationIdentity(session.id, groupLocalIdentity(session).key)
        )
        session.ptt.onEvent(PttEvent.Release)
    }

    private fun isLocalUplinkGranted(): Boolean =
        sessions.values.any { session -> sessionMediaEngines(session).any { it.isCapturing() } }

    /** Test-only: refresh playback gate from ICE reachability without tearing down media links. */
    internal fun testRefreshIceReachability(remoteModuleId: String, state: String) = runOnCoordinatorSync {
        qosMonitor.updateIceState(remoteModuleId, state)
        sessions.values
            .filter { it.type == SessionType.GROUP && it.accepted }
            .forEach { updateSessionReceivePlayback(it, "test_ice_$state") }
    }

    /** Test-only: force ANCHOR relay topology when diagnostic threshold keeps sessions on MESH. */
    internal fun testForceGroupAnchorTopology(channelId: String) = runOnCoordinatorSync {
        sessions.values
            .filter { it.type == SessionType.GROUP && it.channelId == channelId && it.accepted }
            .forEach { session ->
                session.mediaTopology = GroupMediaTopology.ANCHOR
                if (session.anchorModuleId == null) {
                    session.anchorModuleId = electAnchorRoles(memberModuleIds(session))?.primary
                        ?: localModuleId
                }
                electAnchorRoles(memberModuleIds(session))?.let { applyAnchorRolesToSession(session, it) }
                updateSessionReceivePlayback(session, "test_force_anchor")
                syncProgramRelay(session)
            }
    }

    /** Test-only: align authority digest so governance PTT gate sees Authority READY on followers. */
    internal fun testSeedAuthorityDigestForChannel(channelId: String) = runOnCoordinatorSync {
        sessions.values
            .firstOrNull { it.type == SessionType.GROUP && it.channelId == channelId && it.accepted }
            ?.let { session ->
                lastSeenAuthorityDigestByChannel[channelId] = TopologyDigest.fromSession(session)
            }
    }

    /** Test-only: seed a duplicate local GROUP session that wins session-authority over an incoming invite. */
    internal fun testSeedDuplicateGroupSession(
        channelId: String,
        sessionId: String,
        initiatorModuleId: String,
        connectedPeerModuleIds: List<String> = emptyList()
    ) = runOnCoordinatorSync {
        val local = localEndpointAddress() ?: return@runOnCoordinatorSync
        val peers = connectedPeerModuleIds.map { EndpointAddress(ModuleId(it), EndpointId("E01")) }
        channelManager.replaceMembers(channelId, listOf(local.moduleId) + peers.map { it.moduleId })
        val session = TalkbackSession(sessionId, SessionType.GROUP, local, channelId)
        freezeChannelMemberSnapshot(session)
        session.localInitiated = true
        session.initiatorModuleId = ModuleId(initiatorModuleId)
        session.floorAuthorityModuleId = ModuleId(initiatorModuleId)
        session.groupMembers = listOf(local) + peers
        session.memberModules.add(local.moduleId)
        peers.forEach { remote ->
            session.memberModules.add(remote.moduleId)
            discoveredByModule[remote.moduleId.value]?.let { peer ->
                session.remotePeersByModule[remote.moduleId.value] = peer
            }
        }
        session.accepted = true
        session.syncParticipantsFromMembers(local.moduleId)
        applyGroupTopology(session, session.groupMembers.size)
        connectedPeerModuleIds.forEach { peerId ->
            val engine = acquireMeshEngine(session, peerId, forReconnect = false)
            wireIceCallback(session, peerId, engine)
            markMeshLinkCompleted(session,peerId)
            qosMonitor.updateIceState(peerId, "CONNECTED")
        }
        sessions[sessionId] = session
        enterChannelMode(channelId, ChannelMode.GROUP_PTT, initiatorModuleId)
        log("${sessionTag(session)} Test duplicate GROUP seeded ch=$channelId initiator=$initiatorModuleId")
    }

    internal fun hasGroupMediaEngine(remoteModuleId: String): Boolean =
        runOnCoordinatorSync { mediaRegistry.getGroup(remoteModuleId) != null }

    internal fun mediaSessionReuseCount(): Int =
        runOnCoordinatorSync { mediaRegistry.mediaSessionReuseCount() }

    internal fun conferenceMediaGeneration(remoteModuleId: String): Long? =
        runOnCoordinatorSync {
            mediaRegistry.meshSessionState(remoteModuleId)
                ?.takeIf { it.scope == MediaBearerScope.CONFERENCE }
                ?.generation
        }

    internal fun testInjectGroupInvite(
        callerModuleId: String,
        channelId: String,
        sessionId: String,
        initiatorModuleId: String,
        fromPeer: PeerTarget,
        memberModuleIds: List<String> = listOf("M01", "M02", "M03"),
        sdp: String = "v=0"
    ): Boolean = runOnCoordinatorSync {
        val local = localEndpointAddress() ?: return@runOnCoordinatorSync false
        val caller = EndpointAddress(ModuleId(callerModuleId), EndpointId("E01"))
        if (caller.moduleId == local.moduleId) return@runOnCoordinatorSync false
        rememberSignalPeer(callerModuleId, fromPeer)
        val members = memberModuleIds.map { EndpointAddress(ModuleId(it), EndpointId("E01")).key }
        val payload = GroupSessionPayload(
            sdp = sdp,
            channelId = channelId,
            members = members,
            initiatorModuleId = initiatorModuleId,
            floorAuthorityModuleId = initiatorModuleId
        ).encode()
        val signal = buildSignedEnvelope(
            SignalType.GROUP_INVITE,
            caller,
            local,
            sessionId,
            payload
        )
        handleGroupInvite(signal, fromPeer)
        true
    }

    internal fun testRunConferenceHealthCleanup(channelId: String) = runOnCoordinatorSync {
        sessions.values
            .firstOrNull { it.type == SessionType.CONFERENCE && it.channelId == channelId && it.accepted }
            ?.let { cleanupUnhealthyConferenceSession(it) }
    }

    internal fun testEdgeRecoveryFacts(sessionId: String): EdgeRecoveryFacts = runOnCoordinatorSync {
        conferenceEdgeRecoveryController.factsForSession(sessionId)
    }

    internal fun testEdgeObligationOpen(sessionId: String, remoteModuleId: String): Boolean =
        runOnCoordinatorSync {
            conferenceEdgeRecoveryController.edgeObligationOpen(sessionId, remoteModuleId)
        }

    internal fun testEdgeObligationClosed(sessionId: String, remoteModuleId: String): Boolean =
        runOnCoordinatorSync {
            conferenceEdgeRecoveryController.edgeObligationClosed(sessionId, remoteModuleId)
        }

    internal fun testObligationCloseReason(
        sessionId: String,
        remoteModuleId: String
    ): ObligationCloseReason? = runOnCoordinatorSync {
        conferenceEdgeRecoveryController.obligationCloseReason(sessionId, remoteModuleId)
    }

    internal fun testObligationDeadlineAt(sessionId: String, remoteModuleId: String): Long? =
        runOnCoordinatorSync {
            conferenceEdgeRecoveryController.obligationDeadlineAt(sessionId, remoteModuleId)
        }

    internal fun testIsEdgeRecovering(sessionId: String, remoteModuleId: String): Boolean =
        runOnCoordinatorSync {
            conferenceEdgeRecoveryController.isEdgeRecovering(sessionId, remoteModuleId)
        }

    internal fun testConferenceMembershipEpoch(sessionId: String): Long = runOnCoordinatorSync {
        sessions[sessionId]?.rosterEpoch ?: 0L
    }

    /** Test seam for #82 / R29-F: exercise host recovery reinvite gate without waiting for HELLO stale. */
    internal fun testNotifyRemoteModuleRecovered(moduleId: String) = runOnCoordinatorSync {
        onRemoteModuleRecovered(moduleId)
    }

    fun remoteModuleStates(): List<RemoteModuleState> = mergeRemoteModuleViews()

    fun isRemoteModuleReachable(moduleId: String): Boolean = runOnCoordinatorSync {
        val state = mergeRemoteModuleViews().firstOrNull { it.presence.moduleId.value == moduleId }
        isModuleReachable(moduleId, state)
    }

    /** Discovery / static / signal address book — for mesh dial targets, not UI liveness. */
    fun isRemoteModuleDialable(moduleId: String): Boolean = runOnCoordinatorSync {
        isModuleDialable(moduleId)
    }

    /** Active conference authority = session initiator, else channel mode owner, else pending invite host. */
    fun conferenceAuthorityModuleId(channelId: String): String? = runOnCoordinatorSync {
        resolveConferenceAuthorityModuleId(channelId)
    }

    fun shouldLocalInitiateConference(channelId: String): Boolean = runOnCoordinatorSync {
        lastRejoinableConferenceByChannel[channelId]
            ?.takeUnless { it.isExpired(ttlMs = config.rejoinableConferenceTtlMs) }
            ?.let { record ->
                if (record.hostModuleId != localModuleId.value) {
                    return@runOnCoordinatorSync false
                }
            }
        val authority = resolveConferenceAuthorityModuleId(channelId)
        authority == null || authority == localModuleId.value
    }

    fun primaryEndpointIdForModule(moduleId: String): String? = runOnCoordinatorSync {
        val state = mergeRemoteModuleViews().firstOrNull { it.presence.moduleId.value == moduleId }
        resolvePrimaryEndpointId(moduleId, state)
    }

    fun updateStaticPeers(entries: List<StaticPeerEntry>) {
        staticPeers.clear()
        staticPeers.addAll(entries)
        entries.forEach { entry ->
            if (entry.moduleId.value == localModuleId.value) return@forEach
            discoveredByModule[entry.moduleId.value] = PeerTarget(entry.host, entry.port)
        }
    }

    fun resetDiscovery() {
        (discoveryService as? GossipDiscoveryControl)?.resetAndSweep()
    }

    fun peerDisplayRoster(): List<PeerDisplayRow> = runOnCoordinatorSync {
        buildContactsProjectionInternal().map { PeerDisplayRow(it.endpointKey, it.moduleOnline) }
    }

    internal fun buildContactsProjection(): List<ContactEndpointRow> =
        runOnCoordinatorSync { buildContactsProjectionInternal() }

    private fun buildContactsProjectionInternal(): List<ContactEndpointRow> {
        val moduleStates = stateSync.allModules().associateBy { it.presence.moduleId.value }
        return ContactsProjection.project(
            localModuleId = localModuleId.value,
            callableModuleIds = callableModuleGate.verifiedModules(),
            moduleStates = moduleStates,
            isModuleReachable = ::isModuleReachable
        )
    }

    private fun applyDiscoveryCallableGate(moduleId: String) {
        val source = (discoveryService as? CompositeModuleDiscoveryService)?.discoverySource(moduleId)
        when (source) {
            "gossip" -> callableModuleGate.markVerified(moduleId)
            "static", "nsd" -> if (!callableModuleGate.isVerified(moduleId)) {
                log("DISCOVERY_UNVERIFIED module=$moduleId source=$source")
            }
            null -> if (!callableModuleGate.isVerified(moduleId)) {
                log("DISCOVERY_UNVERIFIED module=$moduleId source=unknown")
            }
        }
    }

    /** stateSync + live discovery + static config; partial mDNS on some OEMs only fills one of the two. */
    private fun mergeRemoteModuleViews(): List<RemoteModuleState> {
        val localKey = localModuleId.value
        val merged = LinkedHashMap<String, RemoteModuleState>()
        stateSync.allModules().forEach { state ->
            val id = state.presence.moduleId.value
            if (id != localKey) merged[id] = state
        }
        discoveredByModule.forEach { (moduleId, peer) ->
            if (moduleId == localKey) return@forEach
            val existing = merged[moduleId]
            val endpointTally = existing?.endpoints?.count { it.online } ?: 0
            val activityMs = moduleLastHelloMs[moduleId]
                ?: existing?.lastHelloMs?.takeIf { it > 0L }
                ?: 0L
            val presence = ModulePresence(
                moduleId = ModuleId(moduleId),
                host = peer.host,
                port = peer.port,
                endpointCount = maxOf(existing?.presence?.endpointCount ?: 0, endpointTally, 1),
                lastSeenMs = activityMs
            )
            merged[moduleId] = existing?.copy(presence = presence)
                ?: RemoteModuleState(
                    presence = presence,
                    endpoints = emptyList(),
                    lastHelloMs = 0L
                )
        }
        staticPeers.forEach { entry ->
            val moduleId = entry.moduleId.value
            if (moduleId == localKey) return@forEach
            if (merged.containsKey(moduleId)) return@forEach
            val activityMs = moduleLastHelloMs[moduleId] ?: 0L
            merged[moduleId] = RemoteModuleState(
                presence = ModulePresence(
                    moduleId = entry.moduleId,
                    host = entry.host,
                    port = entry.port,
                    endpointCount = 0,
                    lastSeenMs = activityMs
                ),
                endpoints = emptyList(),
                lastHelloMs = 0L
            )
        }
        return merged.values.sortedBy { it.presence.moduleId.value }
    }

    private fun touchModuleHello(moduleId: String, atMs: Long = System.currentTimeMillis()) {
        if (moduleId == localModuleId.value) return
        moduleLastHelloMs[moduleId] = atMs
    }

    private fun isModuleReachable(moduleId: String, state: RemoteModuleState?): Boolean {
        val last = moduleLastHelloMs[moduleId]
            ?: state?.lastHelloMs?.takeIf { it > 0L }
            ?: return false
        return System.currentTimeMillis() - last <= config.moduleStaleMs
    }

    private fun isModuleDialable(moduleId: String): Boolean {
        if (moduleId == localModuleId.value) return false
        if (discoveredByModule.containsKey(moduleId)) return true
        if (staticPeers.any { it.moduleId.value == moduleId }) return true
        if (signalPeersByModule.containsKey(moduleId)) return true
        val presence = stateSync.get(moduleId)?.presence
        return presence != null && presence.host.isNotBlank() && presence.port > 0
    }

    private fun resolveConferenceAuthorityModuleId(channelId: String): String? {
        lastRejoinableConferenceByChannel[channelId]
            ?.takeUnless { it.isExpired(ttlMs = config.rejoinableConferenceTtlMs) }
            ?.hostModuleId
            ?.let { return it }
        sessions.values.firstOrNull {
            it.channelId == channelId &&
                it.type == SessionType.CONFERENCE &&
                it.accepted &&
                conferenceMemberRemoteIds(it).isNotEmpty()
        }?.initiatorModuleId?.value?.let { return it }
        pendingConferenceInvitesByChannel[channelId]?.signal?.from?.moduleId?.value?.let { return it }
        // FSM modeOwner as last resort (may be stale; cleared by leaveChannelMode).
        val fsm = channelModeFsm(channelId)
        if (fsm.isConferenceMode()) {
            fsm.modeOwnerModuleId?.let { return it }
        }
        return null
    }

    private fun resolvePrimaryEndpointId(moduleId: String, state: RemoteModuleState?): String? {
        state?.endpoints?.let { endpoints ->
            endpoints.filter { it.online }.minByOrNull { it.endpointId }?.endpointId
                ?: endpoints.minByOrNull { it.endpointId }?.endpointId
        }?.let { return it }
        return lastKnownPrimaryEndpointByModule[moduleId]
    }

    private fun endpointKey(moduleId: String, endpointId: String?): String =
        if (endpointId != null) "$moduleId-$endpointId" else "$moduleId-—"

    private fun rememberPrimaryEndpoint(moduleId: String, endpoints: List<RemoteEndpointInfo>) {
        val id = endpoints.filter { it.online }.minByOrNull { it.endpointId }?.endpointId
            ?: endpoints.minByOrNull { it.endpointId }?.endpointId
        if (id != null) {
            lastKnownPrimaryEndpointByModule[moduleId] = id
        }
    }

    private fun rememberSignalPeer(moduleId: String, peer: PeerTarget) {
        if (moduleId == localModuleId.value) return
        signalPeersByModule[moduleId] = peer
        discoveredByModule[moduleId] = peer
        maybeNotifyDialablePeersChanged()
    }

    private fun helloTargets(): Map<String, PeerTarget> {
        val targets = LinkedHashMap<String, PeerTarget>()
        staticPeers.forEach { entry ->
            if (entry.moduleId.value != localModuleId.value) {
                targets[entry.moduleId.value] = PeerTarget(entry.host, entry.port)
            }
        }
        discoveredByModule.forEach { (moduleId, peer) -> targets[moduleId] = peer }
        signalPeersByModule.forEach { (moduleId, peer) -> targets[moduleId] = peer }
        stateSync.allModules().forEach { state ->
            val host = state.presence.host
            val port = state.presence.port
            if (host.isNotBlank() && port > 0) {
                targets.putIfAbsent(state.presence.moduleId.value, PeerTarget(host, port))
            }
        }
        return targets
    }

    fun channels() = channelManager.all()

    fun channelMemberModuleIds(channelId: String): Set<String> = runOnCoordinatorSync {
        ChannelMembershipSnapshot.moduleIds(channelManager.members(channelId))
    }

    fun configureChannelMembership(channelId: String, moduleIds: List<String>) = runOnCoordinatorSync {
        channelManager.replaceMembers(channelId, moduleIds.map { ModuleId(it) }.toSet())
    }

    private fun freezeChannelMemberSnapshot(session: TalkbackSession) {
        val channelId = session.channelId ?: return
        session.channelMemberSnapshot = ChannelMembershipSnapshot.moduleIds(
            ChannelMembershipSnapshot.capture(channelManager, channelId)
        )
    }

    /** Make room for an outgoing private call (e.g. release active group PTT session). */
    private fun prepareForUnicastOutgoing(): UnicastOutgoingPrep {
        cleanupUnhealthySessions()
        sessions.values
            .filter { it.type == SessionType.UNICAST }
            .forEach { session ->
                val stale = !session.accepted || !sessionMediaHealthy(session)
                if (stale) {
                    log("${sessionTag(session)} Clearing stale unicast before outgoing call")
                    hangupInternal(session.id)
                }
            }
        sessions.values
            .filter { it.type == SessionType.GROUP || it.type == SessionType.CONFERENCE }
            .forEach { session ->
                suspendSessionForUnicast(session)
            }
        sessions.values
            .filter { it.type == SessionType.UNICAST }
            .forEach { session ->
                log("${sessionTag(session)} Ending prior unicast for new outgoing call")
                hangupInternal(session.id)
            }
        if (activeSessionCount() != 0) {
            val blocking = sessions.values
                .filter { countsTowardActiveLimit(it) }
                .joinToString { "${it.type.name}/${it.traceId}|${it.id}" }
            log("Outgoing call blocked: active sessions=$blocking")
            return UnicastOutgoingPrep.Blocked("BUSY_ACTIVE_SESSION")
        }
        return when (val outcome = foregroundAdmission.admitUnicastOutgoing()) {
            is ForegroundActivityAdmission.Outcome.Ready -> UnicastOutgoingPrep.Ready
            is ForegroundActivityAdmission.Outcome.Blocked -> {
                log("Outgoing call blocked: ${outcome.reason}")
                UnicastOutgoingPrep.Blocked(outcome.reason)
            }
        }
    }

    private fun resolvePeerForModule(moduleId: String): PeerTarget? {
        discoveredByModule[moduleId]?.let { return it }
        signalPeersByModule[moduleId]?.let { return it }
        staticPeers.firstOrNull { it.moduleId.value == moduleId }?.let { entry ->
            return PeerTarget(entry.host, entry.port)
        }
        stateSync.get(moduleId)?.presence?.let { presence ->
            if (presence.host.isNotBlank() && presence.port > 0) {
                return PeerTarget(presence.host, presence.port)
            }
        }
        return null
    }

    private fun placeCallInternal(
        local: EndpointAddress,
        remote: EndpointAddress,
        channelId: String?,
        type: SessionType
    ): String {
        if (activeSessionCount() >= config.maxActiveSessions) {
            error("Active session limit reached: ${config.maxActiveSessions}")
        }
        val peer = resolvePeerForModule(remote.moduleId.value)
            ?: error("Remote module not discovered: ${remote.moduleId.value}")
        val sessionId = UUID.randomUUID().toString()
        val session = TalkbackSession(sessionId, type, local, channelId)
        channelId?.let { session.sessionOriginChannelId = it }
        session.remote = remote
        session.remotePeer = peer
        session.memberModules.add(remote.moduleId)
        session.remotePeersByModule[remote.moduleId.value] = peer
        sessions[sessionId] = session
        if (type == SessionType.UNICAST) {
            session.localInitiated = true
            session.unicastPhase = UnicastCallPhase.CONNECTING
            session.touch()
        }

        val engine = unicastEngine(session)
        wireIceCallback(session, remote.moduleId.value, engine)
        val offer = engine.createOffer()
        drainPendingIce(sessionId, remote.moduleId.value, engine)
        sendSignal(
            peer,
            buildSignedEnvelope(SignalType.CALL_INVITE, local, remote, sessionId, offer)
        )
        reDialByRemoteModule[remote.moduleId.value] = ReDialRecord(local, remote, channelId)
        log("[${session.traceId}] Outgoing call ${local.key} -> ${remote.key}")
        if (type == SessionType.UNICAST) {
            foregroundAdmission.onUnicastStarted(
                sessionId = sessionId,
                acting = local,
                requestedBy = local,
                suspendedSessions = sessions.values.filter {
                    it.id != sessionId &&
                        (it.type == SessionType.GROUP || it.type == SessionType.CONFERENCE)
                }
            )
        }
        return sessionId
    }

    private fun handleSignal(signal: SignalEnvelope, fromPeer: PeerTarget) {
        if (!verifyIncomingSignal(signal)) return
        callableModuleGate.markVerified(signal.from.moduleId.value)
        rememberSignalPeer(signal.from.moduleId.value, fromPeer)
        touchSession(signal.sessionId)
        when (signal.type) {
            SignalType.DISCOVERY_PROBE -> {
                (discoveryService as? DiscoverySignalHandler)?.onDiscoveryProbe(signal, fromPeer)
            }
            SignalType.DISCOVERY_ANNOUNCE -> {
                (discoveryService as? DiscoverySignalHandler)?.onDiscoveryAnnounce(signal, fromPeer)
            }
            SignalType.HELLO -> handleHello(signal, fromPeer)
            SignalType.CALL_INVITE -> handleCallInvite(signal, fromPeer)
            SignalType.CALL_ACCEPT -> handleCallAccept(signal)
            SignalType.CALL_REJECT -> handleCallReject(signal)
            SignalType.GROUP_INVITE -> handleGroupInvite(signal, fromPeer)
            SignalType.GROUP_ACCEPT -> handleGroupAccept(signal, fromPeer)
            SignalType.GROUP_JOIN -> handleGroupJoin(signal, fromPeer)
            SignalType.GROUP_LEAVE -> handleGroupLeave(signal)
            SignalType.GROUP_RESYNC_REQUEST -> handleGroupResyncRequest(signal, fromPeer)
            SignalType.CONFERENCE_REJOIN -> handleConferenceRejoin(signal, fromPeer)
            SignalType.WEBRTC_ICE -> handleIce(signal)
            SignalType.HEARTBEAT -> Unit
            SignalType.FLOOR_REQUEST -> {
                logFloorRequestRecv(signal, fromPeer)
                handleFloorRequest(signal)
            }
            SignalType.FLOOR_REQUEST_CANCEL -> handleFloorRequestCancel(signal)
            SignalType.FLOOR_GRANTED -> handleFloorGranted(signal)
            SignalType.FLOOR_DENY -> handleFloorDeny(signal)
            SignalType.FLOOR_PREEMPTED -> handleFloorPreempted(signal)
            SignalType.FLOOR_RELEASE -> handleFloorRelease(signal)
            SignalType.HANGUP -> handleHangup(signal)
            else -> Unit
        }
    }

    private fun handleHello(signal: SignalEnvelope, fromPeer: PeerTarget) {
        val payload = HelloPayload.decode(signal.payload) ?: return
        val now = System.currentTimeMillis()
        val wasStale = !isModuleReachable(payload.moduleId, stateSync.get(payload.moduleId))
        rememberSignalPeer(payload.moduleId, fromPeer)
        touchModuleHello(payload.moduleId, now)
        rememberPrimaryEndpoint(payload.moduleId, payload.endpoints)
        remoteHealthByModule[payload.moduleId] = AnchorHealthSnapshot(
            charging = payload.charging,
            batteryPercent = payload.batteryPercent.coerceIn(0, 100),
            onlineSinceMs = payload.onlineSinceMs,
            updatedMs = now
        )
        stateSync.updateEndpoints(payload.moduleId, payload.endpoints, helloMs = now)
        stateSync.get(payload.moduleId)?.let { existing ->
            stateSync.updatePresence(
                listOf(
                    existing.presence.copy(
                        host = fromPeer.host,
                        port = fromPeer.port,
                        endpointCount = maxOf(
                            existing.presence.endpointCount,
                            payload.endpoints.count { it.online }
                        ),
                        lastSeenMs = now
                    )
                )
            )
        }
        log(
            "HELLO from ${payload.moduleId} endpoints=${payload.endpoints.count { it.online }} " +
                "battery=${payload.batteryPercent}% charging=${payload.charging}"
        )
        resolveSplitBrainFromHello(payload, now)
        sessions.values
            .filter { it.type == SessionType.GROUP && it.accepted && it.channelId == payload.channelId }
            .forEach { applyCanonicalEndpointBindingFromHello(it, payload) }
        sessions.values
            .filter { it.type == SessionType.GROUP && it.accepted && it.channelId == payload.channelId }
            .forEach {
                reconcileGroupMembership(it, "hello_${payload.moduleId}")
                onGroupConvergenceBoundary(it)
            }
        assertCanonicalConsistencyFromHello(payload)
        recordAuthorityDigestFromHello(payload)
        recordAuthorityFloorSnapshotFromHello(payload)
        maybeRequestGroupResyncFromHello(payload)
        ensureCanonicalMemberPresent(payload, fromPeer)
        applyAuthorityFloorSnapshotFromHello(payload)
        if (wasStale) {
            onRemoteModuleRecovered(payload.moduleId)
        }
    }

    private fun applyAuthorityFloorSnapshotFromHello(payload: HelloPayload) {
        val digest = payload.floorSnapshot ?: return
        val channelId = payload.channelId ?: return
        sessions.values
            .filter { it.type == SessionType.GROUP && it.accepted && it.channelId == channelId }
            .forEach { session ->
                val localEpochBefore = session.floor.epoch()
                val result = GroupFloorController.applyAuthorityFloorSnapshot(
                    session = session,
                    authorityModuleId = payload.moduleId,
                    digest = digest,
                    onOwnerChanged = { updateSessionReceivePlayback(session) }
                )
                val localEpochAfter = session.floor.epoch()
                val snapshotPhase = when (result) {
                    SnapshotResult.DEFERRED -> "HELLO_SNAPSHOT_DEFERRED"
                    SnapshotResult.IGNORED_OLD_EPOCH,
                    SnapshotResult.UNCHANGED -> "HELLO_SNAPSHOT_IGNORED"
                    SnapshotResult.UPDATED,
                    SnapshotResult.OWNER_CHANGED -> "HELLO_SNAPSHOT_APPLIED"
                }
                if (result != SnapshotResult.UNCHANGED) {
                    FloorTrace.helloFloorSnapshot(
                        snapshotPhase,
                        session.id,
                        channelId,
                        payload.moduleId,
                        digest.epoch,
                        digest.version,
                        localEpochBefore,
                        localEpochAfter,
                        result.name
                    )
                }
                when (result) {
                    SnapshotResult.DEFERRED -> log(
                        "SNAPSHOT_DEFER ${sessionTag(session)} sid=${session.id} " +
                            "from=${payload.moduleId} ownerKey=${digest.ownerKey} " +
                            "epoch=${digest.epoch} version=${digest.version} " +
                            "reason=membership_not_converged"
                    )
                    SnapshotResult.UNCHANGED,
                    SnapshotResult.IGNORED_OLD_EPOCH -> Unit
                    SnapshotResult.UPDATED,
                    SnapshotResult.OWNER_CHANGED -> {
                        log(
                            "FLOOR_SNAPSHOT_APPLIED ${sessionTag(session)} sid=${session.id} " +
                                "from=${payload.moduleId} result=$result " +
                                "epoch=${digest.epoch} version=${digest.version} " +
                                "owner=${digest.ownerKey ?: "null"} " +
                                "localEpoch=${session.floor.epoch()} " +
                                "localOwner=${session.floor.owner()?.key ?: "null"}"
                        )
                        if (isLocalFloorHolder(session)) {
                            onGrantAppliedForMetrics(session)
                            onLocalProtocolFloorAcquired(session)
                        } else if (session.floor.owner() != null && !isLocalFloorHolder(session)) {
                            acquireReleaseWatchdog.onFloorLost(session.id)
                            if (session.ptt.state == PttState.TALK || session.pendingTransmit) {
                                session.ptt.onEvent(PttEvent.Release)
                                session.pendingTransmit = false
                                stopSessionCapture(session)
                            }
                        }
                    }
                }
            }
    }

    private fun handleCallInvite(signal: SignalEnvelope, fromPeer: PeerTarget) {
        val caller = signal.from
        val callee = signal.to ?: return
        if (!prepareForIncomingUnicast(signal)) {
            sendSignal(
                fromPeer,
                buildSignedEnvelope(SignalType.CALL_REJECT, callee, caller, signal.sessionId, "BUSY")
            )
            return
        }
        val session = TalkbackSession(signal.sessionId, SessionType.UNICAST, callee, null)
        session.remote = caller
        session.remotePeer = fromPeer
        session.memberModules.add(caller.moduleId)
        session.remotePeersByModule[caller.moduleId.value] = fromPeer
        sessions[signal.sessionId] = session
        foregroundAdmission.onUnicastStarted(
            sessionId = signal.sessionId,
            acting = callee,
            requestedBy = callee,
            suspendedSessions = sessions.values.filter {
                it.id != signal.sessionId &&
                    (it.type == SessionType.GROUP || it.type == SessionType.CONFERENCE)
            }
        )
        log("[${session.traceId}] Incoming call ${caller.key} -> ${callee.key}")
        if (config.autoAcceptIncoming) {
            acceptInviteWithSdp(signal.sessionId, fromPeer, callee, caller, signal.payload)
        } else {
            session.pendingRemoteOfferSdp = signal.payload
            session.unicastPhase = UnicastCallPhase.RINGING
            session.touch()
        }
    }

    private fun acceptInviteWithSdp(
        sessionId: String,
        fromPeer: PeerTarget,
        local: EndpointAddress,
        remote: EndpointAddress,
        offerSdp: String
    ) {
        val session = sessions[sessionId] ?: return
        val engine = unicastEngine(session)
        wireIceCallback(session, remote.moduleId.value, engine)
        session.accepted = true
        val answer = engine.applyRemoteOffer(offerSdp, politeForInviteAnswer())
        drainPendingIce(session.id, remote.moduleId.value, engine)
        session.pendingRemoteOfferSdp = null
        session.unicastPhase = UnicastCallPhase.CONNECTING
        sendSignal(
            fromPeer,
            buildSignedEnvelope(SignalType.CALL_ACCEPT, local, remote, sessionId, answer)
        )
        ensureUnicastDuplex(session)
        updateSessionReceivePlayback(session)
        log("${sessionTag(session)} Call accepted")
    }

    private fun acceptInvite(
        signal: SignalEnvelope,
        fromPeer: PeerTarget,
        local: EndpointAddress,
        remote: EndpointAddress
    ) {
        acceptInviteWithSdp(signal.sessionId, fromPeer, local, remote, signal.payload)
    }

    private fun handleCallAccept(signal: SignalEnvelope) {
        sessions[signal.sessionId]?.let { it.accepted = true }
        val session = sessions[signal.sessionId] ?: return
        val remote = signal.from
        val engine = unicastEngine(session)
        engine.applyRemoteAnswer(signal.payload, politeForInviteAnswer())
        drainPendingIce(session.id, remote.moduleId.value, engine)
        if (session.type == SessionType.UNICAST) {
            session.unicastPhase = UnicastCallPhase.CONNECTING
            ensureUnicastDuplex(session)
            updateSessionReceivePlayback(session)
        }
        log("${sessionTag(session)} Call accepted (remote)")
    }

    private fun handleCallReject(signal: SignalEnvelope) {
        val session = sessions[signal.sessionId]
        if (session == null) {
            if (signal.payload == "MEETING_ENDED") {
                val hostSessionId = signal.sessionId
                val channelIds = lastRejoinableConferenceByChannel.entries
                    .filter { it.value.hostSessionId == hostSessionId }
                    .map { it.key }
                    .toMutableSet()
                pendingRejoinByChannel.entries
                    .filter { it.value == hostSessionId }
                    .forEach { channelIds.add(it.key) }
                channelIds.forEach { channelId ->
                    releaseConferenceRuntimeAfterRemoteTermination(channelId, "remote_meeting_ended")
                }
                log("Conference terminated remotely session=$hostSessionId channels=${channelIds.joinToString()}")
            } else {
                parseBusyCanonical(signal.payload)?.let { canonical ->
                    if (tryYieldToCanonicalGroupFromBusy(signal.sessionId, canonical)) return
                }
                log("Call rejected unknown session=${signal.sessionId} reason=${signal.payload}")
            }
            return
        }
        val rejectorModuleId = signal.from.moduleId.value
        if (session.type.isMeshSession()) {
            if (signal.payload == "MEETING_ENDED" && session.type == SessionType.CONFERENCE) {
                val channelId = session.channelId
                hangupInternal(session.id)
                channelId?.let {
                    releaseConferenceRuntimeAfterRemoteTermination(it, "remote_meeting_ended")
                }
                log("[${session.traceId}] Conference terminated remotely reason=MEETING_ENDED")
                return
            }
            parseBusyCanonical(signal.payload)?.let { canonical ->
                tryYieldToCanonicalGroupFromBusy(signal.sessionId, canonical)
            }
            reDialByRemoteModule.remove(rejectorModuleId)
            if (signal.payload == "DECLINED" || signal.payload == "EXPIRED" || isBusyRejectPayload(signal.payload)) {
                val inviteState = when (signal.payload) {
                    "DECLINED" -> InviteState.DECLINED
                    "EXPIRED" -> InviteState.EXPIRED
                    else -> InviteState.NONE
                }
                if (!evictMeshInvitee(session, rejectorModuleId, inviteState, signal.payload)) {
                    return
                }
            }
            val connectedPeerIds = connectedMeshPeerIds(session)
            val noConnectedRemotes = connectedPeerIds.isEmpty()
            if (session.initiatorModuleId == localModuleId && noConnectedRemotes) {
                if (session.type == SessionType.CONFERENCE && conferenceSessionExists(session)) {
                    log(
                        "[${session.traceId}] Mesh invite rejected by $rejectorModuleId " +
                            "reason=${signal.payload} (host-owned conference kept)"
                    )
                    if (isBusyRejectPayload(signal.payload)) {
                        val remoteHint = if (parseBusyCanonical(signal.payload) != null) "ALIVE" else "UNKNOWN"
                        ConferenceRecoveryOwnershipLog.emitRejoinResponse(
                            conferenceId = session.id,
                            localModuleId = localModuleId.value,
                            targetParticipantId = rejectorModuleId,
                            response = "BUSY",
                            localMembershipBelief = ConferenceRecoveryOwnershipLog.MembershipObservationState.JOINED,
                            remoteMembershipHint = remoteHint,
                            observedRosterEpoch = session.rosterEpoch
                        )
                    }
                    return
                }
                val remainingRemoteIds = when (session.type) {
                    SessionType.CONFERENCE -> conferenceMemberRemoteIds(session)
                    else -> remoteModuleIds(session)
                }
                if (remainingRemoteIds.isEmpty()) {
                    log(
                        "[${session.traceId}] All targets rejected (${signal.payload}) with no connected " +
                            "remotes -> tearing down solo mesh"
                    )
                    hangupInternal(session.id)
                    return
                }
                if (isBusyRejectPayload(signal.payload) && !hasLiveMeshNegotiation(session, remainingRemoteIds)) {
                    log(
                        "[${session.traceId}] BUSY with no connected remotes and no live mesh " +
                            "negotiation -> tearing down stalled solo mesh"
                    )
                    hangupInternal(session.id)
                    return
                }
            }
            log(
                "[${session.traceId}] Mesh invite rejected by $rejectorModuleId " +
                    "reason=${signal.payload} (session kept)"
            )
            if (session.type == SessionType.CONFERENCE && isBusyRejectPayload(signal.payload)) {
                val remoteHint = if (parseBusyCanonical(signal.payload) != null) "ALIVE" else "UNKNOWN"
                ConferenceRecoveryOwnershipLog.emitRejoinResponse(
                    conferenceId = session.id,
                    localModuleId = localModuleId.value,
                    targetParticipantId = rejectorModuleId,
                    response = "BUSY",
                    localMembershipBelief = ConferenceRecoveryOwnershipLog.MembershipObservationState.JOINED,
                    remoteMembershipHint = remoteHint,
                    observedRosterEpoch = session.rosterEpoch
                )
            }
            return
        }
        sessions.remove(signal.sessionId)
        pendingGroupJoinsBySession.remove(signal.sessionId)
        pendingIceBySession.remove(signal.sessionId)
        if (signal.payload == "DECLINED") {
            session.remote?.moduleId?.value?.let { reDialByRemoteModule.remove(it) }
        }
        releaseSessionMedia(session)
        session.ptt.onEvent(PttEvent.Rejected)
        log("${sessionTag(session)} Call rejected reason=${signal.payload}")
        if (session.type == SessionType.UNICAST) {
            resumeGroupSessionsAfterUnicast(signal.sessionId)
        }
    }

    /**
     * When we are the conference host on this channel, pull a rejoining device into our existing
     * session instead of competing with their solo conference.
     */
    private fun tryHostCounterInviteConference(
        signal: SignalEnvelope,
        fromPeer: PeerTarget,
        channelId: String,
        sessionType: SessionType,
        caller: EndpointAddress
    ): Boolean {
        if (sessionType != SessionType.CONFERENCE) return false
        val payload = GroupSessionPayload.decode(signal.payload) ?: return false
        val incomingInitiator = payload.initiatorModuleId
        val callerModuleId = caller.moduleId.value
        if (incomingInitiator != callerModuleId) return false
        if (resolveConferenceAuthorityModuleId(channelId) != localModuleId.value) return false
        // Accept the session even if not yet fully connected (accepted flag may still be false
        // during initial SDP negotiation) so the counter-invite fires before glare escalates.
        val hostConference = sessions.values.firstOrNull {
            it.channelId == channelId &&
                it.type == SessionType.CONFERENCE &&
                it.initiatorModuleId == localModuleId &&
                it.id != signal.sessionId
        } ?: return false
        val rejoiningLeftMember = conferenceParticipantManager
            .leftMemberEndpoints(hostConference.id)?.containsKey(callerModuleId) == true
        if (!rejoiningLeftMember &&
            conferenceParticipantManager.containsParticipant(hostConference.id, callerModuleId) &&
            qosMonitor.isGroupConnected(callerModuleId)
        ) {
            return false
        }
        rememberSignalPeer(callerModuleId, fromPeer)
        val sent = sendConferenceInvitesInternal(hostConference.id, listOf(caller), rejoin = true)
        log("[${hostConference.traceId}] Host counter-invited $callerModuleId sent=$sent")
        return sent > 0
    }

    private fun isIncomingConferenceRejoinInvite(signal: SignalEnvelope, channelId: String): Boolean {
        if (pendingRejoinByChannel[channelId] != null) return true
        GroupSessionPayload.decode(signal.payload)?.rejoin?.let { if (it) return true }
        val hint = lastRejoinableConferenceByChannel[channelId] ?: return false
        if (hint.isExpired(ttlMs = config.rejoinableConferenceTtlMs)) return false
        return signal.from.moduleId.value == hint.hostModuleId &&
            signal.sessionId == hint.hostSessionId
    }

    /**
     * WM-R1: Rejoin hint eligibility follows conference lifecycle/membership only — never Edge Recovery.
     * Runs on participant soft-leave; host session lives on the host device, not local sessions map.
     */
    private fun conferenceLifecycleIsRejoinEligible(session: TalkbackSession): Boolean {
        if (session.channelId == null) return false
        if (session.type != SessionType.CONFERENCE) return false
        if (!session.accepted) return false
        val hostModuleId = session.initiatorModuleId?.value ?: return false
        if (hostModuleId == localModuleId.value) return false
        return true
    }

    private fun rememberRejoinableConference(session: TalkbackSession) {
        val channelId = session.channelId ?: return
        if (!conferenceLifecycleIsRejoinEligible(session)) {
            log("[${session.traceId}] Conference rejoin memory skipped ch=$channelId reason=not_eligible")
            return
        }
        val hostModuleId = session.initiatorModuleId?.value ?: return
        val hostKey = session.groupMembers
            .firstOrNull { it.moduleId.value == hostModuleId }
            ?.key
            ?: "$hostModuleId-E01"
        val record = RejoinableConferenceRecord(
            channelId = channelId,
            hostSessionId = session.id,
            hostModuleId = hostModuleId,
            hostKey = hostKey,
            leftAtMs = System.currentTimeMillis(),
            membershipEpoch = session.rosterEpoch
        )
        lastRejoinableConferenceByChannel[channelId] = record
        pendingRejoinByChannel[channelId] = session.id
        log("[${session.traceId}] Conference rejoin memory saved ch=$channelId host=$hostModuleId")
    }

    private fun clearConferenceRejoinState(channelId: String) {
        val hadHints = lastRejoinableConferenceByChannel.containsKey(channelId) ||
            pendingRejoinByChannel.containsKey(channelId)
        lastRejoinableConferenceByChannel.remove(channelId)
        pendingRejoinByChannel.remove(channelId)
        conferenceRejoinStartedAtByChannel.remove(channelId)
        cancelHostRejoinRetry(channelId)
        if (hadHints) {
            ConferenceAuditTimelineLog.lifecycle(
                event = "REJOIN_STATE_CLEARED",
                channelId = channelId,
                sessionId = null,
                writer = "clearConferenceRejoinState",
                cause = "hints_removed"
            )
        }
    }

    private fun clearRejoinHintsForSession(channelId: String, sessionId: String) {
        lastRejoinableConferenceByChannel[channelId]?.takeIf { it.hostSessionId == sessionId }?.let {
            clearConferenceRejoinState(channelId)
        }
        if (pendingRejoinByChannel[channelId] == sessionId) {
            clearConferenceRejoinState(channelId)
        }
    }

    /** Conference rejoin intent (joinMeeting / host retry). WM-R2: must not read Edge Recovery state. */
    private fun sendConferenceRejoinIntentInternal(
        channelId: String,
        authority: EndpointAddress,
        hostSessionId: String
    ): Boolean {
        val hostSessionLive = sessions[hostSessionId]?.let {
            it.channelId == channelId && it.type == SessionType.CONFERENCE
        } == true
        val hintValid = lastRejoinableConferenceByChannel[channelId]?.let {
            it.hostSessionId == hostSessionId && !it.isExpired(ttlMs = config.rejoinableConferenceTtlMs)
        } == true
        if (!hostSessionLive && !hintValid) {
            log("Conference rejoin skipped: no live host session or rejoin hint ch=$channelId session=$hostSessionId")
            return false
        }
        return dispatchConferenceRejoinSignal(
            channelId,
            authority,
            hostSessionId,
            intent = ConferenceJoinIntent.USER_REJOIN
        )
    }

    /** Recovery-triggered reattach with R28 reachability gate (ADR-0022). */
    private fun dispatchRecoveryReattachOutcome(
        channelId: String,
        authority: EndpointAddress,
        hostSessionId: String
    ): ReattachDispatchOutcome {
        if (conferenceEdgeRecoveryController.isSessionCancelled(hostSessionId)) {
            log("RECOVERY_EVENT_DROPPED session=$hostSessionId reason=session_cancelled")
            return ReattachDispatchOutcome.SESSION_CANCELLED
        }
        val session = sessions[hostSessionId]
        val authorityId = authority.moduleId.value
        val reachability = buildRecoveryEdgeReachabilitySnapshot(channelId, session, authorityId)
        if (!reachability.canAttemptRecovery()) {
            val waiting = reachability.attemptWaitingReason()!!
            log(
                "RECOVERY_REATTACH_DEFERRED session=$hostSessionId ch=$channelId to=$authorityId " +
                    "reason=$waiting ${reachability.formatProbeFields()}"
            )
            log(
                "RECOVERY_WAITING session=$hostSessionId edge=$authorityId reason=$waiting " +
                    reachability.formatProbeFields()
            )
            return ReattachDispatchOutcome.DEFERRED
        }
        return executeRecoveryReattachSend(
            channelId = channelId,
            authority = authority,
            hostSessionId = hostSessionId,
            reachability = reachability
        )
    }

    private fun buildRecoveryEdgeReachabilitySnapshot(
        channelId: String,
        session: TalkbackSession?,
        remoteModuleId: String
    ): EdgeReachabilitySnapshot {
        val linkReady = !stopped && resolveChannelReadiness(channelId) == ChannelReadiness.READY
        val peerDiscovered = resolvePeerForModule(remoteModuleId) != null
        // v1: mesh ICE CONNECTED to remote == signaling/mesh route converged for this edge.
        val routeConverged = qosMonitor.isGroupConnected(remoteModuleId)
        val authorityReachable = session?.let { isConferenceAuthorityReachable(it) } ?: false
        return EdgeReachabilitySnapshot(
            linkReady = linkReady,
            peerDiscovered = peerDiscovered,
            routeConverged = routeConverged,
            authorityReachable = authorityReachable
        )
    }

    private fun executeRecoveryReattachSend(
        channelId: String,
        authority: EndpointAddress,
        hostSessionId: String,
        reachability: EdgeReachabilitySnapshot
    ): ReattachDispatchOutcome {
        val authorityId = authority.moduleId.value
        val peer = resolvePeerForModule(authorityId) ?: run {
            log(
                "RECOVERY_REATTACH_SEND_FAILED session=$hostSessionId ch=$channelId " +
                    "to=$authorityId err=peer_unreachable ${reachability.formatProbeFields()}"
            )
            return ReattachDispatchOutcome.PEER_UNREACHABLE
        }
        val local = EndpointAddress(localModuleId, localEndpointId())
        val hint = lastRejoinableConferenceByChannel[channelId]
        val hostSession = sessions[hostSessionId]
        val membershipEpoch = hint?.membershipEpoch
            ?: hostSession?.rosterEpoch
            ?: sessions.values.firstOrNull {
                it.channelId == channelId && it.type == SessionType.CONFERENCE
            }?.rosterEpoch
            ?: 0L
        val request = RecoveryReattachRequest(
            conferenceId = channelId,
            hostSessionId = hostSessionId,
            membershipEpoch = membershipEpoch,
            endpointId = local.endpointId.value
        )
        val payload = request.toRejoinPayload(ConferenceJoinIntent.RECOVERY_REATTACH).encode()
        val envelope = buildSignedEnvelope(
            SignalType.CONFERENCE_REJOIN,
            local,
            authority,
            hostSessionId.ifBlank { UUID.randomUUID().toString() },
            payload
        )
        log(
            "RECOVERY_REATTACH_ENQUEUED session=$hostSessionId ch=$channelId " +
                "from=${local.moduleId.value} to=$authorityId epoch=$membershipEpoch " +
                reachability.formatProbeFields()
        )
        var sent = false
        runCatching {
            signalingChannel.send(peer, envelope)
        }.onSuccess {
            sent = true
            log(
                "RECOVERY_REATTACH_SENT session=$hostSessionId ch=$channelId to=$authorityId " +
                    "nonce=${envelope.nonce} ${reachability.formatProbeFields()}"
            )
        }.onFailure {
            log(
                "RECOVERY_REATTACH_SEND_FAILED session=$hostSessionId ch=$channelId " +
                    "to=$authorityId err=${it.message} ${reachability.formatProbeFields()}"
            )
        }
        if (!sent) return ReattachDispatchOutcome.SEND_FAILED
        if (hostSessionId.isNotBlank()) {
            pendingRejoinByChannel[channelId] = hostSessionId
        }
        hostRejoinAttemptByChannel.remove(channelId)
        cancelHostRejoinRetry(channelId)
        conferenceRejoinStartedAtByChannel[channelId] = System.currentTimeMillis()
        sessions.values
            .filter { it.channelId == channelId && it.type == SessionType.CONFERENCE }
            .forEach { conferenceReconnectStartedAtBySession[it.id] = System.currentTimeMillis() }
        log(
            "RECOVERY_REATTACH requested by ${local.moduleId.value} -> authority " +
                "${authority.moduleId.value} session=$hostSessionId ch=$channelId " +
                "epoch=$membershipEpoch endpoint=${local.endpointId.value}"
        )
        return ReattachDispatchOutcome.SENT
    }

    private fun dispatchConferenceRejoinSignal(
        channelId: String,
        authority: EndpointAddress,
        hostSessionId: String,
        intent: ConferenceJoinIntent
    ): Boolean {
        val authorityId = authority.moduleId.value
        val peer = resolvePeerForModule(authorityId) ?: run {
            log("Conference rejoin skipped: authority $authorityId not reachable")
            return false
        }
        val local = EndpointAddress(localModuleId, localEndpointId())
        val hint = lastRejoinableConferenceByChannel[channelId]
        val hostSession = hostSessionId.takeIf { it.isNotBlank() }?.let { sessions[it] }
        val membershipEpoch = hint?.membershipEpoch
            ?: hostSession?.rosterEpoch
            ?: sessions.values.firstOrNull {
                it.channelId == channelId && it.type == SessionType.CONFERENCE
            }?.rosterEpoch
            ?: 0L
        val request = RecoveryReattachRequest(
            conferenceId = channelId,
            hostSessionId = hostSessionId,
            membershipEpoch = membershipEpoch,
            endpointId = local.endpointId.value
        )
        val payload = request.toRejoinPayload(intent).encode()
        val envelope = buildSignedEnvelope(
            SignalType.CONFERENCE_REJOIN,
            local,
            authority,
            hostSessionId.ifBlank { UUID.randomUUID().toString() },
            payload
        )
        sendSignal(peer, envelope)
        if (hostSessionId.isNotBlank()) {
            pendingRejoinByChannel[channelId] = hostSessionId
        }
        hostRejoinAttemptByChannel.remove(channelId)
        cancelHostRejoinRetry(channelId)
        conferenceRejoinStartedAtByChannel[channelId] = System.currentTimeMillis()
        sessions.values
            .filter { it.channelId == channelId && it.type == SessionType.CONFERENCE }
            .forEach { conferenceReconnectStartedAtBySession[it.id] = System.currentTimeMillis() }
        val label = when (intent) {
            ConferenceJoinIntent.USER_REJOIN -> "USER_REJOIN"
            ConferenceJoinIntent.RECOVERY_REATTACH -> "RECOVERY_REATTACH"
            ConferenceJoinIntent.NORMAL_JOIN -> "NORMAL_JOIN"
        }
        log(
            "$label requested by ${local.moduleId.value} -> authority " +
                "${authority.moduleId.value} session=$hostSessionId ch=$channelId " +
                "epoch=$membershipEpoch endpoint=${local.endpointId.value}"
        )
        return true
    }

    private fun validateRecoveryReattachLineage(
        payload: ConferenceRejoinPayload,
        signal: SignalEnvelope,
        hostConference: TalkbackSession
    ): String? {
        if (payload.channelId != hostConference.channelId) return "channel_mismatch"
        if (payload.hostSessionId.isNotBlank() && payload.hostSessionId != hostConference.id) {
            return "session_mismatch"
        }
        val lineageRequired = payload.intent == ConferenceJoinIntent.RECOVERY_REATTACH ||
            payload.membershipEpoch > 0L ||
            payload.endpointId.isNotBlank()
        if (!lineageRequired) return null
        if (payload.endpointId.isNotBlank() &&
            payload.endpointId != signal.from.endpointId.value
        ) {
            return "endpoint_mismatch"
        }
        if (payload.membershipEpoch > 0L &&
            hostConference.rosterEpoch > 0L &&
            payload.membershipEpoch > hostConference.rosterEpoch
        ) {
            log(
                "[${hostConference.traceId}] RECOVERY_REATTACH stale epoch requester=${signal.from.moduleId.value} " +
                    "epoch=${payload.membershipEpoch} hostEpoch=${hostConference.rosterEpoch}"
            )
        }
        return null
    }

    private fun rejectRecoveryReattach(
        fromPeer: PeerTarget,
        requester: EndpointAddress,
        hostSessionId: String,
        reason: String
    ) {
        val local = EndpointAddress(localModuleId, localEndpointId())
        sendSignal(
            fromPeer,
            buildSignedEnvelope(
                SignalType.CALL_REJECT,
                local,
                requester,
                hostSessionId,
                reason
            )
        )
    }

    private fun handleConferenceRejoin(signal: SignalEnvelope, fromPeer: PeerTarget) {
        val payload = ConferenceRejoinPayload.decode(signal.payload) ?: return
        val rejoinerId = signal.from.moduleId.value
        val channelId = payload.channelId
        if (payload.intent == ConferenceJoinIntent.RECOVERY_REATTACH) {
            val inboundSessionId = payload.hostSessionId.takeIf { it.isNotBlank() } ?: signal.sessionId
            log(
                "RECOVERY_REATTACH_INBOUND session=$inboundSessionId ch=$channelId " +
                    "from=$rejoinerId intent=${payload.intent} epoch=${payload.membershipEpoch}"
            )
        }
        rememberSignalPeer(rejoinerId, fromPeer)

        val hostConference = when {
            payload.hostSessionId.isNotBlank() -> sessions[payload.hostSessionId]
            else -> null
        } ?: sessions.values.firstOrNull {
            it.channelId == channelId &&
                it.type == SessionType.CONFERENCE &&
                it.initiatorModuleId == localModuleId
        }
        if (hostConference == null) {
            log("Conference rejoin ignored: no host session on $channelId from $rejoinerId")
            val local = EndpointAddress(localModuleId, localEndpointId())
            sendSignal(
                fromPeer,
                buildSignedEnvelope(
                    SignalType.CALL_REJECT,
                    local,
                    signal.from,
                    signal.sessionId,
                    "MEETING_ENDED"
                )
            )
            return
        }
        if (hostConference.initiatorModuleId != localModuleId) return

        val isMembershipRejoin = payload.intent == ConferenceJoinIntent.USER_REJOIN ||
            payload.intent == ConferenceJoinIntent.NORMAL_JOIN

        if (!isMembershipRejoin) {
            validateRecoveryReattachLineage(payload, signal, hostConference)?.let { lineageReason ->
                log(
                    "[${hostConference.traceId}] RECOVERY_REATTACH denied: $rejoinerId reason=$lineageReason " +
                        "epoch=${payload.membershipEpoch} hostEpoch=${hostConference.rosterEpoch}"
                )
                rejectRecoveryReattach(fromPeer, signal.from, hostConference.id, "RECOVERY_LINEAGE_INVALID")
                return
            }
        }

        val leftKeys = conferenceParticipantManager.leftMemberEndpoints(hostConference.id)?.keys
            ?: hostConference.leftMemberEndpoints.keys
        val wasMember = rejoinerId in leftKeys ||
            rejoinerId in conferenceMemberRemoteIds(hostConference) ||
            hostConference.memberModules.any { it.value == rejoinerId }
        if (!wasMember) {
            log("[${hostConference.traceId}] Conference rejoin denied: $rejoinerId was not a prior member")
            return
        }

        val sent = sendConferenceInvitesInternal(hostConference.id, listOf(signal.from), rejoin = true)
        if (isMembershipRejoin) {
            // Membership plane only — MUST NOT enter RecoveryController (ADR-0021 addendum).
            log(
                "[${hostConference.traceId}] JOIN_RESTORE_STARTED remote=$rejoinerId " +
                    "intent=${payload.intent} sent=$sent epoch=${payload.membershipEpoch}"
            )
            return
        }

        conferenceEdgeRecoveryController.onRecoveryReattachAccepted(
            hostConference.id,
            rejoinerId,
            recoveryReason = RecoveryReason.NETWORK_RECOVERY,
            source = RecoverySource.ICE_MONITOR
        )
        log(
            "[${hostConference.traceId}] RECOVERY_REATTACH accepted $rejoinerId sent=$sent " +
                "epoch=${payload.membershipEpoch}"
        )
    }

    private fun markConferenceParticipantLeft(session: TalkbackSession, moduleId: String) {
        if (moduleId == localModuleId.value) return
        removeConferenceParticipant(
            session,
            moduleId,
            AuthorityMembershipMutationSource.AUTHORITY_GROUP_LEAVE
        )
    }

    private fun localEndpointId(): EndpointId {
        return endpointRegistry.allOnline().firstOrNull()?.address?.endpointId ?: EndpointId("E01")
    }

    /**
     * When we already hold the channel GROUP mesh, pull a joining peer into it instead of BUSY.
     * Canonical session wins: min(initiatorModuleId), tie-break min(sessionId).
     */
    private fun tryCounterInviteGroupMesh(
        signal: SignalEnvelope,
        fromPeer: PeerTarget,
        channelId: String,
        sessionType: SessionType,
        caller: EndpointAddress
    ): Boolean {
        if (sessionType != SessionType.GROUP) return false
        val payload = GroupSessionPayload.decode(signal.payload) ?: return false
        val incomingInitiator = payload.initiatorModuleId.ifBlank { caller.moduleId.value }
        val callerModuleId = caller.moduleId.value
        if (incomingInitiator != callerModuleId) return false
        val localMesh = sessions.values.firstOrNull {
            it.channelId == channelId &&
                it.type == SessionType.GROUP &&
                it.accepted &&
                it.id != signal.sessionId
        } ?: return false
        if (isRemoteMembershipAuthority(callerModuleId, localMesh)) {
            log("[${localMesh.traceId}] Skipping counter-invite for membership authority $callerModuleId")
            return false
        }
        val localInitiator = localMesh.initiatorModuleId?.value ?: localModuleId.value
        if (compareGroupSessionAuthority(incomingInitiator, signal.sessionId, localInitiator, localMesh.id) <= 0) {
            return false
        }
        if (callerModuleId in remoteModuleIds(localMesh) &&
            qosMonitor.isGroupConnected(callerModuleId)
        ) {
            return false
        }
        rememberSignalPeer(callerModuleId, fromPeer)
        val sent = sendGroupMeshInvitesInternal(localMesh.id, listOf(caller))
        log("[${localMesh.traceId}] Counter-invited $callerModuleId into group mesh sent=$sent")
        return sent > 0
    }

    private fun handleGroupInvite(signal: SignalEnvelope, fromPeer: PeerTarget) {
        val caller = signal.from
        val callee = signal.to ?: return
        val payload = GroupSessionPayload.decode(signal.payload) ?: return
        val channelId = payload.channelId
        val members = GroupSessionPayload.parseMembers(payload.members)
        if (members.isEmpty()) return

        val sessionType = sessionTypeFromMode(payload.sessionMode)

        if (tryHostCounterInviteConference(signal, fromPeer, channelId, sessionType, caller)) {
            return
        }
        if (tryCounterInviteGroupMesh(signal, fromPeer, channelId, sessionType, caller)) {
            return
        }

        if (sessionType == SessionType.GROUP &&
            tryApplyMembershipSnapshotInvite(signal, fromPeer, payload)
        ) {
            return
        }

        sessions[signal.sessionId]?.let { existing ->
            if (existing.type == SessionType.GROUP && sessionType == SessionType.GROUP) {
                if (inviteCanonicalRosterMismatch(existing, payload)) {
                    requestGroupResyncFromAuthority(existing)
                    return
                }
                if (acceptGroupInviteReconnect(existing, signal, fromPeer, payload)) {
                    return
                }
            }
        }

        if (!prepareForGroupInvite(signal, channelId, caller.moduleId.value)) {
            sendGroupBusyReject(
                fromPeer,
                callee,
                caller,
                signal.sessionId,
                canonicalGroupSessionOnChannel(channelId)
            )
            return
        }

        if (sessionType == SessionType.CONFERENCE && !autoAcceptConferenceInvites) {
            if (isIncomingConferenceRejoinInvite(signal, channelId)) {
                if (!acceptGroupInvite(signal, fromPeer)) {
                    sendSignal(
                        fromPeer,
                        buildSignedEnvelope(SignalType.CALL_REJECT, callee, caller, signal.sessionId, "BUSY")
                    )
                }
                return
            }
            enterChannelMode(channelId, ChannelMode.CONFERENCE, caller.moduleId.value)
            pendingConferenceInvitesByChannel[channelId] = PendingConferenceInvite(
                signal,
                fromPeer,
                System.currentTimeMillis()
            )
            log("Conference invite pending user confirmation ch=$channelId from=${caller.key}")
            return
        }

        if (!acceptGroupInvite(signal, fromPeer)) {
            sendGroupBusyReject(
                fromPeer,
                callee,
                caller,
                signal.sessionId,
                if (sessionType == SessionType.GROUP) canonicalGroupSessionOnChannel(channelId) else null
            )
        }
    }

    private fun canAcceptGroupInvite(signal: SignalEnvelope, @Suppress("UNUSED_PARAMETER") fromPeer: PeerTarget): Boolean {
        signal.to ?: return false
        val payload = GroupSessionPayload.decode(signal.payload) ?: return false
        val members = GroupSessionPayload.parseMembers(payload.members)
        if (members.isEmpty()) return false
        val channelId = payload.channelId
        val incomingType = sessionTypeFromMode(payload.sessionMode)
        if (incomingType == SessionType.GROUP && blocksGroupOnChannel(channelId)) return false
        if (sessions.containsKey(signal.sessionId)) return false
        val otherChannelSession = sessions.values.firstOrNull {
            it.id != signal.sessionId && it.channelId != channelId
        }
        if (otherChannelSession != null && activeSessionCount() >= config.maxActiveSessions) return false
        return true
    }

    private fun acceptGroupInvite(signal: SignalEnvelope, fromPeer: PeerTarget): Boolean {
        val caller = signal.from
        val callee = signal.to ?: return false
        val payload = GroupSessionPayload.decode(signal.payload) ?: return false
        val channelId = payload.channelId
        val members = GroupSessionPayload.parseMembers(payload.members)
        if (members.isEmpty()) return false
        val sessionType = sessionTypeFromMode(payload.sessionMode)
        if (sessionType == SessionType.GROUP) {
            yieldLocalGroupSessionsForIncomingInvite(channelId, signal.sessionId)
        }

        if (sessionType == SessionType.CONFERENCE) {
            conferenceJoinLatencyTracker.onInviteAccepted(
                sessionId = signal.sessionId,
                channelId = channelId,
                role = "participant"
            )
        }

        val session = TalkbackSession(signal.sessionId, sessionType, callee, channelId)
        populateGroupSessionMetadata(session, payload, members, caller, fromPeer)
        freezeChannelMemberSnapshot(session)
        sessions[signal.sessionId] = session
        if (sessionType == SessionType.CONFERENCE) {
            auditConferenceSessionLifecycle(
                event = "SESSION_CREATED",
                session = session,
                writer = "acceptGroupInvite",
                cause = "participant_invite_accepted"
            )
        }
        enterChannelMode(
            channelId,
            if (sessionType == SessionType.CONFERENCE) ChannelMode.CONFERENCE else ChannelMode.GROUP_PTT,
            session.initiatorModuleId?.value ?: caller.moduleId.value
        )
        if (sessionType == SessionType.CONFERENCE) {
            conferenceParticipantManager.syncParticipantsFromMembers(session.id, localModuleId)
        } else {
            session.syncParticipantsFromMembers(localModuleId)
        }
        if (sessionType == SessionType.CONFERENCE) {
            conferenceParticipantManager.onInviteAccepted(session.id, caller.moduleId.value)
        } else {
            meshParticipant(session, caller.moduleId.value).apply {
                invite = InviteState.ACCEPTED
                media = MediaState.CONNECTING
                lastMediaChangeMs = System.currentTimeMillis()
            }
        }

        val engine = acquireMeshEngine(session, caller.moduleId.value, forReconnect = false)
        wireIceCallback(session, caller.moduleId.value, engine)
        val answer = engine.applyRemoteOffer(payload.sdp, politeForInviteAnswer())
        drainPendingIce(session.id, caller.moduleId.value, engine)
        session.accepted = true
        if (sessionType == SessionType.GROUP) {
            ensureConvergenceAnchor(session)
            maybeEmitAppStartSnapshot(session)
        }
        markMeshLinkCompleted(session,caller.moduleId.value)
        sendSignal(
            fromPeer,
            buildSignedEnvelope(SignalType.GROUP_ACCEPT, callee, caller, signal.sessionId, answer)
        )
        val inviteLabel = if (sessionType == SessionType.CONFERENCE) "Conference" else "Group"
        log("[${session.traceId}] $inviteLabel invite accepted ch=$channelId members=${members.size}")
        if (sessionType == SessionType.CONFERENCE) {
            clearConferenceRejoinState(channelId)
            if (session.initiatorModuleId != localModuleId) {
                scheduleConferenceHostLinkKick(session, caller.moduleId.value)
            }
        }
        completeGroupMesh(session)
        if (sessionType == SessionType.CONFERENCE && payload.rejoin) {
            reconnectConferenceMeshToOtherPeers(session)
        }
        drainPendingGroupJoins(session.id)
        scheduleGroupMeshRetries(session.id)
        updateSessionReceivePlayback(session)
        return true
    }

    /**
     * When rejoining an existing conference, dial every other roster peer directly. Those peers
     * dropped us from their roster on our GROUP_LEAVE, so the normal lexicographic offerer will
     * not re-initiate the pairwise link. The receiver re-adds us via
     * [ensureConferenceParticipantInRoster], restoring the full mesh (and roster count) on all
     * participants, not just the host.
     */
    private fun reconnectConferenceMeshToOtherPeers(session: TalkbackSession) {
        if (session.type != SessionType.CONFERENCE) return
        val hostId = session.initiatorModuleId?.value
        meshRoster(session)
            .map { it.moduleId }
            .filter { it != localModuleId && it.value != hostId }
            .filter { !qosMonitor.isGroupConnected(it.value) }
            .distinct()
            .forEach { offerGroupMeshJoin(session, it) }
    }

    /** Existing canonical GROUP session: treat duplicate GROUP_INVITE as ICE-restart reconnect. */
    private fun acceptGroupInviteReconnect(
        session: TalkbackSession,
        signal: SignalEnvelope,
        fromPeer: PeerTarget,
        payload: GroupSessionPayload
    ): Boolean {
        if (session.isForegroundSuspended()) return false
        val caller = signal.from
        val callee = signal.to ?: return false
        if (payload.sdp.isBlank()) return false
        val peerId = caller.moduleId.value
        val ice = qosMonitor.snapshot(peerId)?.iceState
        val channelId = session.channelId
        if (channelId != null &&
            session.meshCompletedModules.contains(peerId) &&
            !IceConnectivity.isConnected(ice) &&
            !groupMeshReconciler.canAcceptIceRestart(channelId, peerId, ice)
        ) {
            log("${sessionTag(session)} Group invite reconnect throttled from $peerId ice=$ice")
            return false
        }
        channelId?.let { groupMeshReconciler.markIceRestartAccepted(it, peerId) }
        applyCanonicalMetadata(session, payload)
        session.remotePeersByModule[peerId] = fromPeer
        session.memberModules.add(caller.moduleId)
        val engine = getOrCreateMeshEngine(session, peerId)
        wireIceCallback(session, peerId, engine)
        val answer = engine.applyRemoteOffer(payload.sdp, politeForInviteAnswer())
        drainPendingIce(session.id, caller.moduleId.value, engine)
        session.accepted = true
        markMeshLinkCompleted(session,caller.moduleId.value)
        sendSignal(
            fromPeer,
            buildSignedEnvelope(SignalType.GROUP_ACCEPT, callee, caller, signal.sessionId, answer)
        )
        log("${sessionTag(session)} Group invite reconnect accepted from ${caller.moduleId.value}")
        updateSessionReceivePlayback(session)
        emitGroupTopologySnapshot(TopologySnapshotReason.RECONNECT, session)
        onGroupConvergenceBoundary(session)
        return true
    }

    /** Clear stale/zombie sessions so a fresh CALL_INVITE can be accepted. */
    private fun prepareForIncomingUnicast(signal: SignalEnvelope): Boolean {
        cleanupUnhealthySessions()
        sessions.values
            .filter { it.id != signal.sessionId }
            .forEach { session ->
                when (session.type) {
                    SessionType.GROUP, SessionType.CONFERENCE -> suspendSessionForUnicast(session)
                    SessionType.UNICAST -> {
                        log("[${session.traceId}] Replacing unicast for incoming private call")
                        hangupInternal(session.id)
                    }
                }
            }
        return activeSessionCount() == 0 || foregroundAdmission.canAdmitMeshInvite()
    }

    /** Clear stale/zombie sessions so a fresh GROUP_INVITE can be accepted. */
    private fun resolveMembershipAuthorityId(session: TalkbackSession): String? =
        session.anchorModuleId?.value
            ?: resolveBootstrapPrimary(dialableRemoteModuleIds().plus(localModuleId))?.value

    private fun isRemoteMembershipAuthority(callerModuleId: String, session: TalkbackSession): Boolean {
        val authorityId = resolveMembershipAuthorityId(session) ?: return false
        return callerModuleId == authorityId
    }

    /**
     * Control-plane yield for duplicate GROUP on the same channel.
     * Does not send HANGUP, release group media, or clear the ICE graph.
     */
    private fun yieldDuplicateGroupSession(session: TalkbackSession, authorityId: String) {
        session.disposition = SessionDisposition.YIELDED_TO_AUTHORITY
        stopSessionCapture(session)
        setPlaybackEnabled(session, enabled = false, reason = "yield_duplicate")
        val sessionId = session.id
        sessions.remove(sessionId)
        pendingIceBySession.remove(sessionId)
        pendingGroupJoinsBySession.remove(sessionId)
        log(
            "${sessionTag(session)} Yielding duplicate session to membership authority=$authorityId " +
                "channel=${session.channelId} disposition=${session.disposition}"
        )
    }

    private fun prepareForGroupInvite(
        signal: SignalEnvelope,
        channelId: String,
        callerModuleId: String
    ): Boolean {
        val payload = GroupSessionPayload.decode(signal.payload) ?: return false
        val incomingType = sessionTypeFromMode(payload.sessionMode)
        if (incomingType == SessionType.GROUP) {
            if (blocksGroupOnChannel(channelId)) {
                logGroupInviteBlocked(channelId)
                return false
            }
        }
        endConflictingMeshSessions(channelId, incomingType, exceptSessionId = signal.sessionId)

        val onChannel = sessions.values.firstOrNull {
            it.type == incomingType && it.channelId == channelId
        }
        if (onChannel != null) {
            if (onChannel.id == signal.sessionId) {
                return false
            }
            if (incomingType == SessionType.CONFERENCE && onChannel.type == SessionType.CONFERENCE) {
                val incomingInitiator = payload.initiatorModuleId
                val ourHost = onChannel.initiatorModuleId?.value
                if (ourHost == localModuleId.value && incomingInitiator != ourHost) {
                    log("[${onChannel.traceId}] Yielding conference to host invite from $incomingInitiator")
                    hangupInternal(onChannel.id)
                    return activeSessionCount() < config.maxActiveSessions
                }
                if (ourHost != null && ourHost != localModuleId.value && incomingInitiator != ourHost) {
                    log(
                        "[${onChannel.traceId}] Ignoring competing conference from $incomingInitiator " +
                            "(host=$ourHost)"
                    )
                    return false
                }
            }
            if (incomingType == SessionType.GROUP && onChannel.type == SessionType.GROUP) {
                val authorityId = resolveMembershipAuthorityId(onChannel)
                if (authorityId != null && callerModuleId == authorityId) {
                    yieldDuplicateGroupSession(onChannel, authorityId)
                    return activeSessionCount() < config.maxActiveSessions
                }
                val incomingInitiator = payload.initiatorModuleId.ifBlank { callerModuleId }
                val localInitiator = onChannel.initiatorModuleId?.value ?: localModuleId.value
                val authorityCmp = compareGroupSessionAuthority(
                    incomingInitiator,
                    signal.sessionId,
                    localInitiator,
                    onChannel.id
                )
                if (authorityCmp < 0) {
                    log(
                        "[${onChannel.traceId}] Yielding group mesh to $incomingInitiator " +
                            "session=${signal.sessionId}"
                    )
                    hangupInternal(onChannel.id)
                    return activeSessionCount() < config.maxActiveSessions
                }
                if (authorityCmp > 0) {
                    log(
                        "[${onChannel.traceId}] Keeping canonical group mesh, rejecting duplicate invite " +
                            "from $incomingInitiator"
                    )
                    return false
                }
            }
            if (isSessionTransmitReady(onChannel)) {
                log("[${onChannel.traceId}] Keeping connected mesh session on $channelId, rejecting duplicate invite")
                return false
            }
            log("[${onChannel.traceId}] Replacing incomplete mesh session on $channelId")
            hangupInternal(onChannel.id)
            return activeSessionCount() < config.maxActiveSessions
        }
        return foregroundAdmission.canAdmitMeshInvite()
    }

    /** Earlier invite wins; equal timestamp breaks on lower moduleId (design 4.4). */
    private fun shouldYieldGroupInitToIncoming(
        localSession: TalkbackSession,
        incoming: SignalEnvelope,
        callerModuleId: String
    ): Boolean {
        val localStart = localSession.lastActiveMs
        val remoteTs = incoming.timestampMs
        return when {
            remoteTs < localStart -> true
            remoteTs > localStart -> false
            else -> callerModuleId < localModuleId.value
        }
    }

    private fun abandonLocalGroupSession(session: TalkbackSession) {
        sessions.remove(session.id)
        pendingGroupJoinsBySession.remove(session.id)
        pendingIceBySession.remove(session.id)
        releaseSessionMedia(session)
        log("[${session.traceId}] Yield group init on ${session.channelId} to remote")
    }

    private fun handleGroupJoin(signal: SignalEnvelope, fromPeer: PeerTarget) {
        val payload = GroupSessionPayload.decode(signal.payload)
        val channelId = payload?.channelId
        val incomingType = payload?.sessionMode?.let(::sessionTypeFromMode)
        if (incomingType == SessionType.GROUP &&
            channelId != null &&
            payload?.joinIntent != ConferenceJoinIntent.RECOVERY_REATTACH &&
            payload?.joinIntent != ConferenceJoinIntent.USER_REJOIN &&
            blocksGroupOnChannel(channelId)
        ) {
            logGroupInviteBlocked(channelId, joinPath = "JOIN")
            signal.to?.let { callee ->
                sendGroupBusyReject(
                    fromPeer,
                    callee,
                    signal.from,
                    signal.sessionId,
                    canonicalGroupSessionOnChannel(channelId)
                )
            }
            return
        }
        val session = sessions[signal.sessionId]
        if (session == null) {
            pendingGroupJoinsBySession
                .getOrPut(signal.sessionId) { mutableListOf() }
                .add(PendingGroupJoin(signal, fromPeer))
            log("GROUP_JOIN queued: session=${signal.sessionId} from=${signal.from.moduleId.value}")
            return
        }
        acceptGroupJoin(session, signal, fromPeer)
    }

    private fun acceptGroupJoin(
        session: TalkbackSession,
        signal: SignalEnvelope,
        fromPeer: PeerTarget
    ) {
        val caller = signal.from
        val callee = signal.to ?: return
        val payload = GroupSessionPayload.decode(signal.payload) ?: return
        if (session.type == SessionType.CONFERENCE) {
            ensureConferenceParticipantInRoster(session, caller, fromPeer)
        }
        if (session.meshCompletedModules.contains(caller.moduleId.value)) {
            val peerId = caller.moduleId.value
            val ice = qosMonitor.snapshot(peerId)?.iceState
            if (IceConnectivity.isConnected(ice)) {
                log("${sessionTag(session)} GROUP_JOIN duplicate from $peerId ice=$ice")
                return
            }
            val channelId = session.channelId
            if (channelId != null &&
                !groupMeshReconciler.canAcceptIceRestart(channelId, peerId, ice)
            ) {
                log("${sessionTag(session)} GROUP_JOIN ICE restart throttled from $peerId ice=$ice")
                return
            }
            channelId?.let { groupMeshReconciler.markIceRestartAccepted(it, peerId) }
            session.remotePeersByModule[peerId] = fromPeer
            val engine = getOrCreateMeshEngine(session, peerId)
            wireIceCallback(session, peerId, engine)
            MediaRecoveryCausalTrace.mediaSignalOfferReceived(
                mediaRecoveryTraceContext(
                    session,
                    peerId,
                    caller.endpointId.value,
                    iceRestart = payload.joinIntent == ConferenceJoinIntent.RECOVERY_REATTACH
                ),
                joinIntent = payload.joinIntent.name
            )
            val answer = engine.applyRemoteOffer(payload.sdp, politeForMeshPair(peerId))
            drainPendingIce(session.id, peerId, engine)
            sendSignal(
                fromPeer,
                buildSignedEnvelope(SignalType.GROUP_ACCEPT, callee, caller, signal.sessionId, answer)
            )
            log("${sessionTag(session)} Group mesh ICE restart accepted $peerId ice=$ice intent=${payload.joinIntent}")
            if (session.type == SessionType.CONFERENCE) {
                markConferenceParticipantActive(session, caller.moduleId.value)
                // Connectivity Recovery only — USER_REJOIN must not enter RecoveryController.
                if (payload.joinIntent == ConferenceJoinIntent.RECOVERY_REATTACH) {
                    conferenceEdgeRecoveryController.onRecoveryReattachAccepted(
                        session.id,
                        caller.moduleId.value,
                        recoveryReason = RecoveryReason.NETWORK_RECOVERY,
                        source = RecoverySource.ICE_MONITOR
                    )
                }
            }
            if (session.type == SessionType.CONFERENCE) {
                notifyConferenceTransportChanged(session, "acceptGroupJoin")
            } else {
                updateSessionReceivePlayback(session, "acceptGroupJoin")
            }
            return
        }
        session.remotePeersByModule[caller.moduleId.value] = fromPeer
        session.memberModules.add(caller.moduleId)
        val engine = acquireMeshEngine(session, caller.moduleId.value, forReconnect = false)
        wireIceCallback(session, caller.moduleId.value, engine)
        val answer = engine.applyRemoteOffer(payload.sdp, politeForMeshPair(caller.moduleId.value))
        drainPendingIce(session.id, caller.moduleId.value, engine)
        markMeshLinkCompleted(session,caller.moduleId.value)
        sendSignal(
            fromPeer,
            buildSignedEnvelope(SignalType.GROUP_ACCEPT, callee, caller, signal.sessionId, answer)
        )
        log("[${session.traceId}] Group mesh link accepted ${caller.moduleId.value}")
        if (session.type == SessionType.CONFERENCE) {
            markConferenceParticipantActive(session, caller.moduleId.value)
        }
    }

    private fun markConferenceParticipantActive(session: TalkbackSession, moduleId: String) {
        meshParticipant(session,moduleId).apply {
            invite = InviteState.ACCEPTED
            if (media != MediaState.CONNECTED) {
                media = MediaState.CONNECTING
            }
            lastMediaChangeMs = System.currentTimeMillis()
        }
    }

    private fun handleGroupAccept(signal: SignalEnvelope, fromPeer: PeerTarget) {
        val session = sessions[signal.sessionId] ?: return
        session.accepted = true
        val moduleId = signal.from.moduleId.value
        if (session.type == SessionType.CONFERENCE) {
            ensureConferenceParticipantInRoster(session, signal.from, fromPeer)
        } else if (session.type == SessionType.GROUP && isMembershipAuthority(session)) {
            promoteInviteeToCanonicalRoster(session, signal.from)
        }
        val existingIce = qosMonitor.snapshot(moduleId)?.iceState
        if (IceConnectivity.isConnected(existingIce)) {
            session.remotePeersByModule.putIfAbsent(moduleId, fromPeer)
            session.memberModules.add(signal.from.moduleId)
            markMeshLinkCompleted(session,moduleId)
            meshParticipant(session,moduleId).apply {
                invite = InviteState.ACCEPTED
                media = MediaState.CONNECTED
                lastMediaChangeMs = System.currentTimeMillis()
            }
            log("${sessionTag(session)} Group accept duplicate from $moduleId ice=$existingIce")
            if (session.type == SessionType.CONFERENCE) {
                completeGroupMesh(session)
                drainPendingGroupJoins(session.id)
                scheduleGroupMeshRetries(session.id)
            }
            return
        }
        if (!session.remotePeersByModule.containsKey(moduleId)) {
            session.remotePeersByModule[moduleId] = fromPeer
            session.memberModules.add(signal.from.moduleId)
            if (session.remote == null) {
                session.remote = signal.from
                session.remotePeer = fromPeer
            }
        }
        val engine = getOrCreateMeshEngine(session, moduleId)
        wireIceCallback(session, moduleId, engine)
        engine.applyRemoteAnswer(signal.payload, politeForMeshPair(moduleId))
        drainPendingIce(session.id, moduleId, engine)
        markMeshLinkCompleted(session,moduleId)
        meshParticipant(session,moduleId).apply {
            invite = InviteState.ACCEPTED
            media = MediaState.CONNECTING
            lastMediaChangeMs = System.currentTimeMillis()
        }
        log("[${session.traceId}] Group accept from $moduleId")
        completeGroupMesh(session)
        drainPendingGroupJoins(session.id)
        if (session.type == SessionType.CONFERENCE) {
            scheduleGroupMeshRetries(session.id)
        }
        updateSessionReceivePlayback(session)
    }

    /** Late accept after ring-timeout eviction must restore full conference roster on the host. */
    private fun ensureConferenceParticipantInRoster(
        session: TalkbackSession,
        remote: EndpointAddress,
        peer: PeerTarget
    ) {
        val moduleId = remote.moduleId.value
        conferenceParticipantManager.leftMemberEndpoints(session.id)?.remove(moduleId)
        if (meshRoster(session).none { it.moduleId.value == moduleId }) {
            conferenceParticipantManager.onLateJoin(session.id, moduleId, remote)
            log("[${session.traceId}] Conference roster restored $moduleId after accept")
        }
        session.memberModules.add(remote.moduleId)
        session.remotePeersByModule[moduleId] = peer
    }

    private fun handleIce(signal: SignalEnvelope) {
        val session = sessions[signal.sessionId] ?: return
        val moduleId = signal.from.moduleId.value
        val engine = when (session.type) {
            SessionType.UNICAST -> mediaRegistry.getUnicast(session.id)
            else -> meshEngineForSession(session, moduleId)
        }
        val traceCtx = mediaRecoveryTraceContext(session, moduleId, signal.from.endpointId.value)
        if (engine == null) {
            MediaRecoveryCausalTrace.mediaSignalCandidateReceived(traceCtx, queued = true)
            queuePendingIce(signal.sessionId, moduleId, signal.payload)
            return
        }
        MediaRecoveryCausalTrace.mediaSignalCandidateReceived(traceCtx, queued = false)
        engine.addIceCandidate(signal.payload)
        MediaRecoveryCausalTrace.mediaIceCandidateApplied(traceCtx)
    }

    private fun logFloorRequestRecv(signal: SignalEnvelope, fromPeer: PeerTarget) {
        val session = sessions[signal.sessionId]
        val authorityModule = session?.floorAuthorityModuleId?.value
        val localIsAuthority = session?.let {
            GroupFloorController.isFloorAuthority(it, localModuleId.value)
        }
        log(
            "FLOOR_REQUEST_RECV local=${localModuleId.value} from=${signal.from.key} " +
                "src=${fromPeer.host}:${fromPeer.port} " +
                "envelopeTo=${signal.to?.key ?: "null"} " +
                "sid=${signal.sessionId} " +
                "authorityModule=${authorityModule ?: "n/a"} " +
                "localIsAuthority=${localIsAuthority?.toString() ?: "n/a"}"
        )
    }

    private fun handleFloorRequest(signal: SignalEnvelope) {
        val floorPayload = FloorPayload.decode(signal.payload)
        val traceId = floorPayload.traceId
        val session = sessions[signal.sessionId]
        if (session == null) {
            FloorTrace.requestDropped(
                traceId,
                signal.sessionId,
                "NO_SESSION",
                mapOf("from" to signal.from.key)
            )
            log(
                "FLOOR_DROP reason=NO_SESSION sid=${signal.sessionId} " +
                    "from=${signal.from.key}"
            )
            return
        }
        if (!session.type.usesFloorControl()) {
            FloorTrace.requestDropped(
                traceId,
                session.id,
                "NO_FLOOR_CONTROL",
                mapOf("from" to signal.from.key, "type" to session.type.name)
            )
            log(
                "FLOOR_DROP reason=NO_FLOOR_CONTROL sid=${session.id} " +
                    "from=${signal.from.key} type=${session.type}"
            )
            return
        }
        val authorityModule = session.floorAuthorityModuleId?.value
        val localIsAuthority = GroupFloorController.isFloorAuthority(session, localModuleId.value)
        FloorTrace.requestObserved(
            traceId,
            session.id,
            signal.from.key,
            authorityModule,
            localIsAuthority,
            floorPayload.floorEpoch,
            floorPayload.floorVersion,
            session.floor.epoch().takeIf { localIsAuthority }
        )
        log(
            "REQUEST_OBSERVED ${sessionTag(session)} sid=${session.id} " +
                "hash=${System.identityHashCode(session)} from=${signal.from.key} " +
                "authorityModule=${authorityModule ?: "null"} " +
                "localIsAuthority=$localIsAuthority"
        )
        if (!GroupFloorController.shouldProcessFloorRequest(session, localModuleId.value)) {
            FloorTrace.requestDropped(
                traceId,
                session.id,
                "NOT_AUTHORITY",
                mapOf(
                    "from" to signal.from.key,
                    "local" to localModuleId.value,
                    "authority" to (authorityModule ?: "null")
                )
            )
            log(
                "FLOOR_DROP reason=NOT_AUTHORITY ${sessionTag(session)} sid=${session.id} " +
                    "from=${signal.from.key} local=${localModuleId.value} " +
                    "authorityModule=${authorityModule ?: "null"}"
            )
            return
        }
        val requester = signal.from
        val identity = FloorOperationIdentity(session.id, requester.key)
        if (session.floorOwner.isRequestWithdrawn(identity, floorPayload.floorVersion)) {
            FloorTrace.requestDropped(
                traceId,
                session.id,
                "INTENT_WITHDRAWN",
                mapOf("from" to requester.key, "version" to floorPayload.floorVersion.toString())
            )
            log(
                "FLOOR_DROP reason=INTENT_WITHDRAWN ${sessionTag(session)} sid=${session.id} " +
                    "from=${requester.key} version=${floorPayload.floorVersion}"
            )
            return
        }
        if (!isLocalIdentity(session, requester)) {
            session.floorOwner.createRequestToken(session.id, requester, floorPayload.floorVersion)
        }
        processFloorArbitration(session, signal, floorPayload)
    }

    private fun handleFloorRequestCancel(signal: SignalEnvelope) {
        val floorPayload = FloorPayload.decode(signal.payload)
        val session = sessions[signal.sessionId] ?: return
        if (!session.type.usesFloorControl()) return
        if (!GroupFloorController.shouldProcessFloorRequest(session, localModuleId.value)) return
        val requester = signal.from
        val identity = FloorOperationIdentity(session.id, requester.key)
        session.floorOwner.recordRequestWithdrawal(identity, floorPayload.floorVersion)
        log(
            "FLOOR_REQUEST_CANCEL ${sessionTag(session)} sid=${session.id} " +
                "from=${requester.key} version=${floorPayload.floorVersion}"
        )
        if (session.floor.owner() == requester) {
            if (session.floor.release(requester)) {
                broadcastFloorRelease(session)
            }
        }
    }

    private fun isFloorRequestIntentCurrent(
        session: TalkbackSession,
        requester: EndpointAddress,
        requestVersion: Long,
        traceId: Long
    ): Boolean {
        val identity = FloorOperationIdentity(session.id, requester.key)
        if (session.floorOwner.isRequestWithdrawn(identity, requestVersion)) {
            FloorTrace.requestDropped(
                traceId,
                session.id,
                "INTENT_WITHDRAWN",
                mapOf("requester" to requester.key, "version" to requestVersion.toString())
            )
            return false
        }
        val token = session.floorOwner.tokens.validTokenFor(identity)
        if (token == null) {
            FloorTrace.requestDropped(
                traceId,
                session.id,
                "NO_VALID_INTENT",
                mapOf("requester" to requester.key, "version" to requestVersion.toString())
            )
            return false
        }
        if (requestVersion < token.version) {
            FloorTrace.requestDropped(
                traceId,
                session.id,
                "STALE_REQUEST",
                mapOf(
                    "requester" to requester.key,
                    "requestVersion" to requestVersion.toString(),
                    "tokenVersion" to token.version.toString()
                )
            )
            return false
        }
        return true
    }

    private fun processFloorArbitration(
        session: TalkbackSession,
        signal: SignalEnvelope,
        floorPayload: FloorPayload
    ) {
        if (!session.type.usesFloorControl()) return
        val traceId = floorPayload.traceId
        val requester = signal.from
        val canonicalRequester = GroupFloorController.canonicalRequester(session, requester)
        val previousOwner = session.floor.owner()
        val effectivePriority = resolveRegisteredPriority(requester, floorPayload.priority)
        if (effectivePriority != floorPayload.priority) {
            log(
                "Floor priority clamped ${requester.key} " +
                    "claimed=${floorPayload.priority} registered=$effectivePriority"
            )
        }
        val localEpochBefore = session.floor.epoch()
        val localVersionBefore = session.floor.version()
        val memberPresent = GroupFloorController.resolveFloorOwner(session, requester.key) != null
        if (!isFloorRequestIntentCurrent(session, requester, floorPayload.floorVersion, traceId)) {
            log(
                "TRY_GRANT_ABORT ${sessionTag(session)} requester=${requester.key} " +
                    "reason=INTENT_NOT_CURRENT version=${floorPayload.floorVersion}"
            )
            return
        }
        val result = session.floor.tryGrant(
            requester = canonicalRequester,
            requestVersion = floorPayload.floorVersion,
            requestEpoch = floorPayload.floorEpoch,
            priority = effectivePriority,
            requestTsMs = signal.timestampMs,
            arbitrator = floorArbitrator
        )
        FloorTrace.arbitration(
            traceId,
            session.id,
            requester.key,
            floorPayload.floorEpoch,
            localEpochBefore,
            floorPayload.floorVersion,
            localVersionBefore,
            previousOwner?.key,
            memberPresent,
            result.name
        )
        log(
            "TRY_GRANT ${sessionTag(session)} requester=${requester.key} " +
                "requestEpoch=${floorPayload.floorEpoch} localEpoch=$localEpochBefore " +
                "requestVersion=${floorPayload.floorVersion} localVersion=$localVersionBefore " +
                "currentOwner=${previousOwner?.key ?: "null"} memberPresent=$memberPresent " +
                "result=$result"
        )
        when (result) {
            FloorGrantResult.GRANTED, FloorGrantResult.PREEMPTED -> {
                if (!isFloorRequestIntentCurrent(session, requester, floorPayload.floorVersion, traceId)) {
                    if (session.floor.owner() == canonicalRequester) {
                        session.floor.release(canonicalRequester)
                    }
                    log(
                        "GRANT_ABORT ${sessionTag(session)} requester=${requester.key} " +
                            "reason=INTENT_WITHDRAWN_AFTER_ARBITRATION"
                    )
                    return
                }
                val grantPayload = FloorPayload.forRequest(
                    canonicalRequester,
                    session.floor.version(),
                    session.floor.epoch(),
                    effectivePriority,
                    traceId = traceId
                )
                applyFloorGrant(
                    session,
                    canonicalRequester,
                    grantPayload.floorVersion,
                    grantPayload.floorEpoch,
                    effectivePriority,
                    floorAlreadyGranted = true,
                    traceId = traceId
                )
                broadcastFloorGranted(session, grantPayload.encode(), traceId)
                if (result == FloorGrantResult.PREEMPTED &&
                    previousOwner != null &&
                    previousOwner != canonicalRequester
                ) {
                    replyFloorToSession(
                        session,
                        SignalType.FLOOR_PREEMPTED,
                        grantPayload.encode(),
                        previousOwner
                    )
                }
            }
            FloorGrantResult.DENIED, FloorGrantResult.STALE_VERSION -> {
                if (isLocalIdentity(session, requester)) {
                    session.ptt.onEvent(PttEvent.Rejected)
                }
                val denyPayload = FloorPayload(
                    session.floor.version(),
                    session.floor.epoch(),
                    effectivePriority,
                    session.floor.owner()?.key ?: ""
                ).encode()
                replyFloorToSession(session, SignalType.FLOOR_DENY, denyPayload, requester)
            }
        }
    }

    private fun resolveRegisteredPriority(
        endpoint: EndpointAddress,
        claimed: EndpointPriority
    ): EndpointPriority {
        val registered = if (endpoint.moduleId == localModuleId) {
            endpointRegistry.resolve(endpoint)?.priority
        } else {
            stateSync.registeredPriority(endpoint)
        }
        return FloorPriorityPolicy.effectivePriority(registered, claimed)
    }

    private fun handleFloorGranted(signal: SignalEnvelope) {
        val floorPayload = FloorPayload.decode(signal.payload)
        val traceId = floorPayload.traceId
        val session = sessions[signal.sessionId]
        if (session == null) {
            FloorTrace.grantDropped(
                traceId,
                signal.sessionId,
                "NO_SESSION",
                mapOf("from" to signal.from.key)
            )
            log("GRANT_OBSERVED sid=${signal.sessionId} resolved=NO_SESSION from=${signal.from.key}")
            return
        }
        if (session.type == SessionType.GROUP) {
            FloorTrace.grantReceived(
                traceId,
                session.id,
                signal.from.key,
                floorPayload.requesterKey,
                floorPayload.floorEpoch,
                session.floor.epoch(),
                session.floorAuthorityModuleId?.value ?: session.initiatorModuleId?.value,
                helloSnapshotEpochLabel(session),
                requestEpochLabel(session, floorPayload.requesterKey)
            )
            val owner = GroupFloorController.resolveFloorOwner(session, floorPayload.requesterKey)
            log(
                "GRANT_OBSERVED ${sessionTag(session)} sid=${session.id} " +
                    "from=${signal.from.key} requesterKey=${floorPayload.requesterKey} " +
                    "resolved=${if (owner != null) "OK" else "ROSTER_MISS"} " +
                    "grantEpoch=${floorPayload.floorEpoch} localEpoch=${session.floor.epoch()} " +
                    "ownerIsLocal=${owner != null && isLocalIdentity(session, owner)}"
            )
            if (owner == null) {
                FloorTrace.grantDropped(
                    traceId,
                    session.id,
                    "ROSTER_MISS",
                    mapOf("requesterKey" to floorPayload.requesterKey)
                )
                return
            }
            applyFloorGrant(
                session,
                owner,
                floorPayload.floorVersion,
                floorPayload.floorEpoch,
                floorPayload.priority,
                floorAlreadyGranted = false,
                traceId = traceId,
                grantFrom = signal.from.key
            )
        } else {
            FloorTrace.grantDropped(
                traceId,
                session.id,
                "NO_FLOOR_CONTROL",
                mapOf("type" to session.type.name)
            )
        }
    }

    /**
     * Single SSOT entry for applying a floor grant locally (no broadcast, no signaling).
     * [floorAlreadyGranted] true when authority [FloorState.tryGrant] already wrote floor state.
     */
    private fun applyFloorGrant(
        session: TalkbackSession,
        owner: EndpointAddress,
        floorVersion: Long,
        floorEpoch: Long,
        priority: EndpointPriority,
        floorAlreadyGranted: Boolean,
        traceId: Long = 0L,
        grantFrom: String? = null
    ) {
        if (session.type != SessionType.GROUP) return
        val completion = FloorGrantCompletion(
            owner = owner,
            floorVersion = floorVersion,
            floorEpoch = floorEpoch,
            priority = priority,
            alreadyGranted = floorAlreadyGranted
        )
        val requesterIntent = if (isLocalIdentity(session, owner)) {
            FloorOperationIdentity(session.id, owner.key)
        } else {
            null
        }
        var grantAccepted = floorAlreadyGranted
        if (!floorAlreadyGranted) {
            val localEpochBefore = session.floor.epoch()
            val staleEpoch = floorEpoch < localEpochBefore
            if (staleEpoch) {
                FloorTrace.grantDropped(
                    traceId,
                    session.id,
                    "STALE_EPOCH",
                    floorEpochDivergenceDiagnostics(
                        session = session,
                        grantFrom = grantFrom,
                        grantEpoch = floorEpoch,
                        localEpoch = localEpochBefore,
                        owner = owner
                    )
                )
            }
            val outcome = session.floorOwner.commitGrant(completion, requesterIntent)
            if (outcome.result == FloorCommitResult.DISCARDED) {
                val reason = requireNotNull(outcome.discardReason)
                FloorTrace.completionDiscarded(
                    traceId,
                    session.id,
                    reason,
                    mapOf("owner" to owner.key, "version" to floorVersion.toString())
                )
                log(
                    "COMPLETION_DISCARDED ${sessionTag(session)} sid=${session.id} " +
                        "reason=$reason owner=${owner.key} version=$floorVersion"
                )
                return
            }
            if (!staleEpoch) {
                grantAccepted = true
            }
        } else {
            val outcome = session.floorOwner.commitGrant(completion, requesterIntent)
            if (outcome.result == FloorCommitResult.DISCARDED) {
                val reason = requireNotNull(outcome.discardReason)
                FloorTrace.completionDiscarded(
                    traceId,
                    session.id,
                    reason,
                    mapOf("owner" to owner.key, "version" to floorVersion.toString())
                )
                log(
                    "COMPLETION_DISCARDED ${sessionTag(session)} sid=${session.id} " +
                        "reason=$reason owner=${owner.key} version=$floorVersion"
                )
                return
            }
        }
        onGrantAppliedForMetrics(session)
        if (isLocalIdentity(session, owner)) {
            onLocalProtocolFloorAcquired(session)
        } else {
            acquireReleaseWatchdog.onFloorLost(session.id)
            session.ptt.onEvent(PttEvent.Release)
            session.pendingTransmit = false
            stopSessionCapture(session)
        }
        syncProgramRelay(session)
        updateSessionReceivePlayback(session)
        if (grantAccepted) {
            FloorTrace.grantApplied(
                traceId,
                session.id,
                owner.key,
                session.floor.epoch(),
                session.floor.version()
            )
        }
    }

    private fun broadcastFloorGranted(session: TalkbackSession, grantPayload: String, traceId: Long = 0L) {
        val decoded = FloorPayload.decode(grantPayload)
        val targets = targetsForSession(session).joinToString(",") { (peer, remote) ->
            "${remote.key}@${peer.host}:${peer.port}"
        }
        FloorTrace.grantBroadcast(
            traceId,
            session.id,
            decoded.floorEpoch,
            decoded.floorVersion,
            decoded.requesterKey,
            "[$targets]"
        )
        log(
            "GRANT_BROADCAST ${sessionTag(session)} sid=${session.id} " +
                "targets=[$targets]"
        )
        broadcastFloorSignal(session, SignalType.FLOOR_GRANTED, grantPayload)
    }

    private fun handleFloorDeny(signal: SignalEnvelope) {
        val session = sessions[signal.sessionId] ?: return
        if (!session.type.usesFloorControl()) return
        if (signal.to != null && isLocalIdentity(session, signal.to)) {
            session.ptt.onEvent(PttEvent.Rejected)
            session.pendingTransmit = false
            acquireReleaseWatchdog.onFloorLost(session.id)
            stopSessionCapture(session)
        }
    }

    private fun handleFloorPreempted(signal: SignalEnvelope) {
        val session = sessions[signal.sessionId] ?: return
        if (!session.type.usesFloorControl()) return
        val to = signal.to ?: return
        if (!isLocalIdentity(session, to)) return
        session.ptt.onEvent(PttEvent.Rejected)
        session.pendingTransmit = false
        acquireReleaseWatchdog.onFloorLost(session.id)
        stopSessionCapture(session)
        session.localFloorPreempted = true
    }

    private fun handleFloorRelease(signal: SignalEnvelope) {
        val session = sessions[signal.sessionId] ?: return
        if (!session.type.usesFloorControl()) return
        val oldOwner = session.floor.owner()
        val versionBefore = session.floor.version()
        val epochBefore = session.floor.epoch()
        val fromMatchesOwner = oldOwner != null && oldOwner == signal.from
        val released = session.floor.release(signal.from)
        val newOwner = session.floor.owner()
        log(
            "FLOOR_RELEASE_DIAG ${sessionTag(session)} sid=${session.id} " +
                "hash=${System.identityHashCode(session)} " +
                "oldOwner=${oldOwner?.key ?: "null"} from=${signal.from.key} " +
                "fromMatchesOwner=$fromMatchesOwner released=$released " +
                "newOwner=${newOwner?.key ?: "null"} " +
                "version=$versionBefore->${session.floor.version()} epoch=$epochBefore"
        )
        if (isLocalIdentity(session, signal.from)) {
            session.ptt.onEvent(PttEvent.Release)
        }
        acquireReleaseWatchdog.onFloorLost(session.id)
        if (!isLocalFloorHolder(session)) {
            stopSessionCapture(session)
        }
        syncProgramRelay(session)
        updateSessionReceivePlayback(session)
    }

    private fun handleHangup(signal: SignalEnvelope) {
        val pendingInvite = findPendingConferenceInviteBySessionId(signal.sessionId)
        val removed = sessions.remove(signal.sessionId)
        if (removed == null) {
            if (pendingInvite != null) {
                pendingConferenceInvitesByChannel.remove(pendingInvite.channelId)
                releaseConferenceRuntimeAfterRemoteTermination(
                    pendingInvite.channelId,
                    "PENDING_CONFERENCE_ABORT_REMOTE"
                )
            }
            return
        }
        clearPendingConferenceInvite(sessionId = signal.sessionId, channelId = removed.channelId)
        val wasUnicast = removed.type == SessionType.UNICAST
        pendingGroupJoinsBySession.remove(signal.sessionId)
        pendingIceBySession.remove(signal.sessionId)
        removed.remote?.moduleId?.value?.let { reDialByRemoteModule.remove(it) }
        removed.remotePeersByModule.keys.forEach { reDialByRemoteModule.remove(it) }
        if (removed.type == SessionType.CONFERENCE) {
            removed.channelId?.let { clearRejoinHintsForSession(it, removed.id) }
        }
        removed.channelId?.let { ch ->
            groupMeshReconciler.clearChannel(ch)
            if (sessions.values.none { it.channelId == ch && it.type == SessionType.CONFERENCE }) {
                if (removed.type == SessionType.CONFERENCE) {
                    releaseConferenceRuntimeAfterRemoteTermination(ch, "remote_hangup")
                } else {
                    releaseChannelModeIfIdle(ch)
                }
            }
        }
        removed.ptt.onEvent(PttEvent.RemoteHangup)
        releaseSessionMedia(removed)
        log("${sessionTag(removed)} Remote hangup")
        if (wasUnicast) {
            resumeGroupSessionsAfterUnicast(signal.sessionId)
        }
    }

    private fun membershipDecisionForensics(session: TalkbackSession): Triple<String, String, List<String>> {
        val conferenceState = if (session.accepted) "ACTIVE" else "PENDING"
        val recovery = ConferenceMemberDecisionTrace.recoverySummaryForSession(
            conferenceEdgeRecoveryController,
            session.id
        )
        val pending = conferenceEdgeRecoveryController.pendingForensics(session.id)
        return Triple(conferenceState, recovery, pending)
    }

    private fun emitConferenceRecoveryOwnership(
        reason: String,
        session: TalkbackSession,
        participantId: String,
        supersededFromAttempt: Long? = null,
        membershipMutationDecision: ConferenceRecoveryOwnershipLog.MembershipMutationDecisionSnapshot? = null
    ) {
        if (session.type != SessionType.CONFERENCE) return
        val leftKeys = conferenceParticipantManager.leftMemberEndpoints(session.id)?.keys ?: emptySet()
        ConferenceRecoveryOwnershipLog.emitFromSession(
            reason = reason,
            session = session,
            localModuleId = localModuleId.value,
            participantId = participantId,
            controller = conferenceEdgeRecoveryController,
            leftMemberModuleIds = leftKeys,
            supersededFromAttempt = supersededFromAttempt,
            membershipMutationDecision = membershipMutationDecision
        )
    }

    private fun handleGroupLeave(signal: SignalEnvelope) {
        val session = sessions[signal.sessionId] ?: return
        if (session.type != SessionType.CONFERENCE) return
        val leavingModuleId = signal.from.moduleId.value
        if (leavingModuleId == localModuleId.value) return
        val (conferenceState, recoverySummary, pendingActions) = membershipDecisionForensics(session)
        val isHostLeave = leavingModuleId == session.initiatorModuleId?.value
        ConferenceMemberDecisionTrace.groupLeaveReceived(
            sessionId = session.id,
            fromModule = leavingModuleId,
            localModule = localModuleId.value,
            isHostLeave = isHostLeave,
            conferenceState = conferenceState,
            recoverySummary = recoverySummary,
            pendingActions = pendingActions,
            rosterEpoch = session.rosterEpoch,
            signalTsMs = signal.timestampMs
        )
        if (isHostLeave) {
            log("[${session.traceId}] Conference host left via GROUP_LEAVE, ending session")
            hangupInternal(session.id)
            session.channelId?.let { channelId ->
                clearConferenceRejoinState(channelId)
                log("CONFERENCE_TERMINATED ch=$channelId reason=host_group_leave clearRejoinState=true")
            }
            return
        }
        // Authority prune leave carries the post-commit rosterEpoch (G-R29-E4).
        // Do not apply the leaver's member list — it can be incomplete mid-mesh.
        val payload = GroupSessionPayload.decode(signal.payload)
        if (payload != null && payload.rosterEpoch > 0L && payload.rosterEpoch >= session.rosterEpoch) {
            session.rosterEpoch = payload.rosterEpoch
            if (payload.rosterEpochMs > 0L) {
                session.rosterEpochMs = payload.rosterEpochMs
            }
        }
        markConferenceParticipantLeft(session, leavingModuleId)
        emitConferenceRuntimeProjection(session)
        repairConferenceMeshAfterLeave(session)
        log(
            "[${session.traceId}] Conference peer left: $leavingModuleId " +
                "remaining=${session.groupMembers.size} connected=${countConnectedRemotes(session)} " +
                "rosterEpoch=${session.rosterEpoch}"
        )
    }

    private fun leaveConferenceInternal(sessionId: String, reason: String, caller: String) {
        val active = sessions[sessionId] ?: return
        val (conferenceState, recoverySummary, pendingActions) = membershipDecisionForensics(active)
        val isHost = active.initiatorModuleId == localModuleId
        ConferenceMemberDecisionTrace.localLeaveRequest(
            sessionId = sessionId,
            participant = localModuleId.value,
            caller = caller,
            reason = reason,
            conferenceState = conferenceState,
            recoverySummary = recoverySummary,
            pendingActions = pendingActions,
            rosterEpoch = active.rosterEpoch,
            isHost = isHost
        )
        if (isHost) {
            log("[${active.traceId}] Conference host leaving, ending for all")
            hangupInternal(sessionId)
            return
        }
        val session = sessions.remove(sessionId) ?: return
        pendingIceBySession.remove(sessionId)
        pendingGroupJoinsBySession.remove(sessionId)
        rememberRejoinableConference(session)
        session.remote?.moduleId?.value?.let { reDialByRemoteModule.remove(it) }
        session.remotePeersByModule.keys.forEach { reDialByRemoteModule.remove(it) }
        val remainingMemberKeys = session.groupMembers
            .filter { it.moduleId != session.local.moduleId }
            .map { it.key }
        val leavePayload = GroupSessionPayload(
            sdp = "",
            channelId = session.channelId ?: "",
            members = remainingMemberKeys,
            initiatorModuleId = session.initiatorModuleId?.value ?: session.local.moduleId.value,
            floorAuthorityModuleId = session.floorAuthorityModuleId?.value ?: session.local.moduleId.value,
            sessionMode = MeshSessionMode.CONFERENCE
        ).encode()
        val targets = targetsForSession(session)
        ConferenceMemberDecisionTrace.groupLeaveSent(
            sessionId = session.id,
            fromModule = session.local.moduleId.value,
            caller = caller,
            reason = reason,
            targetCount = targets.size,
            conferenceState = conferenceState,
            recoverySummary = recoverySummary,
            pendingActions = pendingActions,
            rosterEpoch = session.rosterEpoch
        )
        targets.forEach { (peer, remote) ->
            sendSignal(
                peer,
                buildSignedEnvelope(
                    SignalType.GROUP_LEAVE,
                    session.local,
                    remote,
                    session.id,
                    leavePayload
                )
            )
        }
        session.channelId?.let { ch ->
            releaseChannelModeIfIdle(ch)
        }
        releaseSessionMedia(session)
        session.ptt.onEvent(PttEvent.RemoteHangup)
        log("[${session.traceId}] Left conference locally notified=${remainingMemberKeys.size}")
    }

    private fun applyConferenceRoster(session: TalkbackSession, members: List<EndpointAddress>) {
        val memberModuleIds = members.map { it.moduleId.value }.toSet()
        remoteModuleIds(session).toList().forEach { moduleId ->
            if (moduleId !in memberModuleIds) {
                qosMonitor.resetRemote(moduleId)
                mediaRegistry.releaseGroup(moduleId)
                session.remotePeersByModule.remove(moduleId)
                session.meshCompletedModules.remove(moduleId)
                session.memberModules.remove(ModuleId(moduleId))
                reDialByRemoteModule.remove(moduleId)
            }
        }
        conferenceParticipantManager.replaceRoster(session.id, members)
        members.forEach { session.memberModules.add(it.moduleId) }
        session.memberModules.add(localModuleId)
        session.touch()
    }

    private fun removeConferenceParticipant(
        session: TalkbackSession,
        moduleId: String,
        source: AuthorityMembershipMutationSource
    ) {
        val wasJoined = session.memberModules.any { it.value == moduleId }
        val (conferenceState, recoverySummary, pendingActions) = membershipDecisionForensics(session)
        ConferenceMemberDecisionTrace.authorityMemberDecision(
            sessionId = session.id,
            participant = moduleId,
            decision = "REMOVE",
            source = source,
            oldMembership = if (wasJoined) "JOINED" else "UNKNOWN",
            conferenceState = conferenceState,
            recoverySummary = recoverySummary,
            pendingActions = pendingActions,
            rosterEpoch = session.rosterEpoch,
            remaining = session.groupMembers.size
        )
        val pruneReason = when (source) {
            AuthorityMembershipMutationSource.AUTHORITY_PRUNE -> "AUTHORITY_PRUNE"
            AuthorityMembershipMutationSource.AUTHORITY_GROUP_LEAVE -> "AUTHORITY_GROUP_LEAVE"
            AuthorityMembershipMutationSource.USER_LEAVE -> "USER_LEAVE"
            AuthorityMembershipMutationSource.HOST_TERMINATE -> "HOST_TERMINATE"
        }
        ConferenceRecoveryOwnershipLog.emitMembershipMutationDecision(
            session = session,
            localModuleId = localModuleId.value,
            participantId = moduleId,
            controller = conferenceEdgeRecoveryController,
            type = ConferenceRecoveryOwnershipLog.MembershipMutationDecisionType.PRUNE,
            reason = pruneReason,
            source = source
        )
        if (source == AuthorityMembershipMutationSource.AUTHORITY_PRUNE) {
            commitAuthorityPrune(session, moduleId)
            return
        }
        applyConferenceMembershipLeaveLocal(session, moduleId, source)
    }

    /**
     * ADR-0024 R29-E.3 authority prune transaction. Partial execution forbidden.
     *
     * 1. mutate canonical roster (+ bump rosterEpoch)
     * 2. mutate memberModules
     * 3. emit GROUP_LEAVE / roster broadcast
     * 4. publish projection update
     * 5. release media bindings (floor / cancelEdge / releaseMeshPeer)
     */
    private fun commitAuthorityPrune(session: TalkbackSession, moduleId: String) {
        if (moduleId == localModuleId.value) return
        val prunedEndpoint = meshRoster(session).firstOrNull { it.moduleId.value == moduleId }
            ?: return
        val broadcastTargets = targetsForSession(session)
            .filter { it.second.moduleId.value != moduleId }
        if (conferenceParticipantManager.applyPrune(session.id, moduleId) == null) {
            return
        }
        bumpRosterEpoch(session, "authority_prune")
        session.memberModules.remove(ModuleId(moduleId))

        broadcastAuthorityPruneLeave(session, prunedEndpoint, broadcastTargets)
        emitConferenceRuntimeProjection(session)

        releaseFloorIfHolderUnavailable(session, moduleId)
        conferenceEdgeRecoveryController.cancelEdge(session.id, moduleId, "member_left")
        releaseMeshPeer(session, moduleId)
        if (session.remote?.moduleId?.value == moduleId) {
            val nextRemote = meshRoster(session).firstOrNull { it.moduleId != localModuleId }
            session.remote = nextRemote
            session.remotePeer = nextRemote?.moduleId?.value?.let { session.remotePeersByModule[it] }
        }
        session.touch()
        resumeConferenceAudioAfterPeerLeft(session)
        log(
            "[${session.traceId}] Conference membership mutation remote=$moduleId " +
                "source=${AuthorityMembershipMutationSource.AUTHORITY_PRUNE} " +
                "remaining=${session.groupMembers.size} rosterEpoch=${session.rosterEpoch}"
        )
    }

    private fun broadcastAuthorityPruneLeave(
        session: TalkbackSession,
        pruned: EndpointAddress,
        targets: List<Pair<PeerTarget, EndpointAddress>>
    ) {
        val leavePayload = groupPayloadBase(session).copy(sdp = "").encode()
        targets.forEach { (peer, remote) ->
            sendSignal(
                peer,
                buildSignedEnvelope(
                    SignalType.GROUP_LEAVE,
                    pruned,
                    remote,
                    session.id,
                    leavePayload
                )
            )
        }
        log(
            "[${session.traceId}] AUTHORITY_PRUNE broadcast leave remote=${pruned.moduleId.value} " +
                "targets=${targets.size} rosterEpoch=${session.rosterEpoch}"
        )
    }

    /** Local apply of an authoritative leave (received GROUP_LEAVE / peer-left path). */
    private fun applyConferenceMembershipLeaveLocal(
        session: TalkbackSession,
        moduleId: String,
        source: AuthorityMembershipMutationSource
    ) {
        if (moduleId == localModuleId.value) return
        releaseFloorIfHolderUnavailable(session, moduleId)
        conferenceParticipantManager.applyPrune(session.id, moduleId)
        conferenceEdgeRecoveryController.cancelEdge(session.id, moduleId, "member_left")
        session.memberModules.remove(ModuleId(moduleId))
        releaseMeshPeer(session, moduleId)
        if (session.remote?.moduleId?.value == moduleId) {
            val nextRemote = meshRoster(session).firstOrNull { it.moduleId != localModuleId }
            session.remote = nextRemote
            session.remotePeer = nextRemote?.moduleId?.value?.let { session.remotePeersByModule[it] }
        }
        session.touch()
        resumeConferenceAudioAfterPeerLeft(session)
        log(
            "[${session.traceId}] Conference membership mutation remote=$moduleId source=$source " +
                "remaining=${session.groupMembers.size}"
        )
    }

    /**
     * Participant-side media degradation (ADR-0023 R29-C). Records recovery facts only;
     * MUST NOT mutate membership or terminate edge recovery obligation.
     */
    private fun recordParticipantMediaDegradation(session: TalkbackSession, moduleId: String) {
        log(
            "[${session.traceId}] RECOVERY_MEDIA_DEGRADED session=${session.id} remote=$moduleId"
        )
        emitConferenceRuntimeProjection(session)
    }

    /**
     * Drop an invitee from a mesh roster and release any WebRTC engine created for them.
     * Returns false when the signal should be ignored (e.g. BUSY from an already-connected peer).
     */
    private fun evictMeshInvitee(
        session: TalkbackSession,
        moduleId: String,
        inviteState: InviteState,
        signalPayload: String
    ): Boolean {
        if (moduleId == localModuleId.value) return false
        val connected = qosMonitor.isGroupConnected(moduleId)
        if (connected && (signalPayload == "BUSY" || signalPayload == "EXPIRED")) {
            log(
                "[${session.traceId}] Ignoring $signalPayload from connected peer $moduleId " +
                    "(duplicate invite)"
            )
            return false
        }
        if (session.type == SessionType.CONFERENCE) {
            if (!conferenceParticipantManager.evictInvitee(session.id, moduleId, inviteState)) {
                return false
            }
            session.memberModules.remove(ModuleId(moduleId))
            releaseMeshPeer(session, moduleId)
            if (session.remote?.moduleId?.value == moduleId) {
                val nextRemote = meshRoster(session).firstOrNull { it.moduleId != localModuleId }
                session.remote = nextRemote
                session.remotePeer = nextRemote?.moduleId?.value?.let { session.remotePeersByModule[it] }
            }
            return true
        }
        if (session.type == SessionType.GROUP) {
            val wasPending = session.pendingInviteeEndpoints.remove(moduleId) != null
            val wasCanonical = session.groupMembers.any { it.moduleId.value == moduleId }
            if (wasPending && !wasCanonical) {
                session.memberModules.remove(ModuleId(moduleId))
                meshParticipant(session,moduleId).invite = inviteState
                releaseMeshPeer(session, moduleId)
                return true
            }
        }
        session.groupMembers = session.groupMembers.filter { it.moduleId.value != moduleId }
        session.memberModules.remove(ModuleId(moduleId))
        meshParticipant(session,moduleId).invite = inviteState
        releaseMeshPeer(session, moduleId)
        if (session.remote?.moduleId?.value == moduleId) {
            val nextRemote = session.groupMembers.firstOrNull { it.moduleId != localModuleId }
            session.remote = nextRemote
            session.remotePeer = nextRemote?.moduleId?.value?.let { session.remotePeersByModule[it] }
        }
        return true
    }

    private fun releaseMeshPeer(session: TalkbackSession, moduleId: String) {
        releasePeerMediaOnly(session, moduleId)
    }

    /** Release WebRTC engine and peer maps without mutating conference roster membership. */
    private fun releasePeerMediaOnly(session: TalkbackSession, moduleId: String, resetQos: Boolean = true) {
        session.remotePeersByModule.remove(moduleId)
        session.meshCompletedModules.remove(moduleId)
        reDialByRemoteModule.remove(moduleId)
        if (resetQos) {
            qosMonitor.resetRemote(moduleId)
        }
        mediaRegistry.releaseGroup(moduleId)
    }

    private fun meshMediaModuleIds(session: TalkbackSession): Set<String> {
        val ids = linkedSetOf<String>()
        ids.addAll(session.remotePeersByModule.keys)
        session.groupMembers.forEach { member ->
            val id = member.moduleId.value
            if (id != localModuleId.value) {
                ids.add(id)
            }
        }
        return ids
    }

    private fun groupMeshModulesOnChannel(channelId: String): Set<String> =
        sessions.values
            .filter { it.channelId == channelId && it.type == SessionType.GROUP }
            .flatMap { meshMediaModuleIds(it) }
            .toSet()

    private fun canSendConferenceInvite(
        session: TalkbackSession,
        moduleId: String,
        forReconnect: Boolean = false
    ): Boolean {
        if (forReconnect) return true
        if (qosMonitor.isGroupConnected(moduleId)) return false
        val participant = meshParticipant(session,moduleId)
        if (participant.invite == InviteState.INVITING || participant.invite == InviteState.RINGING) {
            if (System.currentTimeMillis() - participant.invitedAtMs < CONFERENCE_INVITE_MIN_INTERVAL_MS) {
                return false
            }
        }
        return true
    }

    private fun conferenceMemberRemoteIds(session: TalkbackSession): Set<String> =
        if (isConferenceSession(session)) {
            conferenceParticipantManager.remoteModuleIds(session.id, localModuleId)
        } else {
            session.groupMembers
                .asSequence()
                .map { it.moduleId.value }
                .filter { it != localModuleId.value }
                .toSet()
        }

    private fun resumeConferenceAudioAfterPeerLeft(session: TalkbackSession) {
        if (session.type != SessionType.CONFERENCE || !session.accepted || session.muted) return
        val peers = conferenceMemberRemoteIds(session)
        if (peers.isEmpty()) {
            tryEnsureConferenceDuplex(session)
            return
        }
        val anyConnected = peers.any { id ->
            mediaRegistry.getMesh(id) != null && qosMonitor.isGroupConnected(id)
        }
        if (anyConnected) {
            ensureConferenceDuplex(session)
        }
    }

    private fun repairConferenceMeshAfterLeave(session: TalkbackSession) {
        if (session.type != SessionType.CONFERENCE || !session.accepted) return
        val remaining = meshRoster(session).map { it.moduleId }.toSet()
        if (remaining.size <= 1) {
            resumeConferenceAudioAfterPeerLeft(session)
            return
        }
        val anchor = session.initiatorModuleId
        val hostStillPresent = anchor != null && anchor in remaining
        if (!hostStillPresent) {
            session.initiatorModuleId = remaining.minBy { it.value }
        }
        completeGroupMesh(session)
        drainPendingGroupJoins(session.id)
        scheduleGroupMeshRetries(session.id)
        resumeConferenceAudioAfterPeerLeft(session)
    }

    private fun hangupInternal(sessionId: String) {
        cancelConferenceHostIceHangup(sessionId)
        cancelAllParticipantPrunes(sessionId)
        acquireReleaseWatchdog.onFloorLost(sessionId)
        conferenceReconnectStartedAtBySession.remove(sessionId)
        val session = sessions.remove(sessionId) ?: return
        if (session.type == SessionType.CONFERENCE) {
            auditConferenceSessionLifecycle(
                event = "LOCAL_HANGUP",
                session = session,
                writer = "hangupInternal",
                cause = "local_hangup"
            )
            lastAuthorityReachableBySession.remove(sessionId)
            lastRecoveryCapabilityByEdge.keys.removeIf { it.sessionId == sessionId }
            lastConferenceRuntimeDecisionBySession.remove(sessionId)
            lastConferenceRuntimeMissingByPeer.clear()
            lastConferenceBarrierCanPublishBySession.remove(sessionId)
        }
        val wasUnicast = session.type == SessionType.UNICAST
        if (session.type == SessionType.CONFERENCE) {
            session.channelId?.let { channelId ->
                conferenceEdgeRecoveryController.cancelChannel(channelId, "local_hangup")
                clearRejoinHintsForSession(channelId, session.id)
                cancelHostRejoinRetry(channelId)
            }
            conferenceEdgeRecoveryController.cancelSession(session.id, "local_hangup")
            notifySoftLeftParticipantsMeetingEnded(session)
            disposeConferenceParticipantState(session.id)
        }
        pendingIceBySession.remove(sessionId)
        pendingGroupJoinsBySession.remove(sessionId)
        session.remote?.moduleId?.value?.let { reDialByRemoteModule.remove(it) }
        session.remotePeersByModule.keys.forEach { reDialByRemoteModule.remove(it) }
        hangupTargetsForSession(session).forEach { (peer, remote) ->
            sendSignal(
                peer,
                buildSignedEnvelope(
                    SignalType.HANGUP,
                    session.local,
                    remote,
                    session.id,
                    "LOCAL_HANGUP"
                )
            )
        }
        clearPendingConferenceInvite(sessionId = session.id, channelId = session.channelId)
        session.channelId?.let { ch ->
            groupMeshReconciler.clearChannel(ch)
            if (sessions.values.none { it.channelId == ch && it.type == SessionType.CONFERENCE }) {
                if (session.type == SessionType.CONFERENCE) {
                    releaseConferenceChannelForGroupPtt(ch, "local_hangup")
                } else if (sessions.values.none { it.channelId == ch }) {
                    releaseChannelModeIfIdle(ch)
                }
            }
        }
        releaseSessionMedia(session)
        session.ptt.onEvent(PttEvent.RemoteHangup)
        log("${sessionTag(session)} Hangup")
        if (wasUnicast) {
            resumeGroupSessionsAfterUnicast(session.id)
        }
    }

    private fun releaseSessionMedia(session: TalkbackSession) {
        setPlaybackEnabled(session, enabled = false, reason = "release_media")
        stopSessionCapture(session)
        programAudioBus.clear(session.id)
        conferenceAudioBus.clear(session.id)
        receivePathLivenessObserver.clearSession(session.id)
        if (isConferenceSession(session)) {
            val recoveryModules = linkedSetOf<String>()
            recoveryModules.addAll(meshMediaModuleIds(session))
            session.initiatorModuleId?.value?.let { recoveryModules.add(it) }
            recoveryModules.forEach { conferenceRecoveryController.clearRecovery(it) }
        }
        if (session.type == SessionType.UNICAST) {
            qosMonitor.resetUnicast(session.id)
            mediaRegistry.releaseUnicast(session.id)
        } else {
            meshMediaModuleIds(session).forEach { moduleId ->
                qosMonitor.resetGroup(moduleId)
                mediaRegistry.releaseGroup(moduleId)
            }
        }
    }

    private fun sessionMediaEngines(session: TalkbackSession): List<WebRtcAudioEngine> {
        if (session.type == SessionType.UNICAST) {
            return listOfNotNull(mediaRegistry.getUnicast(session.id))
        }
        val moduleIds = session.remotePeersByModule.keys
        if (moduleIds.isNotEmpty()) {
            return moduleIds.mapNotNull { meshEngineForSession(session, it) }
        }
        val fallback = session.remote?.moduleId?.value ?: return emptyList()
        return listOfNotNull(meshEngineForSession(session, fallback))
    }

    private fun enginesForAudioLevel(session: TalkbackSession): List<WebRtcAudioEngine> {
        val moduleIds = linkedSetOf<String>()
        moduleIds.addAll(session.remotePeersByModule.keys)
        session.groupMembers.forEach { member ->
            val id = member.moduleId.value
            if (id != localModuleId.value) {
                moduleIds.add(id)
            }
        }
        return moduleIds.mapNotNull { meshEngineForSession(session, it) }
    }

    private fun refreshConferenceAudioLevels() {
        sessions.values
            .filter { it.type == SessionType.CONFERENCE && it.accepted && !it.muted }
            .forEach { session ->
                enginesForAudioLevel(session).forEach { engine ->
                    engine.refreshAudioLevel()
                }
            }
    }

    private fun moduleIdFromEndpointKey(key: String): String? {
        val dash = key.indexOf('-')
        return if (dash <= 0) key.uppercase() else key.substring(0, dash).uppercase()
    }

    private fun displayAudioLevel(linear: Float): Float {
        val clamped = linear.coerceIn(0f, 1f)
        if (clamped < 0.01f) return 0f
        return kotlin.math.sqrt(clamped).coerceIn(0f, 1f)
    }

    private fun ensureUnicastDuplex(session: TalkbackSession) {
        if (session.type != SessionType.UNICAST || !session.accepted) return
        audioRouter.selectInput(session.local)
        startSessionCapture(session)
        if (session.muted) {
            sessionMediaEngines(session).forEach { it.setMuted(true) }
        }
    }

    private fun markUnicastConnected(session: TalkbackSession) {
        if (session.type != SessionType.UNICAST) return
        session.unicastPhase = UnicastCallPhase.CONNECTED
        ensureUnicastDuplex(session)
        updateSessionReceivePlayback(session)
    }

    private fun ensureConferenceDuplex(session: TalkbackSession) {
        if (session.type != SessionType.CONFERENCE || !session.accepted) return
        audioRouter.selectInput(session.local)
        if (!session.muted) {
            startSessionCapture(session)
        }
    }

    private fun tryEnsureConferenceDuplex(session: TalkbackSession) {
        if (session.type != SessionType.CONFERENCE || !session.accepted) return
        if (!isSessionTransmitReady(session)) return
        ensureConferenceDuplex(session)
    }

    private fun canPublishAudio(session: TalkbackSession): Boolean {
        if (session.type != SessionType.CONFERENCE) return true
        return ConferenceMediaTransmitGate.canPublishConferenceAudio(
            ConferenceMediaTransmitGate.Input(
                localConferenceActive = session.accepted,
                localMuted = session.muted,
                localPublisherReady = connectedConferencePeerIds(session).isNotEmpty()
            )
        )
    }

    private fun connectedConferencePeerIds(session: TalkbackSession): Set<String> =
        conferenceMemberRemoteIds(session).filter { isPeerMediaConnected(it) }.toSet()

    private fun conferenceBarrierSnapshot(session: TalkbackSession) =
        ConferenceBarrierDiagnostics.snapshot(
            sessionId = session.id,
            joinedPeers = conferenceMemberRemoteIds(session),
            connectedPeers = connectedConferencePeerIds(session),
            controller = conferenceEdgeRecoveryController,
            isIceConnected = { qosMonitor.isGroupConnected(it) },
            gateInput = ConferenceMediaTransmitGate.Input(
                localConferenceActive = session.accepted,
                localMuted = session.muted,
                localPublisherReady = connectedConferencePeerIds(session).isNotEmpty()
            )
        )

    private fun logConferenceBarrierSnapshot(session: TalkbackSession, action: String) {
        if (session.type != SessionType.CONFERENCE) return
        log(ConferenceBarrierDiagnostics.formatLog(action, conferenceBarrierSnapshot(session)))
    }

    private fun applyConferenceTransmitBarrier(session: TalkbackSession, cause: String) {
        if (session.type != SessionType.CONFERENCE || !session.accepted) return
        val canPublish = canPublishAudio(session)
        val previous = lastConferenceBarrierCanPublishBySession[session.id]
        if (previous == false && canPublish) {
            maybeResumeConferenceTransmitAfterBarrierUnblock(session, cause)
        }
        if (!canPublish && sessionMediaEngines(session).any { it.isCapturing() }) {
            if (!session.muted) {
                session.conferenceTransmitSuspendedByBarrier = true
            }
            lastConferenceBarrierCanPublishBySession[session.id] = false
            logConferenceBarrierSnapshot(session, "stop_capture")
            stopSessionCapture(session)
            return
        }
        val shouldLog = !canPublish || previous != null && previous != canPublish
        lastConferenceBarrierCanPublishBySession[session.id] = canPublish
        if (shouldLog) {
            logConferenceBarrierSnapshot(session, cause)
        }
    }

    private fun maybeResumeConferenceTransmitAfterBarrierUnblock(
        session: TalkbackSession,
        cause: String
    ) {
        if (session.muted || !session.conferenceTransmitSuspendedByBarrier) return
        session.conferenceTransmitSuspendedByBarrier = false
        logConferenceBarrierSnapshot(session, "resume_transmit_$cause")
        tryEnsureConferenceDuplex(session)
    }

    private fun startSessionCapture(session: TalkbackSession) {
        if (!canPublishAudio(session)) {
            lastConferenceBarrierCanPublishBySession[session.id] = false
            logConferenceBarrierSnapshot(session, "block_capture")
            return
        }
        if (session.type == SessionType.GROUP) {
            val floorOwner = session.floor.owner()
            val localIdentity = groupLocalIdentity(session)
            if (floorOwner != null && !isLocalIdentity(session, floorOwner)) {
                invariantF1BreakCount++
                TalkbackLog.e(
                    "INVARIANT_F1_BREAK session=${session.id} " +
                        "local=${localIdentity.key} floorOwner=${floorOwner.key}"
                )
                return
            }
        }
        PttTimingLog.captureOn(session.id)
        acquireReleaseWatchdog.onCaptureStarted(session.id)
        sessionMediaEngines(session).forEach { it.startCapture() }
    }

    private fun stopSessionCapture(session: TalkbackSession) {
        PttTimingLog.captureOff(session.id)
        sessionMediaEngines(session).forEach { it.stopCapture() }
    }

    private fun dispatchFloorRequestCancel(session: TalkbackSession, requestVersion: Long) {
        if (!session.type.usesFloorControl()) return
        val localIdentity = groupLocalIdentity(session)
        val payload = FloorPayload.forRequest(
            localIdentity,
            requestVersion,
            session.floor.epoch(),
            EndpointPriority.NORMAL
        ).encode()
        if (GroupFloorController.isFloorAuthority(session, localModuleId.value)) {
            handleFloorRequestCancel(syntheticFloorRequestCancel(session, payload))
            return
        }
        sendFloorRequestCancelToAuthority(session, payload)
    }

    private fun syntheticFloorRequestCancel(session: TalkbackSession, payload: String): SignalEnvelope =
        SignalEnvelope(
            type = SignalType.FLOOR_REQUEST_CANCEL,
            from = groupLocalIdentity(session),
            to = groupLocalIdentity(session),
            sessionId = session.id,
            timestampMs = System.currentTimeMillis(),
            payload = payload,
            nonce = "",
            signature = ""
        )

    private fun sendFloorRequestCancelToAuthority(session: TalkbackSession, payload: String) {
        if (!session.type.usesFloorControl()) return
        val authorityId = session.floorAuthorityModuleId ?: session.initiatorModuleId ?: return
        val peer = session.remotePeersByModule[authorityId.value] ?: return
        val remote = endpointForModule(session, authorityId)
        sendSignal(
            peer,
            buildSignedEnvelope(
                SignalType.FLOOR_REQUEST_CANCEL,
                groupLocalIdentity(session),
                remote,
                session.id,
                payload
            )
        )
        log(
            "FLOOR_REQUEST_CANCEL_SEND ${sessionTag(session)} sid=${session.id} " +
                "authority=${authorityId.value} version=${FloorPayload.decode(payload).floorVersion}"
        )
    }

    private fun broadcastFloorRequest(session: TalkbackSession, payload: String) {
        targetsForSession(session).forEach { (peer, remote) ->
            sendSignal(
                peer,
                buildSignedEnvelope(SignalType.FLOOR_REQUEST, groupLocalIdentity(session), remote, session.id, payload)
            )
        }
    }

    private fun dispatchFloorRequest(session: TalkbackSession, payload: String, traceId: Long) {
        if (!session.type.usesFloorControl()) return
        val floorPayload = FloorPayload.decode(payload)
        val effectiveTraceId = traceId.takeIf { it > 0L } ?: floorPayload.traceId
        val authority = session.floorAuthorityModuleId?.value ?: session.initiatorModuleId?.value
        if (GroupFloorController.isFloorAuthority(session, localModuleId.value)) {
            FloorTrace.requestSend(
                effectiveTraceId,
                session.id,
                groupLocalIdentity(session).key,
                floorPayload.floorEpoch,
                floorPayload.floorVersion,
                authority,
                "SEND_OK_LOCAL"
            )
            processFloorArbitration(session, syntheticFloorRequest(session, payload), floorPayload)
            log(
                "FLOOR_REQUEST_SEND ${sessionTag(session)} sid=${session.id} " +
                    "hash=${System.identityHashCode(session)} result=SEND_OK_LOCAL"
            )
            return
        }
        sendFloorRequestToAuthority(session, payload, effectiveTraceId)
    }

    private fun syntheticFloorRequest(session: TalkbackSession, payload: String): SignalEnvelope =
        SignalEnvelope(
            type = SignalType.FLOOR_REQUEST,
            from = groupLocalIdentity(session),
            to = groupLocalIdentity(session),
            sessionId = session.id,
            timestampMs = System.currentTimeMillis(),
            payload = payload,
            nonce = "",
            signature = ""
        )

    private fun sendFloorRequestToAuthority(session: TalkbackSession, payload: String, traceId: Long) {
        if (!session.type.usesFloorControl()) return
        val floorPayload = FloorPayload.decode(payload)
        val authorityId = session.floorAuthorityModuleId ?: session.initiatorModuleId ?: run {
            FloorTrace.requestSend(
                traceId,
                session.id,
                groupLocalIdentity(session).key,
                floorPayload.floorEpoch,
                floorPayload.floorVersion,
                null,
                "AUTHORITY_MISSING"
            )
            log(
                "FLOOR_REQUEST_SEND ${sessionTag(session)} sid=${session.id} " +
                    "hash=${System.identityHashCode(session)} result=AUTHORITY_MISSING"
            )
            log("[${session.traceId}] Floor request failed: no floor authority")
            return
        }
        val peer = session.remotePeersByModule[authorityId.value] ?: run {
            FloorTrace.requestSend(
                traceId,
                session.id,
                groupLocalIdentity(session).key,
                floorPayload.floorEpoch,
                floorPayload.floorVersion,
                authorityId.value,
                "PEER_UNREACHABLE"
            )
            log(
                "FLOOR_REQUEST_SEND ${sessionTag(session)} sid=${session.id} " +
                    "hash=${System.identityHashCode(session)} result=PEER_UNREACHABLE " +
                    "authority=${authorityId.value}"
            )
            log("[${session.traceId}] Floor request failed: authority ${authorityId.value} unreachable")
            return
        }
        val remote = endpointForModule(session, authorityId)
        val discoveredPeer = discoveredByModule[authorityId.value]
        val peerIsStale = discoveredPeer != null && discoveredPeer != peer
        sendSignal(
            peer,
            buildSignedEnvelope(SignalType.FLOOR_REQUEST, groupLocalIdentity(session), remote, session.id, payload)
        )
        FloorTrace.requestSend(
            traceId,
            session.id,
            groupLocalIdentity(session).key,
            floorPayload.floorEpoch,
            floorPayload.floorVersion,
            authorityId.value,
            "SEND_OK"
        )
        log(
            "FLOOR_REQUEST_SEND ${sessionTag(session)} sid=${session.id} " +
                "hash=${System.identityHashCode(session)} result=SEND_OK " +
                "authority=${authorityId.value} to=${remote.key} " +
                "peerTarget=${peer.host}:${peer.port} " +
                "discovered=${discoveredPeer?.let { "${it.host}:${it.port}" } ?: "null"} " +
                "peerStale=$peerIsStale"
        )
    }

    private fun broadcastFloorSignal(
        session: TalkbackSession,
        type: SignalType,
        payload: String
    ) {
        targetsForSession(session).forEach { (peer, remote) ->
            sendSignal(
                peer,
                buildSignedEnvelope(type, groupLocalIdentity(session), remote, session.id, payload)
            )
        }
    }

    private fun broadcastFloorRelease(session: TalkbackSession) {
        val payload = FloorPayload(session.floor.version(), session.floor.epoch()).encode()
        targetsForSession(session).forEach { (peer, remote) ->
            sendSignal(
                peer,
                buildSignedEnvelope(SignalType.FLOOR_RELEASE, groupLocalIdentity(session), remote, session.id, payload)
            )
        }
    }

    private fun replyFloorToSession(
        session: TalkbackSession,
        type: SignalType,
        payload: String,
        remote: EndpointAddress
    ) {
        val peer = session.remotePeersByModule[remote.moduleId.value] ?: session.remotePeer ?: return
        sendSignal(
            peer,
            buildSignedEnvelope(type, groupLocalIdentity(session), remote, session.id, payload)
        )
    }

    private fun targetsForSession(session: TalkbackSession): List<Pair<PeerTarget, EndpointAddress>> {
        if (session.type == SessionType.UNICAST) {
            val remote = session.remote ?: return emptyList()
            val peer = session.remotePeer ?: return emptyList()
            return listOf(peer to remote)
        }
        return session.remotePeersByModule.map { (mod, peer) ->
            val moduleId = ModuleId(mod)
            peer to endpointForModule(session, moduleId)
        }
    }

    /** Includes invited-but-not-yet-connected peers so HANGUP clears pending conference UI. */
    private fun hangupTargetsForSession(session: TalkbackSession): List<Pair<PeerTarget, EndpointAddress>> {
        val targets = targetsForSession(session).toMutableList()
        if (!session.type.isMeshSession()) return targets
        session.groupMembers.forEach { remote ->
            if (remote.moduleId == localModuleId) return@forEach
            val moduleId = remote.moduleId.value
            val peer = session.remotePeersByModule[moduleId] ?: discoveredByModule[moduleId] ?: return@forEach
            if (targets.none { it.second.moduleId == remote.moduleId }) {
                targets.add(peer to remote)
            }
        }
        return targets
    }

    private fun endpointForModule(session: TalkbackSession, moduleId: ModuleId): EndpointAddress {
        return session.groupMembers.find { it.moduleId == moduleId }
            ?: EndpointAddress(moduleId, session.local.endpointId)
    }

    private fun toConferenceInviteSnapshot(pending: PendingConferenceInvite): ConferenceInviteSnapshot? {
        val signal = pending.signal
        val payload = GroupSessionPayload.decode(signal.payload) ?: return null
        val members = GroupSessionPayload.parseMembers(payload.members)
        return ConferenceInviteSnapshot(
            sessionId = signal.sessionId,
            channelId = payload.channelId,
            hostKey = signal.from.key,
            hostModuleId = signal.from.moduleId.value,
            memberCount = members.size,
            receivedAtMs = pending.receivedAtMs
        )
    }

    private fun clearPendingConferenceInvite(sessionId: String? = null, channelId: String? = null) {
        when {
            channelId != null -> pendingConferenceInvitesByChannel.remove(channelId)
            sessionId != null -> pendingConferenceInvitesByChannel.entries.removeIf { it.value.signal.sessionId == sessionId }
        }
    }

    private data class PendingConferenceInviteMatch(
        val channelId: String,
        val pending: PendingConferenceInvite
    )

    private fun findPendingConferenceInviteBySessionId(sessionId: String): PendingConferenceInviteMatch? {
        val entry = pendingConferenceInvitesByChannel.entries.firstOrNull { (_, pending) ->
            pending.signal.sessionId == sessionId
        } ?: return null
        return PendingConferenceInviteMatch(channelId = entry.key, pending = entry.value)
    }

    private fun populateGroupSessionMetadata(
        session: TalkbackSession,
        payload: GroupSessionPayload,
        members: List<EndpointAddress>,
        caller: EndpointAddress,
        callerPeer: PeerTarget
    ) {
        val initiatorId = payload.initiatorModuleId.takeIf { it.isNotBlank() }?.let { ModuleId(it) }
            ?: caller.moduleId
        val authorityId = payload.floorAuthorityModuleId.takeIf { it.isNotBlank() }?.let { ModuleId(it) }
            ?: initiatorId
        session.initiatorModuleId = initiatorId
        session.floorAuthorityModuleId = authorityId
        if (session.type == SessionType.CONFERENCE) {
            initConferenceParticipantState(session, members)
        } else {
            session.groupMembers = members
            GroupMembershipSupport.syncMembershipFromGroupMembers(session)
        }
        if (payload.rosterEpochMs > 0L) {
            session.rosterEpochMs = payload.rosterEpochMs
        }
        if (payload.rosterEpoch > 0L && payload.rosterEpoch >= session.rosterEpoch) {
            session.rosterEpoch = payload.rosterEpoch
        }
        session.mediaTopology = GroupMediaTopology.fromPayload(payload.mediaTopology)
        payload.anchorModuleId?.let { session.anchorModuleId = ModuleId(it) }
        payload.backupAnchorModuleId?.let { session.backupAnchorModuleId = ModuleId(it) }
        if (payload.anchorEpoch > 0L) {
            applyRemoteAnchorView(
                session,
                payload.anchorEpoch,
                payload.anchorModuleId?.let { ModuleId(it) },
                payload.backupAnchorModuleId?.let { ModuleId(it) }
            )
        }
        if (session.type == SessionType.GROUP || session.type == SessionType.CONFERENCE) {
            if (session.anchorModuleId == null && session.mediaTopology == GroupMediaTopology.ANCHOR) {
                electAnchorRoles(members.map { it.moduleId }.toSet())?.let { applyAnchorRolesToSession(session, it) }
            }
            val threshold = when (session.type) {
                SessionType.CONFERENCE -> MediaTopologyPolicy.anchorThresholdForConference()
                else -> MediaTopologyPolicy.anchorThresholdForGroup()
            }
            if (session.mediaTopology == GroupMediaTopology.MESH && members.size >= threshold) {
                applyGroupTopology(session, members.size)
            }
        }
        session.remote = caller
        session.remotePeer = callerPeer
        session.memberModules.add(localModuleId)
        members.map { it.moduleId }.distinct().forEach { mod ->
            if (mod == localModuleId) return@forEach
            val peer = discoveredByModule[mod.value]
            if (peer == null) {
                log("[${session.traceId}] Group member ${mod.value} not discovered")
                return@forEach
            }
            session.memberModules.add(mod)
            session.remotePeersByModule[mod.value] = peer
        }
        session.remotePeersByModule[caller.moduleId.value] = callerPeer
        session.memberModules.add(caller.moduleId)
    }

    private fun completeGroupMesh(session: TalkbackSession) {
        if (shouldDeferConferenceFullMesh(session)) {
            log("${sessionTag(session)} Deferring full conference mesh until host link is stable")
            return
        }
        val channelId = session.channelId
        if (channelId != null && isMeshRepairSuppressed(channelId)) {
            return
        }
        val initiator = session.initiatorModuleId ?: return
        val allModules = memberModuleIds(session)
        val topology = topologyFor(session)
        val anchor = resolveAnchorModuleId(session)
        topology.joinTargets(localModuleId, initiator, anchor, allModules).forEach { target ->
            offerGroupMeshJoin(session, target)
        }
    }

    private fun shouldDeferConferenceFullMesh(session: TalkbackSession): Boolean {
        if (session.type != SessionType.CONFERENCE || !session.accepted) return false
        if (isConferenceHostSession(session)) return false
        val edgeFacts = conferenceEdgeRecoveryController.factsForSession(session.id)
        val hostId = session.initiatorModuleId?.value ?: return false
        val hostIceConnected = IceConnectivity.isConnected(qosMonitor.snapshot(hostId)?.iceState)
        return ConferenceBootstrapDeferral.shouldDeferFullMesh(
            isParticipantConference = true,
            edgeAnyRecovering = edgeFacts.anyRecovering,
            edgeAnyFailedMediaRecovery = edgeFacts.anyFailedMediaRecovery,
            hostIceConnected = hostIceConnected
        )
    }

    private fun groupInviteTargets(
        session: TalkbackSession,
        sessionType: SessionType,
        local: EndpointAddress,
        remoteEndpoints: List<EndpointAddress>
    ): List<EndpointAddress> {
        val usesAnchor = sessionType == SessionType.GROUP || sessionType == SessionType.CONFERENCE
        if (!usesAnchor || session.mediaTopology != GroupMediaTopology.ANCHOR) {
            return remoteEndpoints
        }
        val anchor = session.anchorModuleId ?: return remoteEndpoints
        return if (local.moduleId == anchor) {
            remoteEndpoints
        } else {
            remoteEndpoints.filter { it.moduleId == anchor }
        }
    }

    private fun memberModuleIds(session: TalkbackSession): Set<ModuleId> =
        meshRoster(session).map { it.moduleId }.toSet()

    private fun iceStateForModule(moduleId: String): String? =
        qosMonitor.snapshot(moduleId)?.iceState

    private fun activeMemberModuleIds(session: TalkbackSession): Set<ModuleId> =
        GroupMembershipSupport.activeMemberModuleIds(session, ::iceStateForModule)

    private fun bumpRosterEpoch(session: TalkbackSession, reason: String) {
        val epoch = GroupMembershipSupport.bumpRosterEpoch(session)
        log("${sessionTag(session)} rosterEpoch=$epoch reason=$reason")
        touchConvergenceAnchor(session)
        emitGroupTopologySnapshot(TopologySnapshotReason.MEMBERSHIP_CHANGED, session)
        onGroupConvergenceBoundary(session)
    }

    /**
     * R35: on verified HELLO, replace stale groupMembers endpoint when moduleId matches but endpointId differs.
     */
    private fun applyCanonicalEndpointBindingFromHello(session: TalkbackSession, payload: HelloPayload) {
        val moduleId = payload.moduleId
        val endpointId = payload.endpoints.filter { it.online }.minByOrNull { it.endpointId }?.endpointId
            ?: lastKnownPrimaryEndpointByModule[moduleId]
            ?: return
        val rebound = GroupMembershipSupport.replaceGroupMemberEndpoint(
            session,
            moduleId,
            EndpointId(endpointId)
        ) ?: return
        log(
            "IDENTITY_REBOUND ${sessionTag(session)} sid=${session.id} " +
                "moduleId=$moduleId oldKey=${rebound.oldEndpoint.key} newKey=${rebound.newEndpoint.key}"
        )
        if (session.remote?.moduleId?.value == moduleId &&
            session.remote?.endpointId == rebound.oldEndpoint.endpointId
        ) {
            session.remote = rebound.newEndpoint
        }
        emitGroupTopologySnapshot(TopologySnapshotReason.IDENTITY_REBOUND, session)
        if (isMembershipAuthority(session)) {
            bumpRosterEpoch(session, "identity_rebound")
            broadcastMembershipSnapshot(session)
        }
        session.touch()
    }

    private fun broadcastMembershipSnapshot(session: TalkbackSession) {
        GroupMembershipSupport.canonicalRosterEndpoints(session)
            .filter { it.moduleId != session.local.moduleId }
            .forEach { remote ->
                if (sendMembershipSnapshotInvite(session, remote)) {
                    log("${sessionTag(session)} MEMBERSHIP_SNAPSHOT -> ${remote.moduleId.value}")
                }
            }
    }

    private fun scheduleReconcile(reason: String) {
        sessions.values
            .filter { it.type == SessionType.GROUP && it.accepted && !it.isForegroundSuspended() }
            .forEach { reconcileGroupMembership(it, reason) }
    }

    private fun isGroupMemberReconnectEligible(session: TalkbackSession, moduleId: String): Boolean {
        if (GroupMembershipSupport.isEvicted(session, moduleId)) return false
        if (GroupMembershipSupport.membershipState(session, moduleId) == GroupMemberReachability.SUSPECT &&
            !GroupMembershipSupport.isIceAlive(iceStateForModule(moduleId))
        ) {
            return false
        }
        return true
    }

    private fun reconcileGroupMembership(session: TalkbackSession, reason: String) {
        if (session.type != SessionType.GROUP || !session.accepted || session.isForegroundSuspended()) return
        val now = System.currentTimeMillis()
        val suspectHelloMs = config.groupMemberSuspectHelloMs
        val suspectIceMs = config.groupMemberSuspectIceMs
        val evictMs = config.groupMemberEvictSuspectMs
        val toEvict = mutableListOf<String>()

        GroupMembershipSupport.canonicalMemberModuleIds(session).forEach { moduleId ->
            val id = moduleId.value
            if (id == localModuleId.value) return@forEach
            val state = GroupMembershipSupport.membershipState(session, id)
            if (state == GroupMemberReachability.EVICTED) return@forEach

            val lastHello = moduleLastHelloMs[id] ?: 0L
            val helloMissing = lastHello <= 0L || now - lastHello > suspectHelloMs
            val snap = qosMonitor.snapshot(id)
            val ice = snap?.iceState
            val iceLost = ice != null && !GroupMembershipSupport.isIceAlive(ice) &&
                !IceConnectivity.isNegotiating(ice)
            val iceLostLong = iceLost && snap != null && now - snap.updatedMs >= suspectIceMs
            val hasMediaLink = id in session.remotePeersByModule || id in session.meshCompletedModules

            when (state) {
                GroupMemberReachability.ONLINE -> {
                    if ((helloMissing && hasMediaLink) || iceLostLong) {
                        GroupMembershipSupport.markSuspect(session, id, now)
                        log("${sessionTag(session)} Member $id SUSPECT ($reason)")
                    }
                }
                GroupMemberReachability.SUSPECT -> {
                    val suspectSince = session.suspectSinceMsByModule[id] ?: now
                    if (now - suspectSince >= evictMs) {
                        toEvict.add(id)
                    } else if (!helloMissing && (GroupMembershipSupport.isIceAlive(ice) || !hasMediaLink)) {
                        GroupMembershipSupport.markOnline(session, id)
                    }
                }
                GroupMemberReachability.EVICTED -> Unit
            }
        }

        toEvict.forEach { removeGroupMember(session, it) }
        if (toEvict.isNotEmpty()) {
            completeGroupMesh(session)
            session.channelId?.let { reconcileGroupMeshInternal(it) }
            updateSessionReceivePlayback(session, "member_evict")
        }
        onGroupConvergenceBoundary(session)
    }

    private fun applyGroupRoster(session: TalkbackSession, members: List<EndpointAddress>, rosterEpoch: Long) {
        if (rosterEpoch > 0L && rosterEpoch < session.rosterEpoch) {
            log("${sessionTag(session)} Ignoring stale roster epoch $rosterEpoch < ${session.rosterEpoch}")
            return
        }
        val memberIds = members.map { it.moduleId.value }.toSet()
        remoteModuleIds(session).toList().forEach { moduleId ->
            if (moduleId !in memberIds) {
                releasePeerMediaOnly(session, moduleId)
            }
        }
        GroupMembershipSupport.applyGroupMembersList(session, members)
        session.memberModules.add(localModuleId)
        if (rosterEpoch > session.rosterEpoch) {
            session.rosterEpoch = rosterEpoch
        }
        session.touch()
    }

    private fun removeGroupMember(session: TalkbackSession, moduleId: String) {
        if (moduleId == localModuleId.value) return
        releaseFloorIfHolderUnavailable(session, moduleId)
        session.groupMembers = session.groupMembers.filter { it.moduleId.value != moduleId }
        session.memberModules.remove(ModuleId(moduleId))
        session.membershipStateByModule.remove(moduleId)
        session.suspectSinceMsByModule.remove(moduleId)
        releaseMeshPeer(session, moduleId)
        session.participants.remove(moduleId)
        if (session.remote?.moduleId?.value == moduleId) {
            val nextRemote = session.groupMembers.firstOrNull { it.moduleId != localModuleId }
            session.remote = nextRemote
            session.remotePeer = nextRemote?.moduleId?.value?.let { session.remotePeersByModule[it] }
        }
        bumpRosterEpoch(session, "member_evicted")
        session.touch()
        log("${sessionTag(session)} EVICTED member $moduleId")
    }

    private fun markMembershipSuspect(session: TalkbackSession, moduleId: String) {
        GroupMembershipSupport.markSuspect(session, moduleId, System.currentTimeMillis())
        log("${sessionTag(session)} Member $moduleId marked SUSPECT (failover)")
    }

    private fun maybeRequestGroupResyncFromHello(payload: HelloPayload) {
        val channelId = payload.channelId ?: return
        if (payload.rosterEpoch <= 0L) return
        val session = sessions.values.firstOrNull {
            it.type == SessionType.GROUP && it.accepted && it.channelId == channelId
        } ?: return
        val localDigest = TopologyDigest.fromSession(session)
        if (payload.rosterEpoch == localDigest.rosterEpoch &&
            payload.memberHash == localDigest.memberHash
        ) {
            return
        }
        val authorityId = session.anchorModuleId?.value
            ?: resolveBootstrapPrimary(dialableRemoteModuleIds().plus(localModuleId))?.value
            ?: return
        if (payload.moduleId != authorityId) return
        val peer = resolvePeerForModule(authorityId) ?: return
        val remote = endpointForModule(session, ModuleId(authorityId))
        val body = GroupResyncRequestPayload(
            channelId = channelId,
            requesterRosterEpoch = session.rosterEpoch
        ).encode()
        sendSignal(
            peer,
            buildSignedEnvelope(
                SignalType.GROUP_RESYNC_REQUEST,
                session.local,
                remote,
                session.id,
                body
            )
        )
        log("${sessionTag(session)} GROUP_RESYNC_REQUEST -> $authorityId")
    }

    private fun assertCanonicalConsistencyFromHello(payload: HelloPayload) {
        val channelId = payload.channelId ?: return
        if (payload.rosterEpoch <= 0L) return
        val session = sessions.values.firstOrNull {
            it.type == SessionType.GROUP && it.accepted && it.channelId == channelId
        } ?: return
        val localDigest = TopologyDigest.fromSession(session)
        if (payload.rosterEpoch == localDigest.rosterEpoch &&
            payload.memberHash == localDigest.memberHash
        ) {
            return
        }
        val authorityId = session.anchorModuleId?.value
            ?: resolveBootstrapPrimary(dialableRemoteModuleIds().plus(localModuleId))?.value
            ?: return
        if (payload.moduleId != authorityId) return
        val localMembers = GroupMembershipSupport.canonicalMemberModuleIds(session)
            .map { it.value }
            .sorted()
        log(
            "CANONICAL_MISMATCH channel=$channelId " +
                "localEpoch=${localDigest.rosterEpoch} localHash=${localDigest.memberHash} " +
                "authorityEpoch=${payload.rosterEpoch} authorityHash=${payload.memberHash} " +
                "members=[${localMembers.joinToString(",")}] " +
                "authorityMembers=[pending_resync]"
        )
    }

    /**
     * Authority pulls a peer back when their HELLO digest matches ours but they are absent from
     * the canonical roster (typical after leave/rejoin without invite).
     */
    private fun ensureCanonicalMemberPresent(payload: HelloPayload, fromPeer: PeerTarget) {
        val channelId = payload.channelId ?: return
        if (payload.rosterEpoch <= 0L || payload.memberHash == 0) return
        val moduleId = payload.moduleId
        if (moduleId == localModuleId.value) return

        val session = sessions.values.firstOrNull {
            it.type == SessionType.GROUP && it.accepted && it.channelId == channelId
        } ?: return
        if (!isMembershipAuthority(session)) return

        val canonicalIds = GroupMembershipSupport.canonicalMemberModuleIds(session).map { it.value }
        if (moduleId in canonicalIds) return

        val localDigest = TopologyDigest.fromSession(session)
        val digestAligned = payload.rosterEpoch == localDigest.rosterEpoch &&
            payload.memberHash == localDigest.memberHash
        val onChannel = moduleId in channelManager.members(channelId).map { it.value }
        if (!digestAligned && !onChannel) return

        val now = System.currentTimeMillis()
        val lastMs = lastEnsureCanonicalInviteMsByModule[moduleId] ?: 0L
        if (now - lastMs < ENSURE_CANONICAL_INVITE_COOLDOWN_MS) return
        lastEnsureCanonicalInviteMsByModule[moduleId] = now

        rememberSignalPeer(moduleId, fromPeer)
        val endpointId = payload.endpoints.firstOrNull { it.online }?.endpointId
            ?: resolvePrimaryEndpointId(moduleId, stateSync.get(moduleId))
            ?: "E01"
        val remote = EndpointAddress(ModuleId(moduleId), EndpointId(endpointId))
        log("${sessionTag(session)} ensureCanonicalMemberPresent module=$moduleId")
        val sent = sendGroupMeshInvitesInternal(session.id, listOf(remote))
        if (sent == 0) {
            sendMembershipSnapshotInvite(session, remote)
        }
    }

    private fun inviteCanonicalRosterMismatch(
        session: TalkbackSession,
        payload: GroupSessionPayload
    ): Boolean {
        if (session.type != SessionType.GROUP) return false
        if (payload.sdp.isBlank()) return false
        val inviteMembers = GroupSessionPayload.parseMembers(payload.members)
            .map { it.moduleId.value }
            .sorted()
        if (inviteMembers.isEmpty()) return false
        val channelId = session.channelId ?: payload.channelId
        if (payload.memberHash != 0) {
            val computed = GroupMembershipSupport.memberHash(
                channelId,
                payload.rosterEpoch,
                inviteMembers
            )
            if (payload.memberHash != computed) {
                log(
                    "${sessionTag(session)} invite memberHash mismatch " +
                        "declared=${payload.memberHash} computed=$computed"
                )
                return true
            }
        }
        if (payload.rosterEpoch <= 0L) return false
        val localMembers = GroupMembershipSupport.canonicalMemberModuleIds(session)
            .map { it.value }
            .sorted()
        if (payload.rosterEpoch == session.rosterEpoch && inviteMembers != localMembers) {
            logCanonicalMismatch(session, payload.rosterEpoch, payload.memberHash, inviteMembers)
            return true
        }
        return false
    }

    private fun logCanonicalMismatch(
        session: TalkbackSession,
        authorityEpoch: Long,
        authorityHash: Int,
        authorityMemberIds: List<String>
    ) {
        val channelId = session.channelId ?: return
        val localDigest = TopologyDigest.fromSession(session)
        val localMembers = GroupMembershipSupport.canonicalMemberModuleIds(session)
            .map { it.value }
            .sorted()
        log(
            "CANONICAL_MISMATCH channel=$channelId " +
                "localEpoch=${localDigest.rosterEpoch} localHash=${localDigest.memberHash} " +
                "authorityEpoch=$authorityEpoch authorityHash=$authorityHash " +
                "members=[${localMembers.joinToString(",")}] " +
                "authorityMembers=[${authorityMemberIds.joinToString(",")}]"
        )
    }

    private fun requestGroupResyncFromAuthority(session: TalkbackSession) {
        val channelId = session.channelId ?: return
        val authorityId = session.anchorModuleId?.value
            ?: resolveBootstrapPrimary(dialableRemoteModuleIds().plus(localModuleId))?.value
            ?: return
        val peer = resolvePeerForModule(authorityId) ?: return
        val remote = endpointForModule(session, ModuleId(authorityId))
        val body = GroupResyncRequestPayload(
            channelId = channelId,
            requesterRosterEpoch = session.rosterEpoch
        ).encode()
        sendSignal(
            peer,
            buildSignedEnvelope(
                SignalType.GROUP_RESYNC_REQUEST,
                session.local,
                remote,
                session.id,
                body
            )
        )
        log("${sessionTag(session)} GROUP_RESYNC_REQUEST (invite mismatch) -> $authorityId")
    }

    private fun handleGroupResyncRequest(signal: SignalEnvelope, fromPeer: PeerTarget) {
        val payload = GroupResyncRequestPayload.decode(signal.payload) ?: return
        val session = sessions.values.firstOrNull {
            it.type == SessionType.GROUP &&
                it.accepted &&
                it.channelId == payload.channelId
        } ?: return
        if (!isMembershipAuthority(session)) return
        val requesterId = signal.from.moduleId.value
        val endpoint = session.groupMembers.find { it.moduleId.value == requesterId }
            ?: endpointForDialableModule(ModuleId(requesterId))
            ?: return
        sendMembershipSnapshotInvite(session, endpoint)
        log("${sessionTag(session)} GROUP_RESYNC -> SNAPSHOT $requesterId")
    }

    private fun membershipSnapshotForSession(session: TalkbackSession): MembershipSnapshot {
        val members = rosterMembersForPayload(session).map { it.key }
        return MembershipSnapshot(
            rosterEpoch = session.rosterEpoch,
            anchorEpoch = session.anchorEpoch,
            members = members
        )
    }

    /** Push roster snapshot only — no ICE offer, no mesh rebuild. */
    private fun sendMembershipSnapshotInvite(session: TalkbackSession, remote: EndpointAddress): Boolean {
        val peer = resolvePeerForModule(remote.moduleId.value) ?: return false
        val payload = groupPayloadBase(session).copy(
            sdp = "",
            rejoin = true,
            membershipSnapshot = membershipSnapshotForSession(session)
        )
        sendSignal(
            peer,
            buildSignedEnvelope(
                SignalType.GROUP_INVITE,
                session.local,
                remote,
                session.id,
                payload.encode()
            )
        )
        return true
    }

    private fun findGroupSessionForMembership(channelId: String, sessionId: String): TalkbackSession? =
        sessions[sessionId]?.takeIf {
            it.type == SessionType.GROUP && it.accepted && it.channelId == channelId
        } ?: sessions.values.firstOrNull {
            it.type == SessionType.GROUP && it.accepted && it.channelId == channelId
        }

    private fun tryApplyMembershipSnapshotInvite(
        signal: SignalEnvelope,
        fromPeer: PeerTarget,
        payload: GroupSessionPayload
    ): Boolean {
        val snapshot = payload.membershipSnapshot ?: return false
        if (payload.sdp.isNotBlank()) return false
        val channelId = payload.channelId
        val members = GroupSessionPayload.parseMembers(payload.members)
        if (members.isEmpty()) return false
        val session = findGroupSessionForMembership(channelId, signal.sessionId) ?: return false
        val caller = signal.from
        val authorityId = session.anchorModuleId?.value
            ?: resolveBootstrapPrimary(dialableRemoteModuleIds().plus(localModuleId))?.value
            ?: caller.moduleId.value
        rememberSignalPeer(caller.moduleId.value, fromPeer)
        session.remotePeersByModule[caller.moduleId.value] = fromPeer
        val localEpochBefore = session.rosterEpoch
        when (
            GroupMembershipSupport.applyMembershipSnapshot(
                session,
                snapshot.rosterEpoch,
                snapshot.anchorEpoch,
                members,
                caller.moduleId.value,
                authorityId
            )
        ) {
            GroupMembershipSupport.MembershipSnapshotApplyResult.APPLIED -> {
                val forceAlign = snapshot.rosterEpoch < localEpochBefore
                log(
                    "${sessionTag(session)} snapshotApplied rosterEpoch=${snapshot.rosterEpoch} " +
                        "anchorEpoch=${snapshot.anchorEpoch} members=${snapshot.members.size} " +
                        "from=${caller.moduleId.value}" +
                        if (forceAlign) " forceAlign localWas=$localEpochBefore" else ""
                )
                touchConvergenceAnchor(session)
                completeGroupMesh(session)
                updateSessionReceivePlayback(session)
                tryFlushPendingTransmit(session)
                emitGroupTopologySnapshot(TopologySnapshotReason.MEMBERSHIP_CHANGED, session)
                onGroupConvergenceBoundary(session)
            }
            GroupMembershipSupport.MembershipSnapshotApplyResult.IGNORED_STALE -> {
                log(
                    "${sessionTag(session)} snapshotIgnored remoteEpoch=${snapshot.rosterEpoch} " +
                        "localEpoch=${session.rosterEpoch} from=${caller.moduleId.value}"
                )
            }
            GroupMembershipSupport.MembershipSnapshotApplyResult.IGNORED_NOT_AUTHORITY -> {
                log(
                    "WARN ${sessionTag(session)} snapshotIgnored notAuthority " +
                        "from=${caller.moduleId.value} authority=$authorityId " +
                        "remoteEpoch=${snapshot.rosterEpoch} localEpoch=${session.rosterEpoch}"
                )
            }
        }
        session.touch()
        return true
    }

    private fun isMembershipAuthority(session: TalkbackSession): Boolean {
        if (session.anchorModuleId == localModuleId) return true
        return resolveBootstrapPrimary(dialableRemoteModuleIds().plus(localModuleId)) == localModuleId
    }

    private fun topologyFor(session: TalkbackSession): MediaTopology =
        MediaTopology.forSession(session.mediaTopology, memberModuleIds(session).size)

    private fun resolveAnchorModuleId(session: TalkbackSession): ModuleId =
        session.anchorModuleId ?: electAnchorRoles(memberModuleIds(session))?.primary ?: localModuleId

    private fun anchorThresholdFor(session: TalkbackSession): Int =
        when (session.type) {
            SessionType.CONFERENCE -> MediaTopologyPolicy.anchorThresholdForConference()
            else -> MediaTopologyPolicy.anchorThresholdForGroup()
        }

    private fun applyGroupTopology(session: TalkbackSession, memberCount: Int) {
        val threshold = anchorThresholdFor(session)
        session.mediaTopology = if (memberCount >= threshold) {
            GroupMediaTopology.ANCHOR
        } else {
            GroupMediaTopology.MESH
        }
        if (session.mediaTopology == GroupMediaTopology.ANCHOR) {
            electAnchorRoles(memberModuleIds(session))?.let { applyAnchorRolesToSession(session, it) }
                ?: run { session.anchorModuleId = localModuleId }
        } else {
            session.backupAnchorModuleId = null
        }
    }

    private fun rosterMembersForPayload(session: TalkbackSession): List<EndpointAddress> =
        when (session.type) {
            SessionType.CONFERENCE -> fullConferenceRoster(session)
            SessionType.GROUP -> GroupMembershipSupport.canonicalRosterEndpoints(session)
            else -> session.groupMembers
        }

    private fun canonicalAndPendingMemberKeys(session: TalkbackSession): Set<String> {
        val keys = linkedSetOf<String>()
        session.groupMembers.forEach { keys.add(it.key) }
        session.pendingInviteeEndpoints.values.forEach { keys.add(it.key) }
        return keys
    }

    private fun invitePayloadMemberKeys(session: TalkbackSession): List<String> {
        if (session.type == SessionType.GROUP) {
            return GroupMembershipSupport.canonicalMemberKeys(session)
        }
        val byKey = linkedMapOf<String, EndpointAddress>()
        session.groupMembers.forEach { byKey[it.key] = it }
        session.pendingInviteeEndpoints.values.forEach { byKey[it.key] = it }
        return byKey.values.map { it.key }
    }

    private fun promoteInviteeToCanonicalRoster(session: TalkbackSession, remote: EndpointAddress) {
        val moduleId = remote.moduleId.value
        session.pendingInviteeEndpoints.remove(moduleId)
        if (session.groupMembers.any { it.moduleId.value == moduleId }) return
        session.groupMembers = session.groupMembers + remote
        GroupMembershipSupport.syncMembershipFromGroupMembers(session)
        bumpRosterEpoch(session, "member_joined")
        log("${sessionTag(session)} Canonical roster +$moduleId epoch=${session.rosterEpoch}")
    }

    /** Active roster plus evicted peers still eligible for conference re-invite. */
    private fun fullConferenceRoster(session: TalkbackSession): List<EndpointAddress> {
        val byModule = LinkedHashMap<String, EndpointAddress>()
        meshRoster(session).forEach { byModule[it.moduleId.value] = it }
        conferenceParticipantManager.leftMemberEndpoints(session.id)
            ?.forEach { (id, endpoint) -> byModule.putIfAbsent(id, endpoint) }
            ?: session.leftMemberEndpoints.forEach { (id, endpoint) -> byModule.putIfAbsent(id, endpoint) }
        return byModule.values.toList()
    }

    private fun conferenceUnconnectedInviteTargets(session: TalkbackSession): List<EndpointAddress> =
        fullConferenceRoster(session).filter { remote ->
            remote.moduleId != session.local.moduleId &&
                !qosMonitor.isGroupConnected(remote.moduleId.value)
        }

    private fun groupPayloadBase(
        session: TalkbackSession,
        joinIntent: ConferenceJoinIntent = ConferenceJoinIntent.NORMAL_JOIN
    ): GroupSessionPayload {
        val initiator = session.initiatorModuleId ?: localModuleId
        val authority = session.floorAuthorityModuleId ?: initiator
        val usesAnchorPayload = session.mediaTopology == GroupMediaTopology.ANCHOR &&
            (session.type == SessionType.GROUP || session.type == SessionType.CONFERENCE)
        return GroupSessionPayload(
            sdp = "",
            channelId = session.channelId ?: "CH-01",
            members = rosterMembersForPayload(session).map { it.key },
            initiatorModuleId = initiator.value,
            floorAuthorityModuleId = authority.value,
            sessionMode = meshSessionModeFor(session),
            mediaTopology = session.mediaTopology.encode().takeIf {
                session.type == SessionType.GROUP || session.type == SessionType.CONFERENCE
            },
            anchorModuleId = session.anchorModuleId?.value?.takeIf { usesAnchorPayload },
            backupAnchorModuleId = session.backupAnchorModuleId?.value?.takeIf { usesAnchorPayload },
            anchorEpoch = session.anchorEpoch.takeIf { usesAnchorPayload && it > 0L } ?: 0L,
            rosterEpochMs = session.rosterEpochMs,
            rosterEpoch = session.rosterEpoch,
            memberHash = GroupMembershipSupport.memberHashForSession(session)
                .takeIf { session.type == SessionType.GROUP } ?: 0,
            joinIntent = joinIntent
        )
    }

    private fun transmitPeerIdsForSession(session: TalkbackSession): Set<String> {
        if (session.type != SessionType.GROUP) return remoteModuleIds(session)
        val topology = topologyFor(session)
        val anchor = resolveAnchorModuleId(session)
        return topology.transmitPeerIds(localModuleId, anchor, activeMemberModuleIds(session))
    }

    private fun syncProgramRelay(session: TalkbackSession) {
        if (session.type != SessionType.GROUP) return
        val ownerKey = session.floor.owner()?.key
        val holderModuleId = ownerKey?.let { moduleIdFromEndpointKey(it) }
        programAudioBus.updateFloorHolder(
            session,
            localModuleId,
            holderModuleId,
            activeRemoteModuleIds = activeMemberModuleIds(session).map { it.value }.toSet()
        )
    }

    private fun syncConferenceRelay(session: TalkbackSession, reason: String) {
        if (session.type != SessionType.CONFERENCE) return
        log(
            "${sessionTag(session)} receive-path sync reason=$reason " +
                "topology=${session.mediaTopology.name} accepted=${session.accepted}"
        )
        conferenceAudioBus.updateParticipants(session, localModuleId)
        receivePathLivenessObserver.syncMeshSession(session, localModuleId) { remoteModuleId ->
            meshEngineForSession(session, remoteModuleId)
        }
    }

    private fun releaseFloorIfHolderUnavailable(session: TalkbackSession, moduleId: String) {
        if (!session.type.usesFloorControl()) return
        val owner = session.floor.owner() ?: return
        if (owner.moduleId.value != moduleId) return
        session.floor.release(owner)
        if (isLocalIdentity(session, owner)) {
            session.ptt.onEvent(PttEvent.Release)
            session.pendingTransmit = false
            stopSessionCapture(session)
        } else {
            stopSessionCapture(session)
        }
        syncProgramRelay(session)
        log("${sessionTag(session)} Floor released for unavailable holder $moduleId")
    }

    private fun offerGroupMeshJoin(session: TalkbackSession, targetModuleId: ModuleId) {
        val channelId = session.channelId ?: return
        val peerId = targetModuleId.value
        val ice = qosMonitor.snapshot(peerId)?.iceState
        val meshPeer = session.meshCompletedModules.contains(peerId)
        if (meshPeer && IceConnectivity.isConnected(ice)) return
        val invitePending = meshParticipant(session,peerId).invite == InviteState.INVITING &&
            !meshPeer
        if (invitePending) {
            log("${sessionTag(session)} Mesh join deferred: $peerId invite still pending")
            return
        }
        if (meshPeer) {
            if (!groupMeshReconciler.canReconnect(channelId, peerId, ice)) return
        } else if (!groupMeshReconciler.canOfferJoin(channelId, peerId, ice)) {
            return
        }
        val peer = resolvePeerForModule(peerId)
        if (peer == null) {
            log("${sessionTag(session)} Mesh join skipped: $peerId not discovered ice=$ice")
            return
        }
        val payloadBase = groupPayloadBase(session)
        val remote = endpointForModule(session, targetModuleId)
        val engine = getOrCreateMeshEngine(session, peerId)
        wireIceCallback(session, peerId, engine)
        val traceCtx = mediaRecoveryTraceContext(
            session,
            peerId,
            remote.endpointId.value,
            iceRestart = meshPeer
        )
        if (meshPeer) {
            MediaRecoveryCausalTrace.recoveryIceRestartDispatched(traceCtx)
        }
        val offer = engine.createOffer(iceRestart = meshPeer)
        MediaRecoveryCausalTrace.mediaSignalOfferSent(traceCtx)
        drainPendingIce(session.id, peerId, engine)
        sendSignal(
            peer,
            buildSignedEnvelope(
                SignalType.GROUP_JOIN,
                session.local,
                remote,
                session.id,
                payloadBase.copy(sdp = offer).encode()
            )
        )
        if (meshPeer) {
            groupMeshReconciler.markReconnectAttempt(channelId, peerId)
        } else {
            groupMeshReconciler.markJoinOffered(channelId, peerId)
        }
        log("${sessionTag(session)} Group mesh join offered -> $peerId ice=$ice restart=$meshPeer")
        emitGroupTopologySnapshot(TopologySnapshotReason.MESH_OFFERED, session)
    }

    private fun drainPendingGroupJoins(sessionId: String) {
        val pending = pendingGroupJoinsBySession.remove(sessionId) ?: return
        pending.forEach { acceptGroupJoin(sessions[sessionId] ?: return@forEach, it.signal, it.fromPeer) }
    }

    private fun scheduleGroupMeshRetries(sessionId: String) {
        if (!meshRetryScheduledSessionIds.add(sessionId)) return
        MESH_RETRY_DELAYS_MS.forEach { delayMs ->
            scheduler.schedule({
                runOnCoordinator {
                    val session = sessions[sessionId]
                    if (session == null) {
                        meshRetryScheduledSessionIds.remove(sessionId)
                        return@runOnCoordinator
                    }
                    if (!session.type.isMeshSession()) {
                        meshRetryScheduledSessionIds.remove(sessionId)
                        return@runOnCoordinator
                    }
                    completeGroupMesh(session)
                    drainPendingGroupJoins(sessionId)
                    if (delayMs == MESH_RETRY_DELAYS_MS.last()) {
                        meshRetryScheduledSessionIds.remove(sessionId)
                    }
                }
            }, delayMs, TimeUnit.MILLISECONDS)
        }
    }

    private fun retryIncompleteGroupMesh() {
        sessions.values
            .filter { it.type.isMeshSession() && it.accepted }
            .forEach { session ->
                completeGroupMesh(session)
                drainPendingGroupJoins(session.id)
            }
    }

    fun rebroadcastHello() = runOnCoordinatorSync { broadcastHello() }

    fun consumeFloorPreempted(sessionId: String): Boolean = runOnCoordinatorSync {
        val session = sessions[sessionId] ?: return@runOnCoordinatorSync false
        if (!session.localFloorPreempted) return@runOnCoordinatorSync false
        session.localFloorPreempted = false
        true
    }

    fun consumeAcquireTimedOut(sessionId: String): Boolean = runOnCoordinatorSync {
        val session = sessions[sessionId] ?: return@runOnCoordinatorSync false
        if (!session.localAcquireTimedOut) return@runOnCoordinatorSync false
        session.localAcquireTimedOut = false
        true
    }

    private fun onLocalProtocolFloorAcquired(session: TalkbackSession) {
        if (!isLocalFloorHolder(session)) return
        if (session.ptt.state == PttState.REQUEST_FLOOR) {
            session.ptt.onEvent(PttEvent.Granted)
        }
        beginTransmitIfReady(session)
        scheduleAcquireReleaseIfNeeded(session)
    }

    private fun scheduleAcquireReleaseIfNeeded(session: TalkbackSession) {
        if (!session.type.usesFloorControl()) return
        if (!isLocalFloorHolder(session)) return
        val capturing = sessionMediaEngines(session).any { it.isCapturing() }
        acquireReleaseWatchdog.onLocalGrantApplied(session.id, alreadyCapturing = capturing)
    }

    private fun onAcquireReleaseTimeout(sessionId: String) {
        val session = sessions[sessionId] ?: return
        if (!session.type.usesFloorControl()) return
        if (!isLocalFloorHolder(session)) return
        if (sessionMediaEngines(session).any { it.isCapturing() }) {
            log("ACQUIRE_RELEASE_TIMEOUT_SKIP sid=$sessionId reason=capture_already_on")
            return
        }
        val awaitingUplink = session.ptt.state == PttState.TALK ||
            session.ptt.state == PttState.REQUEST_FLOOR ||
            session.pendingTransmit
        if (!awaitingUplink) return

        log(
            "ACQUIRE_RELEASE_TIMEOUT ${sessionTag(session)} sid=$sessionId " +
                "timeoutMs=${config.acquireReleaseTimeoutMs}"
        )
        session.localAcquireTimedOut = true
        session.pendingTransmit = false
        stopSessionCapture(session)
        moduleMixer.setActiveCapture(null)
        session.ptt.onEvent(PttEvent.Release)
        if (releaseLocalFloorIfHeld(session)) {
            broadcastFloorRelease(session)
        }
        syncProgramRelay(session)
        updateSessionReceivePlayback(session, "acquire_timeout")
    }

    private fun broadcastHello() {
        val endpoints = endpointRegistry.allOnline().map {
            RemoteEndpointInfo(
                endpointId = it.address.endpointId.value,
                displayName = it.displayName,
                online = it.online,
                priority = it.priority
            )
        }
        val localHealth = localDeviceHealth.snapshot()
        val anchorSession = sessions.values.firstOrNull {
            (it.type == SessionType.GROUP || it.type == SessionType.CONFERENCE) &&
                it.accepted &&
                it.channelId != null &&
                it.mediaTopology == GroupMediaTopology.ANCHOR
        }
        val groupSession = sessions.values.firstOrNull {
            it.type == SessionType.GROUP && it.accepted && it.channelId != null
        } ?: anchorSession
        val digest = groupSession?.let { TopologyDigest.fromSession(it) }
        if (groupSession != null && digest != null) {
            val floorDigest = groupSession.takeIf {
                GroupFloorController.canPublishFloorSnapshot(it, localModuleId.value)
            }?.floor?.let { floor ->
                FloorSnapshotDigest(
                    epoch = floor.epoch(),
                    version = floor.version(),
                    ownerKey = GroupFloorController.canonicalFloorOwnerKey(groupSession),
                    ownerPriority = floor.ownerPriority()
                )
            }
            log(
                "GROUP_DIGEST sessionId=${groupSession.id} channelId=${groupSession.channelId} " +
                    "anchorEpoch=${digest.anchorEpoch} rosterEpoch=${digest.rosterEpoch} " +
                    "memberHash=${digest.memberHash}" +
                    (floorDigest?.let {
                        " floorEpoch=${it.epoch} floorVersion=${it.version} " +
                            "floorOwner=${it.ownerKey ?: "null"}"
                    } ?: "")
            )
        }
        val floorSnapshot = groupSession?.takeIf {
            GroupFloorController.canPublishFloorSnapshot(it, localModuleId.value)
        }?.let { session ->
            FloorSnapshotDigest(
                epoch = session.floor.epoch(),
                version = session.floor.version(),
                ownerKey = GroupFloorController.canonicalFloorOwnerKey(session),
                ownerPriority = session.floor.ownerPriority()
            )
        }
        val payload = HelloPayload(
            moduleId = localModuleId.value,
            endpoints = endpoints,
            charging = localHealth.charging,
            batteryPercent = localHealth.batteryPercent,
            onlineSinceMs = localHealth.onlineSinceMs,
            anchorEpoch = anchorSession?.anchorEpoch ?: 0L,
            primaryModuleId = anchorSession?.anchorModuleId?.value,
            backupModuleId = anchorSession?.backupAnchorModuleId?.value,
            channelId = groupSession?.channelId,
            rosterEpoch = digest?.rosterEpoch ?: 0L,
            meshGeneration = digest?.meshGeneration ?: 0L,
            memberHash = digest?.memberHash ?: 0,
            floorSnapshot = floorSnapshot
        ).encode()
        val from = endpointRegistry.allOnline().firstOrNull()?.address
            ?: EndpointAddress(localModuleId, com.talkback.core.model.EndpointId("E01"))
        helloTargets().values.forEach { peer ->
            sendSignal(
                peer,
                buildSignedEnvelope(SignalType.HELLO, from, null, "hello", payload)
            )
        }
    }

    private fun wireIceCallback(session: TalkbackSession, remoteModuleId: String, engine: WebRtcAudioEngine) {
        engine.playbackDiagnosticTag = session.id
        engine.remoteTrackDiagnosticLogger = { playback ->
            val ice = qosMonitor.snapshot(remoteModuleId)?.iceState ?: "UNKNOWN"
            log(
                "remoteTrackAttached session=${session.id} ice=$ice playback=$playback peer=$remoteModuleId"
            )
            if (session.type == SessionType.CONFERENCE && session.accepted) {
                runOnCoordinator {
                    val live = sessions[session.id] ?: return@runOnCoordinator
                    if (!live.accepted || live.type != SessionType.CONFERENCE) return@runOnCoordinator
                    syncConferenceRelay(live, "remote_track_attached")
                }
            }
        }
        engine.setOnLocalIceCandidate { candidate ->
            runOnCoordinator {
                if (!sessions.containsKey(session.id)) return@runOnCoordinator
                if (!session.accepted &&
                    session.type == SessionType.UNICAST &&
                    !session.localInitiated
                ) {
                    return@runOnCoordinator
                }
                val peer = session.remotePeersByModule[remoteModuleId] ?: return@runOnCoordinator
                val remote = session.remote?.takeIf { it.moduleId.value == remoteModuleId }
                    ?: EndpointAddress(ModuleId(remoteModuleId), session.local.endpointId)
                val traceCtx = mediaRecoveryTraceContext(
                    session,
                    remoteModuleId,
                    remote.endpointId.value
                )
                MediaRecoveryCausalTrace.mediaIceCandidateGenerated(traceCtx)
                sendSignal(
                    peer,
                    buildSignedEnvelope(
                        SignalType.WEBRTC_ICE,
                        session.local,
                        remote,
                        session.id,
                        candidate
                    )
                )
                MediaRecoveryCausalTrace.mediaSignalCandidateSent(traceCtx)
            }
        }
    }

    private fun queuePendingIce(sessionId: String, moduleId: String, candidate: String) {
        pendingIceBySession
            .getOrPut(sessionId) { ConcurrentHashMap() }
            .getOrPut(moduleId) { mutableListOf() }
            .add(candidate)
    }

    private fun drainPendingIce(sessionId: String, moduleId: String, engine: WebRtcAudioEngine) {
        val moduleMap = pendingIceBySession[sessionId] ?: return
        val pending = moduleMap.remove(moduleId) ?: return
        val session = sessions[sessionId]
        pending.forEach { candidate ->
            engine.addIceCandidate(candidate)
            session?.let {
                MediaRecoveryCausalTrace.mediaIceCandidateApplied(
                    mediaRecoveryTraceContext(it, moduleId),
                    queued = true
                )
            }
        }
        if (moduleMap.isEmpty()) {
            pendingIceBySession.remove(sessionId)
        }
    }

    internal fun onIceStateChanged(scope: MediaBearerScope, bearerKey: String, state: String) {
        when (scope) {
            MediaBearerScope.UNICAST -> onUnicastIceStateChanged(bearerKey, state)
            MediaBearerScope.GROUP, MediaBearerScope.CONFERENCE -> onMeshIceStateChanged(bearerKey, state)
        }
    }

    /** Test / legacy entry: mesh ICE events keyed by remote module id. */
    internal fun onIceStateChanged(remoteModuleId: String, state: String) {
        onMeshIceStateChanged(remoteModuleId, state)
    }

    private fun onUnicastIceStateChanged(sessionId: String, state: String) {
        runOnCoordinator {
            val session = sessions[sessionId] ?: return@runOnCoordinator
            val remoteModuleId = session.remote?.moduleId?.value ?: return@runOnCoordinator
            qosMonitor.updateUnicastIceState(sessionId, remoteModuleId, state)
            log("ICE unicast $sessionId ($remoteModuleId) state=$state ${qosMonitor.formatSummary()}")
            when {
                IceConnectivity.isConnected(state) -> markUnicastConnected(session)
                IceConnectivity.isLost(state) -> {
                    if (session.participants.containsKey(remoteModuleId)) {
                        meshParticipant(session,remoteModuleId).media = when (state) {
                            "FAILED" -> MediaState.FAILED
                            else -> MediaState.RECONNECTING
                        }
                    }
                    if (!config.iceReconnectEnabled) return@runOnCoordinator
                    if (state == "DISCONNECTED" && session.unicastPhase == UnicastCallPhase.CONNECTING) {
                        log("${sessionTag(session)} Unicast ICE DISCONNECTED during connect, deferring teardown")
                        return@runOnCoordinator
                    }
                    onMediaLinkLost(session, remoteModuleId)
                }
            }
        }
    }

    private fun onMeshIceStateChanged(remoteModuleId: String, state: String) {
        runOnCoordinator {
            conferenceRecoveryController.notifyIceStateChanged(remoteModuleId, state)
            qosMonitor.updateIceState(remoteModuleId, state)
            log("ICE $remoteModuleId state=$state ${qosMonitor.formatSummary()}")
            maybeEmitIceTopologySnapshot(remoteModuleId)
            if (state == "CHECKING") {
                sessions.values
                    .filter { isConferenceSession(it) && it.accepted }
                    .forEach { session ->
                        if (conferenceParticipantManager.containsParticipant(session.id, remoteModuleId)) {
                            conferenceJoinLatencyTracker.onPeerIceChecking(session.id, remoteModuleId)
                        }
                    }
            }
            if (IceConnectivity.isConnected(state)) {
                // Gate-R1: ICE up must yield DECISION (session alive) or MISSING (session gone).
                maybeLogConferenceRuntimeMissing(remoteModuleId, state)
                val routeTrigger = RecoveryReevaluateTrigger.ROUTE_CONVERGED
                sessions.values
                    .filter { it.type == SessionType.GROUP && it.channelId != null }
                    .forEach { session ->
                        if (remoteModuleId in session.remotePeersByModule ||
                            remoteModuleId in session.meshCompletedModules
                        ) {
                            groupMeshReconciler.markConnected(session.channelId!!, remoteModuleId)
                            updateSessionReceivePlayback(session)
                        }
                    }
                sessions.values.forEach { session ->
                    val tracked = if (isConferenceSession(session)) {
                        conferenceParticipantManager.containsParticipant(session.id, remoteModuleId)
                    } else {
                        session.participants.containsKey(remoteModuleId)
                    }
                    if (tracked) {
                        if (isConferenceSession(session)) {
                            conferenceJoinLatencyTracker.onPeerIceConnected(session.id, remoteModuleId)
                            conferenceParticipantManager.onMediaConnected(session.id, remoteModuleId)
                            maybeNotifyRecoveryReachabilityChanged(session, remoteModuleId, routeTrigger)
                            // #83 / ADR-0022: ICE restoration always feeds completion evaluation.
                            // Controller records the fact; only evaluation may emit RECOVERED.
                            conferenceEdgeRecoveryController.onIceConnected(session.id, remoteModuleId)
                        } else {
                            meshParticipant(session, remoteModuleId).apply {
                                media = MediaState.CONNECTED
                                lastMediaChangeMs = System.currentTimeMillis()
                                if (invite == InviteState.INVITING || invite == InviteState.RINGING) {
                                    invite = InviteState.ACCEPTED
                                }
                            }
                        }
                    }
                }
                sessions.values
                    .filter {
                        it.type == SessionType.CONFERENCE &&
                            it.accepted &&
                            remoteModuleId == it.initiatorModuleId?.value
                    }
                    .forEach { session ->
                        if (pendingConferenceHostIceHangupBySession.containsKey(session.id)) {
                            cancelConferenceHostIceHangup(session.id)
                            log("[${session.traceId}] Conference host ICE recovered, keeping session")
                        }
                        session.channelId?.let { cancelHostRejoinRetry(it) }
                    }
                sessions.values
                    .filter { it.accepted && it.type == SessionType.CONFERENCE }
                    .forEach { session ->
                        cancelParticipantPrune(session.id, remoteModuleId)
                        if (remoteModuleId == session.initiatorModuleId?.value &&
                            session.initiatorModuleId != localModuleId &&
                            qosMonitor.isGroupConnected(remoteModuleId)
                        ) {
                            session.channelId?.let { clearConferenceRejoinState(it) }
                            conferenceReconnectStartedAtBySession.remove(session.id)
                            completeGroupMesh(session)
                            drainPendingGroupJoins(session.id)
                            scheduleGroupMeshRetries(session.id)
                        } else if (
                            remoteModuleId in conferenceMemberRemoteIds(session) &&
                            qosMonitor.isGroupConnected(remoteModuleId)
                        ) {
                            completeGroupMesh(session)
                            drainPendingGroupJoins(session.id)
                            scheduleGroupMeshRetries(session.id)
                        }
                        if (isConferenceUiReady(session)) {
                            conferenceReconnectStartedAtBySession.remove(session.id)
                        }
                        session.channelId?.let { maybeEvaluateMeetingStartCompletion(it) }
                        emitConferenceRuntimeProjection(session)
                    }
                sessions.values
                    .filter { it.type == SessionType.GROUP && it.accepted }
                    .forEach { session ->
                        if (remoteModuleId in session.memberModules.map { it.value } ||
                            remoteModuleId in session.groupMembers.map { it.moduleId.value }
                        ) {
                            GroupMembershipSupport.markOnline(session, remoteModuleId)
                            reconcileGroupMembership(session, "ice_connected_$remoteModuleId")
                        }
                        tryFlushPendingTransmit(session)
                        onGroupConvergenceBoundary(session)
                    }
                sessions.values
                    .filter { it.type == SessionType.CONFERENCE && it.accepted }
                    .forEach {
                        updateSessionReceivePlayback(it, "ice_$state")
                        tryEnsureConferenceDuplex(it)
                    }
                // Receive-path observers are required for all accepted conference sessions.
                // MESH conferences do not participate in backup-standby maintenance,
                // so this bootstrap path is intentionally separate.
                ConferenceIceConnectedSideEffects
                    .sessionsForReceivePathBootstrap(sessions.values)
                    .forEach { session ->
                        syncConferenceRelay(session, "ice_connected")
                    }
                ConferenceIceConnectedSideEffects
                    .sessionsForBackupStandbyMaintenance(sessions.values)
                    .forEach { session ->
                        maintainBackupStandby(session)
                    }
            }
            if (state == "CHECKING") {
                sessions.values
                    .filter { it.type == SessionType.GROUP && it.channelId != null }
                    .forEach { session ->
                        if (remoteModuleId in session.remotePeersByModule) {
                            groupMeshReconciler.markIceChecking(session.channelId!!, remoteModuleId)
                        }
                    }
                sessions.values
                    .filter {
                        it.accepted &&
                            isConferenceSession(it) &&
                            conferenceParticipantManager.containsParticipant(it.id, remoteModuleId)
                    }
                    .forEach { session ->
                        maybeNotifyRecoveryReachabilityChanged(
                            session,
                            remoteModuleId,
                            RecoveryReevaluateTrigger.ICE_CHECKING
                        )
                    }
                tryRecoverStuckCheckingPeer(remoteModuleId)
                tryRecoverStuckCheckingConferencePeer(remoteModuleId)
            }
            if (state == "FAILED" || state == "DISCONNECTED") {
                maybeNotifyRecoveryReachabilityForRemote(
                    remoteModuleId,
                    RecoveryReevaluateTrigger.ROUTE_LOST
                )
                sessions.values
                    .filter { it.type == SessionType.GROUP && it.accepted }
                    .forEach { updateSessionReceivePlayback(it, "ice_$state") }
                sessions.values
                    .filter { it.type == SessionType.GROUP && it.channelId != null }
                    .forEach { session ->
                        if (remoteModuleId in session.remotePeersByModule ||
                            remoteModuleId in session.meshCompletedModules
                        ) {
                            groupMeshReconciler.markDisconnected(session.channelId!!, remoteModuleId)
                        }
                        if (state == "FAILED") {
                            releaseFloorIfHolderUnavailable(session, remoteModuleId)
                        }
                    }
                sessions.values.forEach { session ->
                    val tracked = if (isConferenceSession(session)) {
                        conferenceParticipantManager.containsParticipant(session.id, remoteModuleId)
                    } else {
                        session.participants.containsKey(remoteModuleId)
                    }
                    if (tracked) {
                        val media = when (state) {
                            "FAILED" -> MediaState.FAILED
                            else -> MediaState.RECONNECTING
                        }
                        if (isConferenceSession(session)) {
                            conferenceParticipantManager.updateMediaState(session.id, remoteModuleId, media)
                        } else {
                            meshParticipant(session, remoteModuleId).media = media
                        }
                    }
                }
                sessions.values
                    .filter {
                        it.accepted &&
                            it.type == SessionType.CONFERENCE &&
                            (
                                remoteModuleId in conferenceMemberRemoteIds(it) ||
                                    remoteModuleId == it.initiatorModuleId?.value
                                )
                    }
                    .toList()
                    .forEach { session ->
                        notifyConferenceEdgeIceState(session, remoteModuleId, state)
                        if (remoteModuleId == session.initiatorModuleId?.value) {
                            markConferenceReconnecting(session)
                            if (session.initiatorModuleId == localModuleId) {
                                scheduleConferenceHostIceHangup(session, state)
                            } else {
                                handleParticipantHostLinkLoss(session, state)
                            }
                            return@forEach
                        }
                        if (isConferenceHostSession(session)) {
                            scheduleParticipantPrune(session, remoteModuleId, state)
                        } else {
                            log(
                                "${sessionTag(session)} Conference peer ICE $state for $remoteModuleId " +
                                    "(participant waiting for recovery)"
                            )
                        }
                    }
                if (!config.iceReconnectEnabled) return@runOnCoordinator
                sessions.values
                    .filter {
                        it.accepted &&
                            it.type != SessionType.CONFERENCE &&
                            it.type != SessionType.UNICAST &&
                            it.memberModules.any { m -> m.value == remoteModuleId }
                    }
                    .toList()
                    .forEach { session -> onMediaLinkLost(session, remoteModuleId) }
            }
        }
    }

    private fun bestSessionForChannel(channelId: String): TalkbackSession? {
        val candidates = sessions.values.filter { it.channelId == channelId }
        if (candidates.isEmpty()) return null
        return candidates.maxByOrNull { sessionPreferenceScore(it) }
    }

    private fun sessionPreferenceScore(session: TalkbackSession): Int {
        var score = 0
        if (session.accepted) score += 4
        if (session.type == SessionType.CONFERENCE) score += 2
        if (session.type.isMeshSession() && session.accepted) {
            val remoteIds = when (session.type) {
                SessionType.CONFERENCE -> conferenceMemberRemoteIds(session)
                else -> remoteModuleIds(session)
            }
            if (remoteIds.any { qosMonitor.isGroupConnected(it) }) score += 8
            if (session.type == SessionType.CONFERENCE && session.initiatorModuleId != localModuleId) {
                val hostId = session.initiatorModuleId?.value
                if (hostId != null && qosMonitor.isGroupConnected(hostId)) {
                    score += 16
                }
            }
        }
        return score
    }

    private fun meshSessionForChannel(channelId: String): TalkbackSession? {
        val meshSessions = sessions.values.filter {
            it.channelId == channelId && it.type.isMeshSession() && it.accepted
        }
        if (meshSessions.isEmpty()) return null
        return meshSessions.maxByOrNull { sessionPreferenceScore(it) }
    }

    private fun isConferenceHostSession(session: TalkbackSession): Boolean =
        session.type == SessionType.CONFERENCE &&
            session.accepted &&
            session.initiatorModuleId == localModuleId

    private fun isPeerMediaConnected(moduleId: String): Boolean =
        mediaRegistry.getMesh(moduleId) != null && qosMonitor.isGroupConnected(moduleId)

    private fun isUnicastMediaConnected(session: TalkbackSession): Boolean =
        mediaRegistry.getUnicast(session.id) != null && qosMonitor.isUnicastConnected(session.id)

    /**
     * Conference participants enter the live meeting once the host link is up.
     * Other roster members may still be inviting; they must not block channelReady.
     */
    private fun conferencePeersRequiredForReady(session: TalkbackSession): Set<String> {
        if (session.type != SessionType.CONFERENCE) return emptySet()
        val hostId = session.initiatorModuleId?.value ?: return conferenceMemberRemoteIds(session)
        if (hostId == localModuleId.value) return emptySet()
        return setOf(hostId)
    }

    /**
     * UI/session gate: host may enter the meeting room while inviting; participants need host ICE.
     * Duplex capture still uses [isSessionTransmitReady].
     */
    private fun isConferenceUiReady(session: TalkbackSession): Boolean {
        if (!session.accepted) return false
        if (isConferenceHostSession(session)) return true
        // R24-A / R15: meeting stays UI-open while edge recovering or after FAILED_MEDIA_RECOVERY
        val edgeFacts = conferenceEdgeRecoveryController.factsForSession(session.id)
        if (edgeFacts.anyRecovering || edgeFacts.anyFailedMediaRecovery) return true
        val required = conferencePeersRequiredForReady(session)
        if (required.isEmpty()) return countConnectedRemotes(session) > 0
        return required.all { isPeerMediaConnected(it) }
    }

    private fun isSessionTransmitReady(session: TalkbackSession): Boolean {
        if (!session.accepted) return false
        if (isConferenceHostSession(session)) {
            return countConnectedRemotes(session) > 0
        }
        if (session.type == SessionType.CONFERENCE) {
            val required = conferencePeersRequiredForReady(session)
            if (required.isEmpty()) {
                return countConnectedRemotes(session) > 0
            }
            return required.all { isPeerMediaConnected(it) }
        }
        if (session.type == SessionType.UNICAST) {
            return isUnicastMediaConnected(session)
        }
        val remoteIds = when (session.type) {
            SessionType.GROUP -> transmitPeerIdsForSession(session)
            else -> remoteModuleIds(session)
        }
        if (remoteIds.isEmpty()) {
            return false
        }
        return remoteIds.all { isPeerMediaConnected(it) }
    }

    private fun beginTransmitIfReady(session: TalkbackSession) {
        if (session.ptt.state != PttState.TALK) return
        if (isSessionTransmitReady(session)) {
            session.pendingTransmit = false
            val localIdentity = groupLocalIdentity(session)
            val stackActing = activityStack.topActingEndpointId()
            if (stackActing != null && stackActing != localIdentity.key) {
                log(
                    "CAPTURE_STACK_MISMATCH ${sessionTag(session)} " +
                        "stackActing=$stackActing local=${localIdentity.key}"
                )
                return
            }
            moduleMixer.setActiveCapture(localIdentity)
            audioRouter.selectInput(localIdentity)
            startSessionCapture(session)
            syncProgramRelay(session)
        } else {
            session.pendingTransmit = true
            stopSessionCapture(session)
            if (isLocalFloorHolder(session)) {
                scheduleAcquireReleaseIfNeeded(session)
            }
            if (session.type == SessionType.GROUP) {
                emitGroupTopologySnapshot(TopologySnapshotReason.PTT_BLOCKED, session)
                onGroupConvergenceBoundary(session)
            }
        }
    }

    private fun tryFlushPendingTransmit(session: TalkbackSession) {
        if (!session.pendingTransmit) return
        if (session.ptt.state != PttState.TALK) return
        if (!isLocalFloorHolder(session)) return
        beginTransmitIfReady(session)
    }

    private fun sessionMediaHealthy(session: TalkbackSession): Boolean {
        if (!session.accepted) return false
        if (session.type == SessionType.UNICAST) {
            return mediaRegistry.getUnicast(session.id) != null &&
                when (qosMonitor.snapshotUnicast(session.id)?.iceState) {
                    "DISCONNECTED", "FAILED" -> false
                    else -> true
                }
        }
        val remoteIds = when (session.type) {
            SessionType.GROUP -> transmitPeerIdsForSession(session)
            else -> remoteModuleIds(session)
        }
        if (remoteIds.isEmpty()) {
            return session.type == SessionType.CONFERENCE
        }
        return remoteIds.all { id ->
            if (meshEngineForSession(session, id) == null) return false
            when (qosMonitor.snapshot(id)?.iceState) {
                "DISCONNECTED", "FAILED" -> false
                else -> true
            }
        }
    }

    private fun remoteModuleIds(session: TalkbackSession): Set<String> = when {
        session.remotePeersByModule.isNotEmpty() -> session.remotePeersByModule.keys
        session.remote != null -> setOf(session.remote!!.moduleId.value)
        else -> emptySet()
    }

    private fun countConnectedRemotes(session: TalkbackSession): Int =
        connectedMeshPeerIds(session).size

    /** Peers with an active CONNECTED ICE link, regardless of roster invite state. */
    private fun connectedMeshPeerIds(session: TalkbackSession): Set<String> {
        val candidates = when (session.type) {
            SessionType.CONFERENCE -> {
                val roster = conferenceMemberRemoteIds(session)
                roster + session.remotePeersByModule.keys
            }
            SessionType.GROUP -> transmitPeerIdsForSession(session)
            else -> remoteModuleIds(session)
        }
        return candidates.filter { id ->
            id != localModuleId.value &&
                meshEngineForSession(session, id) != null &&
                qosMonitor.isGroupConnected(id)
        }.toSet()
    }

    private fun isSessionMediaNegotiating(session: TalkbackSession): Boolean {
        if (!session.accepted) return true
        if (isSessionTransmitReady(session)) return false
        if (session.type == SessionType.UNICAST) {
            return mediaRegistry.getUnicast(session.id) != null &&
                IceConnectivity.isNegotiating(qosMonitor.snapshotUnicast(session.id)?.iceState)
        }
        val remoteIds = when (session.type) {
            SessionType.CONFERENCE -> conferencePeersRequiredForReady(session).ifEmpty {
                conferenceMemberRemoteIds(session)
            }
            else -> remoteModuleIds(session)
        }
        if (remoteIds.isEmpty()) return false
        return remoteIds.all { id ->
            meshEngineForSession(session, id) != null &&
                IceConnectivity.isNegotiating(qosMonitor.snapshot(id)?.iceState)
        }
    }

    private fun hasLiveMeshNegotiation(
        session: TalkbackSession,
        remoteIds: Collection<String>
    ): Boolean = remoteIds.any { moduleId ->
        val ice = qosMonitor.snapshot(moduleId)?.iceState
        if (IceConnectivity.isLiveNegotiation(ice)) return true
        return mediaRegistry.getMesh(moduleId) != null || moduleId in session.meshCompletedModules
    }

    private fun isMeshSessionStuck(session: TalkbackSession): Boolean {
        if (!session.type.isMeshSession() || !session.accepted) return false
        if (isSessionTransmitReady(session)) return false
        val remoteIds = remoteModuleIds(session)
        if (remoteIds.isEmpty()) return false
        val now = System.currentTimeMillis()
        val awaitingMeshAccept = remoteIds.any { it !in session.meshCompletedModules }
        if (awaitingMeshAccept) {
            val pendingInviteGraceMs = if (session.meshCompletedModules.isNotEmpty()) {
                config.meshNegotiationGraceMs
            } else {
                120_000L
            }
            if (now - session.lastActiveMs < pendingInviteGraceMs) return false
        }
        if (now - session.lastActiveMs < config.meshNegotiationGraceMs) return false
        return remoteIds.any { id ->
            val engine = meshEngineForSession(session, id)
            val snap = qosMonitor.snapshot(id)
            val ice = snap?.iceState
            val since = snap?.updatedMs ?: session.lastActiveMs
            when {
                engine == null -> now - session.lastActiveMs > config.iceNegotiationTimeoutMs
                IceConnectivity.isConnected(ice) -> false
                ice == "DISCONNECTED" || ice == "FAILED" || ice == "CLOSED" ->
                    now - since > config.meshNegotiationGraceMs
                now - since > config.iceNegotiationTimeoutMs -> true
                else -> false
            }
        }
    }

    /** Group PTT and Meeting share one channel — only one mesh session type may be active. */
    private fun endConflictingMeshSessions(
        channelId: String,
        incomingType: SessionType,
        exceptSessionId: String? = null
    ) {
        sessions.values
            .filter { session ->
                session.channelId == channelId &&
                    session.type.isMeshSession() &&
                    session.type != incomingType &&
                    session.id != exceptSessionId
            }
            .toList()
            .forEach { session ->
                log("[${session.traceId}] Ending ${session.type.name} on $channelId for $incomingType")
                hangupInternal(session.id)
            }
    }

    private fun findReusableMeshSession(channelId: String, sessionType: SessionType): TalkbackSession? {
        val existing = sessions.values.firstOrNull { it.channelId == channelId && it.type == sessionType }
            ?: return null
        if (isMeshSessionStuck(existing)) {
            log("[${existing.traceId}] Stale ${sessionType.name} session on $channelId, closing")
            hangupInternal(existing.id)
            return null
        }
        return existing
    }

    private fun scheduleReDialAfterMediaLoss(session: TalkbackSession, remoteModuleId: String) {
        if (!config.autoReDialOnModuleRecovery) return
        val remote = session.groupMembers.firstOrNull { it.moduleId.value == remoteModuleId }
            ?: session.remote?.takeIf { it.moduleId.value == remoteModuleId }
            ?: return
        reDialByRemoteModule[remoteModuleId] = ReDialRecord(
            session.local,
            remote,
            session.channelId,
            session.groupMembers.takeIf { members -> members.size > 1 },
            session.type
        )
    }

    private fun cancelConferenceHostIceHangup(sessionId: String) {
        pendingConferenceHostIceHangupBySession.remove(sessionId)?.cancel(false)
    }

    private fun cancelParticipantPrune(sessionId: String, moduleId: String) {
        pendingParticipantPruneBySession[sessionId]?.remove(moduleId)?.cancel(false)
        if (pendingParticipantPruneBySession[sessionId]?.isEmpty() == true) {
            pendingParticipantPruneBySession.remove(sessionId)
        }
    }

    private fun cancelAllParticipantPrunes(sessionId: String) {
        pendingParticipantPruneBySession.remove(sessionId)?.values?.forEach { it.cancel(false) }
    }

    private fun cancelHostRejoinRetry(channelId: String) {
        pendingHostRejoinByChannel.remove(channelId)?.cancel(false)
        hostRejoinAttemptByChannel.remove(channelId)
    }

    private fun markConferenceReconnecting(session: TalkbackSession) {
        if (session.type != SessionType.CONFERENCE || !session.accepted) return
        conferenceReconnectStartedAtBySession.putIfAbsent(session.id, System.currentTimeMillis())
    }

    private fun deferConferenceParticipantPruneIfRecovering(
        session: TalkbackSession,
        moduleId: String
    ): Boolean {
        if (!conferenceEdgeRecoveryController.isEdgeRecovering(session.id, moduleId)) {
            return false
        }
        log(
            "[${session.traceId}] RECOVERY_PRUNE_DEFERRED session=${session.id} remote=$moduleId"
        )
        return true
    }

    private fun scheduleParticipantPrune(
        session: TalkbackSession,
        moduleId: String,
        iceState: String
    ) {
        if (deferConferenceParticipantPruneIfRecovering(session, moduleId)) return
        if (iceState == "FAILED") {
            cancelParticipantPrune(session.id, moduleId)
            tryAuthorityPruneFromIce(session, moduleId, iceState)
            return
        }
        if (iceState != "DISCONNECTED") return
        val graceMs = config.conferenceParticipantPruneGraceMs
        if (graceMs <= 0L) {
            cancelParticipantPrune(session.id, moduleId)
            tryAuthorityPruneFromIce(session, moduleId, iceState)
            return
        }
        val bySession = pendingParticipantPruneBySession.getOrPut(session.id) { ConcurrentHashMap() }
        if (bySession.containsKey(moduleId)) {
            log("[${session.traceId}] Conference ICE DISCONNECTED for $moduleId, grace already running")
            return
        }
        log("[${session.traceId}] Conference ICE DISCONNECTED for $moduleId, waiting ${graceMs}ms before prune")
        val sessionId = session.id
        val future = scheduler.schedule({
            runOnCoordinator {
                pendingParticipantPruneBySession[sessionId]?.remove(moduleId)
                val current = sessions[sessionId] ?: return@runOnCoordinator
                if (current.type != SessionType.CONFERENCE || !current.accepted) return@runOnCoordinator
                when (qosMonitor.snapshot(moduleId)?.iceState) {
                    "CONNECTED" -> {
                        log("[${current.traceId}] Conference peer $moduleId ICE recovered, keeping member")
                    }
                    else -> {
                        if (deferConferenceParticipantPruneIfRecovering(current, moduleId)) {
                            return@runOnCoordinator
                        }
                        val ice = qosMonitor.snapshot(moduleId)?.iceState ?: "UNKNOWN"
                        tryAuthorityPruneFromIce(current, moduleId, ice)
                    }
                }
            }
        }, graceMs, TimeUnit.MILLISECONDS)
        bySession[moduleId] = future
    }

    /**
     * ICE path may observe peer loss early; prune still requires [canAuthorityPrune]
     * (ADR-0024 R29-E). ICE alone must not authorize AUTHORITY_PRUNE.
     */
    private fun tryAuthorityPruneFromIce(
        session: TalkbackSession,
        moduleId: String,
        iceState: String
    ) {
        if (!canAuthorityPrune(session, moduleId)) {
            log(
                "[${session.traceId}] Conference ICE $iceState for $moduleId, " +
                    "AUTHORITY_PRUNE deferred until obligation prune-eligible"
            )
            return
        }
        log("[${session.traceId}] Conference ICE $iceState for $moduleId, pruning peer")
        removeConferenceParticipant(
            session,
            moduleId,
            AuthorityMembershipMutationSource.AUTHORITY_PRUNE
        )
        repairConferenceMeshAfterLeave(session)
    }

    private fun handleParticipantHostLinkLoss(session: TalkbackSession, iceState: String) {
        val hostId = session.initiatorModuleId?.value ?: return
        markConferenceReconnecting(session)
        log("[${session.traceId}] Conference host ICE $iceState (edge recovery active for $hostId)")
    }

    /** Participant: host ICE may not start after accept; retry GROUP_JOIN ICE restart on a short cadence. */
    private fun scheduleConferenceHostLinkKick(session: TalkbackSession, hostModuleId: String) {
        val sessionId = session.id
        CONFERENCE_HOST_LINK_KICK_DELAYS_MS.forEach { delayMs ->
            scheduler.schedule({
                runOnCoordinator {
                    val current = sessions[sessionId] ?: return@runOnCoordinator
                    if (current.type != SessionType.CONFERENCE || !current.accepted) return@runOnCoordinator
                    if (isConferenceHostSession(current)) return@runOnCoordinator
                    if (current.initiatorModuleId?.value != hostModuleId) return@runOnCoordinator
                    if (IceConnectivity.isConnected(qosMonitor.snapshot(hostModuleId)?.iceState)) {
                        completeGroupMesh(current)
                        tryEnsureConferenceDuplex(current)
                        return@runOnCoordinator
                    }
                    val hostEndpoint = resolveConferenceHostEndpoint(current, hostModuleId)
                    if (attemptConferencePeerIceRestart(current, hostModuleId, hostEndpoint)) {
                        log("${sessionTag(current)} Conference host link kick -> $hostModuleId delay=${delayMs}ms")
                    }
                }
            }, delayMs, TimeUnit.MILLISECONDS)
        }
    }

    private fun attemptConferenceIceRestartForRecovery(remoteModuleId: String): Boolean {
        val session = sessions.values.firstOrNull {
            it.type == SessionType.CONFERENCE &&
                it.accepted &&
                (
                    remoteModuleId in conferenceMemberRemoteIds(it) ||
                        remoteModuleId == it.initiatorModuleId?.value
                    )
        } ?: return false
        val remote = meshRoster(session).firstOrNull { it.moduleId.value == remoteModuleId }
            ?: resolveConferenceHostEndpoint(session, remoteModuleId)
        return attemptConferencePeerIceRestart(session, remoteModuleId, remote)
    }

    private fun negotiateConferencePeerAfterRecreate(remoteModuleId: String) {
        val session = sessions.values.firstOrNull {
            it.type == SessionType.CONFERENCE &&
                it.accepted &&
                (
                    remoteModuleId in conferenceMemberRemoteIds(it) ||
                        remoteModuleId == it.initiatorModuleId?.value
                    )
        } ?: return
        val remote = meshRoster(session).firstOrNull { it.moduleId.value == remoteModuleId }
            ?: resolveConferenceHostEndpoint(session, remoteModuleId)
        attemptConferencePeerOffer(session, remoteModuleId, remote, iceRestart = false)
    }

    private fun attemptConferencePeerOffer(
        session: TalkbackSession,
        remoteModuleId: String,
        remote: EndpointAddress,
        iceRestart: Boolean
    ): Boolean {
        if (!config.iceReconnectEnabled) return false
        val peer = session.remotePeersByModule[remoteModuleId]
        if (peer == null) {
            log("[${session.traceId}] Conference offer skipped: no signal peer for $remoteModuleId")
            return false
        }
        val engine = meshEngineForSession(session, remoteModuleId)
        if (engine == null) {
            log("[${session.traceId}] Conference offer skipped: no mesh engine for $remoteModuleId")
            return false
        }
        wireIceCallback(session, remoteModuleId, engine)
        val traceCtx = mediaRecoveryTraceContext(
            session,
            remoteModuleId,
            remote.endpointId.value,
            iceRestart = iceRestart
        )
        if (iceRestart) {
            MediaRecoveryCausalTrace.recoveryIceRestartDispatched(traceCtx)
        }
        val offer = engine.createOffer(iceRestart = iceRestart)
        MediaRecoveryCausalTrace.mediaSignalOfferSent(traceCtx)
        drainPendingIce(session.id, remoteModuleId, engine)
        sendSignal(
            peer,
            buildSignedEnvelope(
                SignalType.GROUP_JOIN,
                session.local,
                remote,
                session.id,
                groupPayloadBase(
                    session,
                    joinIntent = if (iceRestart) {
                        ConferenceJoinIntent.RECOVERY_REATTACH
                    } else {
                        ConferenceJoinIntent.NORMAL_JOIN
                    }
                ).copy(sdp = offer).encode()
            )
        )
        log(
            "[${session.traceId}] Conference ${if (iceRestart) "ICE restart" else "offer"} sent -> $remoteModuleId"
        )
        return true
    }

    private fun attemptConferencePeerIceRestart(
        session: TalkbackSession,
        remoteModuleId: String,
        remote: EndpointAddress
    ): Boolean {
        return attemptConferencePeerOffer(session, remoteModuleId, remote, iceRestart = true)
    }

    private fun scheduleHostRejoinRetry(
        channelId: String,
        session: TalkbackSession,
        immediate: Boolean
    ) {
        if (pendingHostRejoinByChannel.containsKey(channelId)) return
        val hostId = session.initiatorModuleId?.value ?: return
        val attempt = hostRejoinAttemptByChannel[channelId] ?: 0
        if (attempt >= HOST_REJOIN_BACKOFF_MS.size) {
            log("[${session.traceId}] Conference host rejoin retries exhausted on $channelId")
            return
        }
        val delayMs = if (immediate) 0L else HOST_REJOIN_BACKOFF_MS[attempt]
        val future = scheduler.schedule({
            runOnCoordinator {
                pendingHostRejoinByChannel.remove(channelId)
                val current = sessions[session.id] ?: return@runOnCoordinator
                if (current.type != SessionType.CONFERENCE || !current.accepted) return@runOnCoordinator
                if (qosMonitor.isGroupConnected(hostId)) {
                    cancelHostRejoinRetry(channelId)
                    return@runOnCoordinator
                }
                hostRejoinAttemptByChannel[channelId] = attempt + 1
                val hint = lastRejoinableConferenceByChannel[channelId]
                val hostSessionId = hint?.hostSessionId?.takeIf { it.isNotBlank() } ?: current.id
                val authority = resolveConferenceHostEndpoint(current, hostId).let { resolved ->
                    if (
                        meshRoster(current).none { it.moduleId.value == hostId } &&
                        current.remote?.moduleId?.value != hostId
                    ) {
                        hint?.let {
                            EndpointAddress(
                                ModuleId(it.hostModuleId),
                                EndpointId(it.hostKey.substringAfter('-', "E01"))
                            )
                        } ?: resolved
                    } else {
                        resolved
                    }
                }
                val sent = sendConferenceRejoinIntentInternal(channelId, authority, hostSessionId)
                log("[${current.traceId}] Participant host rejoin retry sent=$sent attempt=${attempt + 1}")
                if (!sent && attempt + 1 < HOST_REJOIN_BACKOFF_MS.size) {
                    scheduleHostRejoinRetry(channelId, current, immediate = false)
                }
            }
        }, delayMs, TimeUnit.MILLISECONDS)
        pendingHostRejoinByChannel[channelId] = future
    }

    private fun scheduleConferenceHostIceHangup(session: TalkbackSession, iceState: String) {
        val hostId = session.initiatorModuleId?.value ?: return
        if (session.initiatorModuleId != localModuleId) {
            handleParticipantHostLinkLoss(session, iceState)
            return
        }
        if (iceState == "FAILED") {
            cancelConferenceHostIceHangup(session.id)
            log("[${session.traceId}] Conference host ICE FAILED, ending session")
            hangupInternal(session.id)
            return
        }
        if (iceState != "DISCONNECTED") return
        val graceMs = config.conferenceHostIceReconnectGraceMs
        if (graceMs <= 0L) {
            cancelConferenceHostIceHangup(session.id)
            log("[${session.traceId}] Conference host ICE DISCONNECTED, ending session")
            hangupInternal(session.id)
            return
        }
        if (pendingConferenceHostIceHangupBySession.containsKey(session.id)) {
            log("[${session.traceId}] Conference host ICE DISCONNECTED, grace timer already running")
            return
        }
        log("[${session.traceId}] Conference host ICE DISCONNECTED, waiting ${graceMs}ms before ending session")
        val sessionId = session.id
        val future = scheduler.schedule({
            runOnCoordinator {
                pendingConferenceHostIceHangupBySession.remove(sessionId)
                val current = sessions[sessionId] ?: return@runOnCoordinator
                if (current.type != SessionType.CONFERENCE || !current.accepted) return@runOnCoordinator
                val hostIce = qosMonitor.snapshot(hostId)?.iceState
                if (IceConnectivity.isConnected(hostIce)) {
                    log("[${current.traceId}] Conference host ICE recovered, keeping session")
                } else {
                    val ice = hostIce ?: "UNKNOWN"
                    log("[${current.traceId}] Conference host ICE still $ice after grace, ending session")
                    hangupInternal(sessionId)
                }
            }
        }, graceMs, TimeUnit.MILLISECONDS)
        pendingConferenceHostIceHangupBySession[sessionId] = future
    }

    private fun handleAnchorFailover(session: TalkbackSession, failedAnchorId: String) {
        val members = memberModuleIds(session)
        val current = ModuleId(failedAnchorId)
        val backup = session.backupAnchorModuleId
        val next = when {
            backup != null && backup != current && backup in members -> backup
            else -> {
                val remaining = members.filter { it != current }.toSet()
                electAnchorRoles(remaining)?.primary?.takeIf { it != current }
                    ?: AnchorElection.nextAnchor(members, current)
            }
        }
        if (next == null) {
            log("[${session.traceId}] Anchor failover exhausted, ending session")
            hangupInternal(session.id)
            return
        }
        val newEpoch = AnchorAuthority.nextEpochAfterFailover(session.anchorEpoch)
        log("[${session.traceId}] Anchor failover $failedAnchorId -> ${next.value} epoch=$newEpoch")
        session.anchorModuleId = next
        session.anchorEpoch = newEpoch
        session.anchorTenureStartMs = System.currentTimeMillis()
        val remainingForBackup = members.filter { it != current && it != next }.toSet()
        session.backupAnchorModuleId = electAnchorRoles(remainingForBackup)?.primary
        programAudioBus.clear(session.id)
        conferenceAudioBus.clear(session.id)
        receivePathLivenessObserver.clearSession(session.id)
        if (session.floor.owner()?.moduleId == current) {
            session.floor.owner()?.let { session.floor.release(it) }
            session.ptt.onEvent(PttEvent.Release)
            session.pendingTransmit = false
            stopSessionCapture(session)
            broadcastFloorRelease(session)
        }
        mediaRegistry.releaseGroup(failedAnchorId)
        session.remotePeersByModule.remove(failedAnchorId)
        session.meshCompletedModules.remove(failedAnchorId)
        session.participants.remove(failedAnchorId)
        markMembershipSuspect(session, failedAnchorId)
        if (localModuleId == next) {
            completeGroupMesh(session)
            drainPendingGroupJoins(session.id)
            scheduleGroupMeshRetries(session.id)
            syncConferenceRelay(session, "anchor_failover_host")
        } else {
            val hadStandby = session.backupStandbyPeers.contains(next.value) &&
                qosMonitor.isGroupConnected(next.value)
            if (hadStandby) {
                log("${sessionTag(session)} Promoting half-hot standby link to ${next.value}")
                session.backupStandbyPeers.remove(next.value)
            } else {
                offerGroupMeshJoin(session, next)
            }
        }
        session.channelId?.let { reconcileGroupMeshInternal(it) }
        maintainBackupStandby(session)
        updateSessionReceivePlayback(session, "anchor_failover")
        syncConferenceRelay(session, "anchor_failover")
        scheduleReconcile("anchor_failover")
    }

    private fun onMediaLinkLost(session: TalkbackSession, remoteModuleId: String) {
        if (!sessions.containsKey(session.id)) return
        if (session.type == SessionType.UNICAST) {
            log("[${session.traceId}] Unicast media link lost, tearing down call")
            mediaRegistry.releaseUnicast(session.id)
            hangupInternal(session.id)
            return
        }
        if (session.type == SessionType.CONFERENCE) {
            if (remoteModuleId !in conferenceMemberRemoteIds(session)) return
            if (remoteModuleId == session.initiatorModuleId?.value) {
                if (session.initiatorModuleId == localModuleId) {
                    scheduleConferenceHostIceHangup(session, "DISCONNECTED")
                } else {
                    handleParticipantHostLinkLoss(session, "DISCONNECTED")
                }
                return
            }
            if (isConferenceHostSession(session)) {
                scheduleParticipantPrune(session, remoteModuleId, "DISCONNECTED")
            } else {
                log(
                    "[${session.traceId}] Conference media link lost for $remoteModuleId " +
                        "(participant waiting for recovery)"
                )
            }
            return
        }
        if (session.type == SessionType.GROUP &&
            session.mediaTopology == GroupMediaTopology.ANCHOR &&
            remoteModuleId == session.anchorModuleId?.value
        ) {
            handleAnchorFailover(session, remoteModuleId)
            return
        }
        if (session.type == SessionType.CONFERENCE &&
            session.mediaTopology == GroupMediaTopology.ANCHOR &&
            remoteModuleId == session.anchorModuleId?.value
        ) {
            handleAnchorFailover(session, remoteModuleId)
            return
        }
        if (session.type == SessionType.GROUP && remoteModuleIds(session).size > 1) {
            log("[${session.traceId}] Group media link lost for $remoteModuleId, marking SUSPECT")
            GroupMembershipSupport.markSuspect(session, remoteModuleId, System.currentTimeMillis())
            releasePeerMediaOnly(session, remoteModuleId, resetQos = false)
            scheduleReconcile("ice_lost_$remoteModuleId")
            updateSessionReceivePlayback(session, "group_peer_suspect")
            return
        }
        log("[${session.traceId}] Media link lost for $remoteModuleId, tearing down session")
        scheduleReDialAfterMediaLoss(session, remoteModuleId)
        mediaRegistry.releaseGroup(remoteModuleId)
        hangupInternal(session.id)
    }

    private fun onRemoteModuleRecovered(moduleId: String) {
        log("Remote module recovered: $moduleId")
        maybeNotifyRecoveryReachabilityForRemote(
            moduleId,
            RecoveryReevaluateTrigger.REMOTE_MODULE_RECOVERED
        )
        cleanupUnhealthySessions()
        if (tryHostReinviteConferencePeer(moduleId)) return
        if (tryReinviteGroupPeerPairwise(moduleId)) return
        tryReDialForModule(moduleId)
    }

    private fun yieldLocalGroupSessionsForIncomingInvite(channelId: String, incomingSessionId: String) {
        sessions.values
            .filter {
                it.channelId == channelId &&
                    it.type == SessionType.GROUP &&
                    it.id != incomingSessionId &&
                    it.localInitiated
            }
            .forEach {
                log("[${it.traceId}] Yielding local group mesh to host invite on $channelId")
                hangupInternal(it.id)
            }
    }

    /** Pairwise offerer (local < peer) invites late peers into the live GROUP mesh. */
    private fun tryReinviteGroupPeerPairwise(moduleId: String): Boolean {
        if (moduleId == localModuleId.value) return false
        val mesh = sessions.values.firstOrNull {
            it.type == SessionType.GROUP && it.accepted && !it.isForegroundSuspended()
        } ?: return false
        val anchor = resolveAnchorModuleId(mesh)
        if (localModuleId != anchor && mesh.mediaTopology == GroupMediaTopology.ANCHOR) return false
        if (localModuleId.value >= moduleId && mesh.mediaTopology == GroupMediaTopology.MESH) return false
        if (moduleId in remoteModuleIds(mesh) &&
            qosMonitor.isGroupConnected(moduleId)
        ) {
            return false
        }
        if (!isModuleDialable(moduleId)) return false
        val endpointId = resolvePrimaryEndpointId(moduleId, null) ?: "E01"
        val remote = EndpointAddress(ModuleId(moduleId), EndpointId(endpointId))
        discoveredByModule[moduleId]?.let { rememberSignalPeer(moduleId, it) }
        val sent = sendGroupMeshInvitesInternal(mesh.id, listOf(remote))
        if (sent > 0) {
            log("[${mesh.traceId}] Pairwise re-invited $moduleId into group mesh sent=$sent")
        }
        return sent > 0
    }

    /**
     * Proactively offer an ICE-restart when a GROUP peer stays in CHECKING/NEW longer than
     * [TalkbackCoordinatorConfig.iceNegotiationTimeoutMs], so recovery does not depend solely
     * on the remote sending GROUP_JOIN.
     */
    private fun tryRecoverStuckCheckingPeer(moduleId: String) {
        val snap = qosMonitor.snapshot(moduleId) ?: return
        val ice = snap.iceState
        if (ice != "CHECKING" && ice != "NEW") return
        val now = System.currentTimeMillis()
        if (now - snap.updatedMs < config.iceNegotiationTimeoutMs) return
        val session = sessions.values.firstOrNull {
            it.type == SessionType.GROUP &&
                it.accepted &&
                !it.isForegroundSuspended() &&
                moduleId in remoteModuleIds(it)
        } ?: return
        val channelId = session.channelId ?: return
        if (!groupMeshReconciler.canReconnect(channelId, moduleId, ice)) return
        if (tryReinviteGroupPeerPairwise(moduleId)) {
            log("${sessionTag(session)} Forcing ICE-restart offer for $moduleId stuck in $ice")
        }
    }

    /**
     * ICE callback path: recover conference host links (participant) or re-invite stuck
     * participants (host) after [TalkbackCoordinatorConfig.iceNegotiationTimeoutMs].
     */
    private fun tryRecoverStuckCheckingConferencePeer(moduleId: String) {
        val snap = qosMonitor.snapshot(moduleId) ?: return
        val ice = snap.iceState
        if (ice != "CHECKING" && ice != "NEW") return
        val now = System.currentTimeMillis()
        if (now - snap.updatedMs < config.iceNegotiationTimeoutMs) return

        sessions.values
            .filter {
                it.type == SessionType.CONFERENCE &&
                    it.accepted &&
                    !isConferenceHostSession(it) &&
                    it.initiatorModuleId?.value == moduleId
            }
            .forEach { session ->
                recoverStuckConferenceHostLink(session, moduleId, ice)
            }

        sessions.values
            .filter {
                it.type == SessionType.CONFERENCE &&
                    it.accepted &&
                    isConferenceHostSession(it) &&
                    moduleId in conferenceMemberRemoteIds(it) &&
                    !qosMonitor.isGroupConnected(moduleId)
            }
            .forEach { session ->
                val target = session.groupMembers.filter { it.moduleId.value == moduleId }
                if (target.isNotEmpty()) {
                    recoverStuckConferenceParticipantOnHost(session, moduleId, ice, target.first())
                }
            }
    }

    /** Periodic maintenance: same recovery as [tryRecoverStuckCheckingConferencePeer] per session. */
    private fun recoverStuckCheckingConferencePeers(session: TalkbackSession) {
        if (!session.accepted || session.type != SessionType.CONFERENCE) return
        val now = System.currentTimeMillis()
        if (!isConferenceHostSession(session)) {
            val hostId = session.initiatorModuleId?.value ?: return
            val snap = qosMonitor.snapshot(hostId) ?: return
            val ice = snap.iceState
            if ((ice == "CHECKING" || ice == "NEW") && now - snap.updatedMs >= config.iceNegotiationTimeoutMs) {
                recoverStuckConferenceHostLink(session, hostId, ice)
            }
            return
        }
        session.groupMembers.forEach { peer ->
            val moduleId = peer.moduleId.value
            if (moduleId == localModuleId.value || qosMonitor.isGroupConnected(moduleId)) return@forEach
            val snap = qosMonitor.snapshot(moduleId) ?: return@forEach
            val ice = snap.iceState
            if ((ice == "CHECKING" || ice == "NEW") && now - snap.updatedMs >= config.iceNegotiationTimeoutMs) {
                recoverStuckConferenceParticipantOnHost(session, moduleId, ice, peer)
            }
        }
    }

    private fun recoverStuckConferenceParticipantOnHost(
        session: TalkbackSession,
        moduleId: String,
        ice: String,
        remote: EndpointAddress
    ) {
        val accepted = moduleId in session.meshCompletedModules ||
            meshParticipant(session,moduleId).invite == InviteState.ACCEPTED
        if (accepted || meshEngineForSession(session, moduleId) != null) {
            notifyConferenceEdgeIceState(session, moduleId, ice)
            log("[${session.traceId}] Conference recovery escalation for $moduleId stuck in $ice")
            return
        }
        val sent = sendConferenceInvitesInternal(session.id, listOf(remote))
        if (sent > 0) {
            log("[${session.traceId}] Re-invited conference peer $moduleId stuck in $ice")
        }
    }

    private fun recoverStuckConferenceHostLink(session: TalkbackSession, hostId: String, ice: String) {
        notifyConferenceEdgeIceState(session, hostId, ice)
        if (meshEngineForSession(session, hostId) == null) {
            log("${sessionTag(session)} Conference host recovery waiting for edge reattach $hostId stuck in $ice")
        } else {
            log("${sessionTag(session)} Conference host recovery escalation for $hostId stuck in $ice")
        }
    }

    private fun recoverStuckCheckingGroupPeers(session: TalkbackSession) {
        if (!session.accepted || session.channelId == null) return
        val channelId = session.channelId!!
        val now = System.currentTimeMillis()
        remoteModuleIds(session).forEach { moduleId ->
            val snap = qosMonitor.snapshot(moduleId) ?: return@forEach
            val ice = snap.iceState
            if (ice != "CHECKING" && ice != "NEW") return@forEach
            if (now - snap.updatedMs < config.iceNegotiationTimeoutMs) return@forEach
            if (!groupMeshReconciler.canReconnect(channelId, moduleId, ice)) return@forEach
            if (tryReinviteGroupPeerPairwise(moduleId)) {
                log("${sessionTag(session)} Periodic ICE-restart offer for $moduleId stuck in $ice")
            }
        }
    }

    private fun reconcileGroupMeshInternal(channelId: String) {
        endStaleConferenceBlockingGroup(channelId)
        if (blocksGroupOnChannel(channelId)) return
        if (isMeshRepairSuppressed(channelId)) return
        val local = localEndpointAddress() ?: return
        val dialable = dialableRemoteModuleIds()
        if (dialable.isEmpty()) return
        val allModules = dialable + localModuleId

        val session = sessions.values.firstOrNull {
            it.channelId == channelId && it.type == SessionType.GROUP
        }

        if (session == null) {
            val primary = resolveBootstrapPrimary(allModules)
            if (primary != null && localModuleId != primary) {
                log("Waiting for primary ${primary.value} to bootstrap GROUP on $channelId")
                observeGroupTransitionBootstrapAttempt(
                    channelId = channelId,
                    resolvedPrimary = primary.value,
                    waitingForPrimary = true,
                    meshRecoveryState = "waiting_primary"
                )
                return
            }
            val inviteModuleIds = GroupMeshPlanner.inviteTargets(localModuleId, allModules)
            if (inviteModuleIds.isEmpty()) return
            val inviteEndpoints = inviteModuleIds.mapNotNull { endpointForDialableModule(it) }
            if (inviteEndpoints.isEmpty()) return
            observeGroupTransitionBootstrapAttempt(
                channelId = channelId,
                resolvedPrimary = primary?.value,
                waitingForPrimary = false,
                meshRecoveryState = "mesh_create"
            )
            runCatching {
                meshCallInternal(
                    local,
                    inviteEndpoints,
                    channelId,
                    SessionType.GROUP,
                    MeshSessionMode.GROUP,
                    config.maxGroupModules
                )
            }.onFailure { log("reconcileGroupMesh bootstrap failed on $channelId: ${it.message}") }
            return
        }

        if (!session.accepted || session.isForegroundSuspended()) return

        val primary = resolveBootstrapPrimary(allModules)
        observeGroupTransitionPrimaryResolve(channelId, primary?.value)
        val missingPeers = dialable
            .filter { it != localModuleId && it !in session.memberModules }
            .filter { isGroupMemberReconnectEligible(session, it.value) }
            .mapNotNull { endpointForDialableModule(it) }
        val pairwiseReconnect = if (primary != null && localModuleId == primary) {
            dialable
                .filter { it != localModuleId && localModuleId.value < it.value }
                .filter { isGroupMemberReconnectEligible(session, it.value) }
                .mapNotNull { endpointForDialableModule(it) }
        } else {
            emptyList()
        }
        val toInvite = (missingPeers + pairwiseReconnect).distinctBy { it.moduleId.value }
        if (toInvite.isNotEmpty()) {
            sendGroupMeshInvitesInternal(session.id, toInvite)
        }
        completeGroupMesh(session)
        maintainBackupStandby(session)
        touchConvergenceAnchor(session)
        emitGroupTopologySnapshot(TopologySnapshotReason.PLANNER_SCHEDULED, session)
        onGroupConvergenceBoundary(session)
    }

    private fun anchorHealthMap(): Map<String, AnchorHealthSnapshot> {
        val map = HashMap<String, AnchorHealthSnapshot>()
        map[localModuleId.value] = AnchorHealthSnapshot.fromLocal(localDeviceHealth.snapshot())
        remoteHealthByModule.forEach { (moduleId, health) -> map[moduleId] = health }
        return map
    }

    private fun electAnchorRoles(members: Collection<ModuleId>): AnchorRanking.Roles? =
        AnchorRanking.elect(members, anchorHealthMap())

    /**
     * Who may create the initial GROUP mesh — must match [ChannelMeshHostElection] / app warmup host.
     * Anchor health ranking applies after the mesh exists, not for bootstrap primary.
     */
    private fun resolveBootstrapPrimary(members: Collection<ModuleId>): ModuleId? {
        if (members.isEmpty()) return null
        return ChannelMeshHostElection.electHost(
            localModuleId,
            members.filter { it != localModuleId }.map { it.value },
            emptyMap()
        )
    }

    /**
     * ADR-0014 R-L4: Host-owned conference exists until explicit host hangup/leave.
     * Solo host (zero connected remotes) is valid — not a stale session.
     */
    private fun conferenceSessionExists(session: TalkbackSession): Boolean =
        session.type == SessionType.CONFERENCE &&
            session.accepted &&
            isConferenceHostSession(session)

    /**
     * ADR-0014 R-L4: Whether conference media mesh has live or negotiating remotes.
     * Distinct from [conferenceSessionExists] — solo host is Exists=true, Operational=false.
     */
    private fun conferenceSessionOperational(session: TalkbackSession): Boolean {
        if (!session.accepted) return false
        if (countConnectedRemotes(session) > 0) return true
        if (isConferenceSession(session) &&
            conferenceParticipantManager.participants(session.id).any { ps ->
                ps.invite == InviteState.INVITING ||
                    ps.invite == InviteState.RINGING ||
                    (ps.invite == InviteState.ACCEPTED &&
                        (ps.media == MediaState.CONNECTING || ps.media == MediaState.RECONNECTING))
            }
        ) {
            return true
        }
        if (!isConferenceSession(session) &&
            session.participants.values.any { ps ->
                ps.invite == InviteState.INVITING ||
                    ps.invite == InviteState.RINGING ||
                    (ps.invite == InviteState.ACCEPTED &&
                        (ps.media == MediaState.CONNECTING || ps.media == MediaState.RECONNECTING))
            }
        ) {
            return true
        }
        if (!isConferenceHostSession(session)) {
            val hostId = session.initiatorModuleId?.value ?: return false
            return when (qosMonitor.snapshot(hostId)?.iceState) {
                null, "CLOSED", "FAILED" -> false
                else -> true
            }
        }
        return false
    }

    /**
     * After meeting tests, a lingering CONFERENCE session blocks GROUP on the same channel.
     * Reclaim only non-host zombie sessions; host-owned conferences are never reclaimed (ADR-0014 R-L5).
     */
    private fun endStaleConferenceBlockingGroup(channelId: String) {
        if (meetingPreferred || uiMeetingPreferredChannels[channelId] == true) return
        if (pendingConferenceInvitesByChannel.containsKey(channelId)) return
        val stale = sessions.values.filter {
            it.channelId == channelId && it.type == SessionType.CONFERENCE
        }
        if (stale.isEmpty()) return
        if (stale.any(::conferenceSessionExists)) return
        if (stale.any(::conferenceSessionOperational)) return
        stale.forEach { session ->
            log("[${session.traceId}] Ending stale conference on $channelId for GROUP reclaim")
            hangupInternal(session.id)
        }
        releaseChannelModeIfIdle(channelId)
        clearConferenceRejoinState(channelId)
        cancelHostRejoinRetry(channelId)
    }

    private fun applyAnchorRolesToSession(session: TalkbackSession, roles: AnchorRanking.Roles) {
        session.anchorModuleId = roles.primary
        session.backupAnchorModuleId = roles.backup
        if (session.anchorEpoch < AnchorRanking.INITIAL_ANCHOR_EPOCH) {
            session.anchorEpoch = AnchorRanking.INITIAL_ANCHOR_EPOCH
        }
        if (session.anchorTenureStartMs <= 0L) {
            session.anchorTenureStartMs = System.currentTimeMillis()
        }
    }

    private fun applyRemoteAnchorView(
        session: TalkbackSession,
        remoteEpoch: Long,
        remotePrimary: ModuleId?,
        remoteBackup: ModuleId?
    ) {
        if (remoteEpoch <= 0L) return
        val merged = AnchorAuthority.mergeCanonical(
            localEpoch = session.anchorEpoch,
            localPrimary = session.anchorModuleId,
            localBackup = session.backupAnchorModuleId,
            remoteEpoch = remoteEpoch,
            remotePrimary = remotePrimary,
            remoteBackup = remoteBackup
        )
        session.anchorEpoch = merged.epoch
        session.anchorModuleId = merged.primary
        session.backupAnchorModuleId = merged.backup
    }

    private fun resolveSplitBrainFromHello(payload: HelloPayload, nowMs: Long) {
        val channelId = payload.channelId ?: return
        if (payload.anchorEpoch <= 0L || payload.primaryModuleId.isNullOrBlank()) return
        val session = sessions.values.firstOrNull {
            (it.type == SessionType.GROUP || it.type == SessionType.CONFERENCE) &&
                it.channelId == channelId &&
                it.accepted
        } ?: return
        val remotePrimary = ModuleId(payload.primaryModuleId)
        val localPrimary = session.anchorModuleId
        if (localPrimary == null) {
            applyRemoteAnchorView(
                session,
                payload.anchorEpoch,
                remotePrimary,
                payload.backupModuleId?.let { ModuleId(it) }
            )
            return
        }
        val winnerId = when {
            payload.anchorEpoch > session.anchorEpoch -> payload.primaryModuleId
            payload.anchorEpoch < session.anchorEpoch -> localPrimary.value
            payload.primaryModuleId == localPrimary.value -> null
            else -> AnchorRanking.resolveSplitBrain(
                leftModuleId = localPrimary.value,
                leftEpoch = session.anchorEpoch,
                rightModuleId = payload.primaryModuleId,
                rightEpoch = payload.anchorEpoch,
                healthByModule = anchorHealthMap(),
                nowMs = nowMs
            )
        } ?: return
        if (payload.anchorEpoch > session.anchorEpoch) {
            applyRemoteAnchorView(
                session,
                payload.anchorEpoch,
                remotePrimary,
                payload.backupModuleId?.let { ModuleId(it) }
            )
            if (session.type == SessionType.GROUP && localModuleId != remotePrimary) {
                offerGroupMeshJoin(session, remotePrimary)
            }
        }
        if (localModuleId == localPrimary && winnerId != localModuleId.value) {
            demoteToMemberAndReconnect(session, ModuleId(winnerId))
        }
    }

    private fun demoteToMemberAndReconnect(session: TalkbackSession, winnerPrimary: ModuleId) {
        log("${sessionTag(session)} Split-brain recovery: demoting local primary, reconnect to ${winnerPrimary.value}")
        if (session.anchorModuleId == localModuleId) {
            programAudioBus.clear(session.id)
            conferenceAudioBus.clear(session.id)
            receivePathLivenessObserver.clearSession(session.id)
        }
        session.anchorModuleId = winnerPrimary
        session.backupAnchorModuleId = null
        if (localModuleId != winnerPrimary) {
            offerGroupMeshJoin(session, winnerPrimary)
        }
        maintainBackupStandby(session)
        updateSessionReceivePlayback(session, "split_brain_recovery")
        syncConferenceRelay(session, "split_brain_recovery")
    }

    private fun maintainBackupStandby(session: TalkbackSession) {
        if (session.mediaTopology != GroupMediaTopology.ANCHOR) return
        val backup = session.backupAnchorModuleId ?: return
        if (localModuleId == session.anchorModuleId || localModuleId == backup) return
        if (!session.accepted || session.isForegroundSuspended()) return
        val backupId = backup.value
        if (backupId in session.backupStandbyPeers && qosMonitor.isGroupConnected(backupId)) return
        offerBackupStandbyJoin(session, backup)
    }

    private fun offerBackupStandbyJoin(session: TalkbackSession, backup: ModuleId) {
        if (session.type != SessionType.CONFERENCE) return
        val peerId = backup.value
        val ice = qosMonitor.snapshot(peerId)?.iceState
        if (IceConnectivity.isConnected(ice)) {
            session.backupStandbyPeers.add(peerId)
            return
        }
        val peer = resolvePeerForModule(peerId) ?: return
        val payloadBase = groupPayloadBase(session)
        val remote = endpointForModule(session, backup)
        val engine = getOrCreateMeshEngine(session, peerId)
        wireIceCallback(session, peerId, engine)
        engine.setRemotePlaybackEnabled(false)
        val offer = engine.createOffer()
        drainPendingIce(session.id, peerId, engine)
        sendSignal(
            peer,
            buildSignedEnvelope(
                SignalType.GROUP_JOIN,
                session.local,
                remote,
                session.id,
                payloadBase.copy(sdp = offer).encode()
            )
        )
        session.backupStandbyPeers.add(peerId)
        log("${sessionTag(session)} Half-hot standby join offered to backup $peerId")
    }

    private fun suspendSessionForUnicast(session: TalkbackSession) {
        if (session.isForegroundSuspended()) return
        if (!SessionDispositionTransitions.suspend(session)) return
        stopSessionCapture(session)
        setPlaybackEnabled(session, enabled = false, reason = "suspend_unicast")
        log("${sessionTag(session)} Suspending ${session.type.name} session for unicast disposition=${session.disposition}")
    }

    private fun resumeGroupSessionsAfterUnicast(endedUnicastSessionId: String? = null) {
        val endedId = endedUnicastSessionId ?: activityStack.topSessionId()
        if (endedUnicastSessionId != null) {
            foregroundAdmission.onUnicastEnded(endedUnicastSessionId)
        } else {
            activityStack.pop()
        }
        var resumedGroup = false
        sessions.values
            .filter {
                (it.type == SessionType.GROUP || it.type == SessionType.CONFERENCE) &&
                    it.disposition == SessionDisposition.SUSPENDED
            }
            .forEach { session ->
                if (!activityStack.consumeResume(session.id, endedId)) {
                    log("${sessionTag(session)} Skip resume: preemption token mismatch ended=$endedId")
                    return@forEach
                }
                if (!SessionDispositionTransitions.beginResume(session)) return@forEach
                val playbackAfterResume = when {
                    session.type == SessionType.GROUP -> shouldEnableGroupReceivePlayback(session)
                    session.type == SessionType.CONFERENCE ->
                        ConferenceReceivePlaybackPolicy.shouldEnableReceivePlayback(
                            ConferenceReceivePlaybackPolicy.Input(
                                accepted = session.accepted,
                                foregroundSuspended = session.isForegroundSuspended()
                            )
                        )
                    else -> false
                }
                log("${sessionTag(session)} Resuming ${session.type.name} session after unicast disposition=${session.disposition}")
                log(
                    "${sessionTag(session)} resumeGroupSessionsAfterUnicast() " +
                        "playback=$playbackAfterResume"
                )
                updateSessionReceivePlayback(session, "resume_after_unicast")
                if (session.type == SessionType.GROUP) {
                    resumedGroup = true
                    session.channelId?.let { reconcileGroupMeshInternal(it) }
                    completeGroupMesh(session)
                    tryFlushPendingTransmit(session)
                } else {
                    completeGroupMesh(session)
                    maintainBackupStandby(session)
                    tryEnsureConferenceDuplex(session)
                    syncConferenceRelay(session, "foreground_resume")
                }
                SessionDispositionTransitions.markActive(session)
            }
        if (resumedGroup) {
            scheduler.schedule({
                runOnCoordinator {
                    log("Post-unicast mesh retry")
                    retryIncompleteGroupMesh()
                }
            }, RESUME_MESH_RETRY_DELAY_MS, TimeUnit.MILLISECONDS)
        }
    }

    private fun localEndpointAddress(): EndpointAddress? {
        val online = endpointRegistry.allOnline().firstOrNull() ?: return null
        return online.address
    }

    private fun dialableRemoteModuleIds(): Set<ModuleId> =
        mergeRemoteModuleViews()
            .map { it.presence.moduleId }
            .filter { isModuleDialable(it.value) }
            .toSet()

    private fun endpointForDialableModule(moduleId: ModuleId): EndpointAddress? {
        if (!isModuleDialable(moduleId.value)) return null
        val state = mergeRemoteModuleViews().firstOrNull { it.presence.moduleId == moduleId }
        val endpointId = resolvePrimaryEndpointId(moduleId.value, state) ?: "E01"
        return EndpointAddress(moduleId, EndpointId(endpointId))
    }

  /** min(initiatorModuleId), tie-break min(sessionId). */
    private fun compareGroupSessionAuthority(
        initiatorA: String,
        sessionIdA: String,
        initiatorB: String,
        sessionIdB: String
    ): Int {
        val cmp = initiatorA.compareTo(initiatorB)
        if (cmp != 0) return cmp
        return sessionIdA.compareTo(sessionIdB)
    }

    /** Proactively pull a former participant back into our host conference after they come online. */
    private fun tryHostReinviteConferencePeer(moduleId: String): Boolean {
        if (moduleId == localModuleId.value) return false
        val hostConference = sessions.values.firstOrNull {
            it.type == SessionType.CONFERENCE &&
                it.accepted &&
                it.initiatorModuleId == localModuleId
        } ?: return false
        // R29-F (#82): eligibility from membership lifecycle only — never media/QoS.
        if (!isConferenceRejoinEligible(hostConference, moduleId)) return false
        val remoteState = mergeRemoteModuleViews().firstOrNull { it.presence.moduleId.value == moduleId }
        if (!isModuleReachable(moduleId, remoteState)) return false
        val endpointId = resolvePrimaryEndpointId(moduleId, remoteState) ?: return false
        val remote = EndpointAddress(ModuleId(moduleId), EndpointId(endpointId))
        discoveredByModule[moduleId]?.let { rememberSignalPeer(moduleId, it) }
        val sent = sendConferenceInvitesInternal(hostConference.id, listOf(remote), rejoin = true)
        if (sent > 0) {
            log("[${hostConference.traceId}] Host re-invited $moduleId on recovery sent=$sent")
        }
        return sent > 0
    }

    /**
     * R29-F (#82): who may be rejoin-invited.
     * Consumes membership lifecycle only — MUST NOT read ICE / mesh / QoS / recovery facts.
     * JOINED ∧ obligationOpen ⇒ NOT rejoin eligible.
     */
    private fun isConferenceRejoinEligible(session: TalkbackSession, moduleId: String): Boolean {
        val lifecycle = resolveConferenceMembershipLifecycle(session, moduleId) ?: return false
        return ConferenceRejoinEligibility.isEligible(lifecycle)
    }

    /**
     * Resolve membership lifecycle from roster / left set / invite commitment only.
     * [ConferenceMembershipLifecycle.REJOIN_REQUIRED] is reserved and not produced here (#82).
     * [ConferenceMembershipLifecycle.PRUNED] shares the left-member set with LEFT today;
     * both are rejoin-eligible and need not be distinguished for this gate.
     */
    private fun resolveConferenceMembershipLifecycle(
        session: TalkbackSession,
        moduleId: String
    ): ConferenceMembershipLifecycle? {
        if (!isConferenceSession(session)) return null
        val leftKeys = conferenceParticipantManager.leftMemberEndpoints(session.id)?.keys
            ?: session.leftMemberEndpoints.keys
        if (moduleId in leftKeys) {
            return ConferenceMembershipLifecycle.LEFT
        }
        if (moduleId !in conferenceMemberRemoteIds(session)) return null
        return when (conferenceParticipantManager.participant(session.id, moduleId).invite) {
            InviteState.ACCEPTED -> ConferenceMembershipLifecycle.JOINED
            InviteState.INVITING,
            InviteState.RINGING,
            InviteState.NONE,
            InviteState.DECLINED,
            InviteState.EXPIRED -> ConferenceMembershipLifecycle.INVITED
        }
    }

    private fun maybeTriggerAnchorHandover(session: TalkbackSession) {
        if (session.mediaTopology != GroupMediaTopology.ANCHOR) return
        if (session.anchorModuleId != localModuleId) return
        val backup = session.backupAnchorModuleId ?: return
        if (backup == localModuleId) return
        val health = localDeviceHealth.snapshot()
        val lowBattery = health.batteryPercent < config.anchorLowBatteryPercent && !health.charging
        if (!lowBattery) return
        log("${sessionTag(session)} Low battery (${health.batteryPercent}%), graceful handover to ${backup.value}")
        handleAnchorFailover(session, localModuleId.value)
    }

    private fun cleanupUnhealthySessions() {
        val now = System.currentTimeMillis()
        sessions.values.toList().forEach { session ->
            when (session.type) {
                SessionType.UNICAST -> {
                    val timedOut = !session.accepted &&
                        (session.unicastPhase == UnicastCallPhase.CONNECTING ||
                            session.unicastPhase == UnicastCallPhase.RINGING) &&
                        now - session.lastActiveMs > config.unicastRingTimeoutMs
                    if (timedOut) {
                        log("${sessionTag(session)} Unicast ring/connect timeout, hanging up")
                        hangupInternal(session.id)
                        return@forEach
                    }
                }
                else -> Unit
            }
            if (!session.accepted || session.isForegroundSuspended()) return@forEach
            when (session.type) {
                SessionType.CONFERENCE -> {
                    recoverStuckCheckingConferencePeers(session)
                    cleanupUnhealthyConferenceSession(session)
                }
                SessionType.GROUP -> {
                    recoverStuckCheckingGroupPeers(session)
                    maybeTriggerAnchorHandover(session)
                    reconcileGroupMembership(session, "cleanup_tick")
                    if (isMeshSessionStuck(session)) {
                        log("${sessionTag(session)} Cleaning unhealthy group session")
                        scheduleReDialAfterMediaLoss(
                            session,
                            session.remote?.moduleId?.value
                                ?: session.remotePeersByModule.keys.firstOrNull()
                                ?: return@forEach
                        )
                        hangupInternal(session.id)
                    }
                }
                SessionType.UNICAST -> if (!sessionMediaHealthy(session)) {
                    log("${sessionTag(session)} Cleaning unhealthy unicast session")
                    scheduleReDialAfterMediaLoss(
                        session,
                        session.remote?.moduleId?.value ?: return@forEach
                    )
                    hangupInternal(session.id)
                }
            }
        }
    }

    private fun cleanupUnhealthyConferenceSession(session: TalkbackSession) {
        if (isConferenceHostSession(session)) {
            session.channelId?.let { resendConferenceInvitesToUnconnected(it) }
        }
        val now = System.currentTimeMillis()
        val hostId = session.initiatorModuleId?.value
        if (!isConferenceHostSession(session)) {
            conferenceMemberRemoteIds(session).forEach { moduleId ->
                if (hostId != null && hostId != localModuleId.value && moduleId == hostId) return@forEach
                if (!isConferencePeerMediaUnhealthyForAdvisory(session, moduleId, now)) return@forEach
                recordParticipantMediaDegradation(session, moduleId)
            }
            return
        }
        val deadRemotes = conferenceMemberRemoteIds(session).filter { moduleId ->
            canAuthorityPrune(session, moduleId)
        }
        if (deadRemotes.isEmpty()) return
        deadRemotes.forEach { moduleId ->
            log("[${session.traceId}] Pruning unhealthy conference peer $moduleId")
            removeConferenceParticipant(
                session,
                moduleId,
                AuthorityMembershipMutationSource.AUTHORITY_PRUNE
            )
        }
        repairConferenceMeshAfterLeave(session)
    }

    /**
     * Host membership prune eligibility (ADR-0024 R29-E / #75+#77).
     *
     * Consumes recovery obligation facts read-only. MUST NOT authorize prune from
     * `!isEdgeRecovering`, ICE grace, HELLO silence, or `route=false`.
     * MUST NOT recompute obligationDeadlineAt — controller is the single writer.
     */
    private fun canAuthorityPrune(
        session: TalkbackSession,
        moduleId: String
    ): Boolean {
        if (ModuleId(moduleId) in reachabilityView.snapshot(session.id).evicted) return false
        val participant = meshParticipant(session, moduleId)
        if (participant.invite != InviteState.ACCEPTED) return false
        if (!conferenceParticipantWasEverConnected(session, moduleId)) return false
        // Negative health guards only — never positive prune authority.
        if (qosMonitor.isGroupConnected(moduleId)) return false
        if (participant.media == MediaState.CONNECTED) return false

        if (!conferenceEdgeRecoveryController.edgeObligationClosed(session.id, moduleId)) {
            return false
        }
        val closeReason =
            conferenceEdgeRecoveryController.obligationCloseReason(session.id, moduleId)
                ?: return false
        if (!closeReason.isPruneEligible()) return false
        if (conferenceEdgeRecoveryController.hasPendingCompletionDecision(session.id, moduleId)) {
            return false
        }
        return true
    }

    /**
     * Participant advisory candidate (ADR-0023 R29-C). Media-health only; must not
     * share host [canAuthorityPrune] obligation gate so RECOVERY_MEDIA_DEGRADED stays live
     * while obligation remains OPEN after FAILED_MEDIA_RECOVERY.
     */
    private fun isConferencePeerMediaUnhealthyForAdvisory(
        session: TalkbackSession,
        moduleId: String,
        now: Long
    ): Boolean {
        if (conferenceEdgeRecoveryController.isEdgeRecovering(session.id, moduleId)) return false
        if (ModuleId(moduleId) in reachabilityView.snapshot(session.id).evicted) return false
        val participant = meshParticipant(session, moduleId)
        if (participant.invite != InviteState.ACCEPTED) return false
        if (!conferenceParticipantWasEverConnected(session, moduleId)) return false
        if (qosMonitor.isGroupConnected(moduleId)) return false
        if (participant.media == MediaState.CONNECTED) return false
        val snap = qosMonitor.snapshot(moduleId)
        val ice = snap?.iceState
        val unhealthySince = snap?.updatedMs ?: participant.lastMediaChangeMs
        return when (ice) {
            "DISCONNECTED", "FAILED", "CLOSED" ->
                now - unhealthySince > config.meshNegotiationGraceMs
            else ->
                meshEngineForSession(session, moduleId) == null &&
                    now - unhealthySince.coerceAtLeast(session.lastActiveMs) > config.iceNegotiationTimeoutMs
        }
    }

    private fun conferenceParticipantWasEverConnected(session: TalkbackSession, moduleId: String): Boolean =
        if (isConferenceSession(session)) {
            conferenceParticipantManager.wasEverConnected(session.id, moduleId)
        } else {
            session.meshCompletedModules.contains(moduleId)
        }

    private fun touchSession(sessionId: String) {
        sessions[sessionId]?.touch()
    }

    private fun isBusy(): Boolean = foregroundAdmission.isForegroundBusy()

    private fun countsTowardActiveLimit(session: TalkbackSession): Boolean = session.isForegroundActive()

    private fun activeSessionCount(): Int = sessions.values.count { countsTowardActiveLimit(it) }

    private fun isBusyRejectPayload(payload: String): Boolean =
        payload == "BUSY" || parseBusyCanonical(payload) != null

    private fun parseBusyCanonical(payload: String): GroupSessionPayload? {
        if (payload == "BUSY" || payload == "DECLINED" || payload == "EXPIRED" || payload == "MEETING_ENDED") {
            return null
        }
        return GroupSessionPayload.decode(payload)?.takeIf {
            it.channelId.isNotBlank() && it.initiatorModuleId.isNotBlank()
        }
    }

    private fun canonicalGroupSessionOnChannel(channelId: String): TalkbackSession? =
        sessions[GroupRoomId.forChannel(channelId)]
            ?: sessions.values.firstOrNull {
                it.type == SessionType.GROUP &&
                    it.channelId == channelId &&
                    it.accepted &&
                    !it.isForegroundSuspended()
            }

    private fun tryYieldToCanonicalGroupFromBusy(
        invitedSessionId: String,
        canonical: GroupSessionPayload
    ): Boolean {
        val channelId = canonical.channelId
        val canonicalId = GroupRoomId.forChannel(channelId)
        sessions.values
            .filter {
                it.type == SessionType.GROUP &&
                    it.channelId == channelId &&
                    it.id != canonicalId
            }
            .forEach {
                log("${sessionTag(it)} Yielding duplicate group to canonical $canonicalId after BUSY")
                hangupInternal(it.id)
            }
        val localCanonical = sessions[canonicalId]
        if (localCanonical != null) {
            applyCanonicalMetadata(localCanonical, canonical)
            suppressMeshRepairUntilMsByChannel[channelId] =
                System.currentTimeMillis() + BUSY_MESH_REPAIR_SUPPRESS_MS
            log(
                "${sessionTag(localCanonical)} Absorbing BUSY redirect " +
                    "anchor=${canonical.anchorModuleId} epoch=${canonical.rosterEpochMs}"
            )
            return true
        }
        if (localModuleId.value != canonical.initiatorModuleId) {
            reconcileGroupMeshInternal(channelId)
        }
        return true
    }

    private fun applyCanonicalMetadata(session: TalkbackSession, canonical: GroupSessionPayload) {
        if (canonical.rosterEpochMs > 0L && canonical.rosterEpochMs >= session.rosterEpochMs) {
            session.rosterEpochMs = canonical.rosterEpochMs
        }
        if (canonical.anchorEpoch > 0L) {
            applyRemoteAnchorView(
                session,
                canonical.anchorEpoch,
                canonical.anchorModuleId?.let { ModuleId(it) },
                canonical.backupAnchorModuleId?.let { ModuleId(it) }
            )
        } else {
            canonical.anchorModuleId?.takeIf { it.isNotBlank() }?.let {
                session.anchorModuleId = ModuleId(it)
            }
            canonical.backupAnchorModuleId?.takeIf { it.isNotBlank() }?.let {
                session.backupAnchorModuleId = ModuleId(it)
            }
        }
        if (canonical.initiatorModuleId.isNotBlank()) {
            session.initiatorModuleId = ModuleId(canonical.initiatorModuleId)
        }
        if (canonical.floorAuthorityModuleId.isNotBlank()) {
            session.floorAuthorityModuleId = ModuleId(canonical.floorAuthorityModuleId)
        }
        canonical.mediaTopology?.let { session.mediaTopology = GroupMediaTopology.fromPayload(it) }
    }

    private fun sendGroupBusyReject(
        fromPeer: PeerTarget,
        callee: EndpointAddress,
        caller: EndpointAddress,
        invitedSessionId: String,
        holdingSession: TalkbackSession?
    ) {
        val payload = holdingSession?.let { groupPayloadBase(it).copy(sdp = "").encode() } ?: "BUSY"
        sendSignal(
            fromPeer,
            buildSignedEnvelope(SignalType.CALL_REJECT, callee, caller, invitedSessionId, payload)
        )
        if (holdingSession?.type == SessionType.CONFERENCE && holdingSession.accepted) {
            val belief = ConferenceRecoveryOwnershipLog.observeParticipantMembership(
                session = holdingSession,
                participantId = localModuleId.value,
                rosterOwner = holdingSession.initiatorModuleId?.value ?: localModuleId.value
            ).observedMembershipState
            ConferenceRecoveryOwnershipLog.emitRejoinResponse(
                conferenceId = holdingSession.id,
                localModuleId = localModuleId.value,
                targetParticipantId = caller.moduleId.value,
                response = "BUSY",
                localMembershipBelief = belief,
                remoteMembershipHint = "ALIVE",
                observedRosterEpoch = holdingSession.rosterEpoch
            )
        }
    }

    private fun isPoliteNegotiator(remoteModuleId: String): Boolean =
        localModuleId.value > remoteModuleId

    /**
     * INVITE answerer (CALL_INVITE / GROUP_INVITE) must defer to the offerer.
     * Lex-order [isPoliteNegotiator] is only for pairwise GROUP_JOIN mesh links.
     */
    private fun politeForInviteAnswer(): Boolean = true

    private fun politeForMeshPair(remoteModuleId: String): Boolean =
        isPoliteNegotiator(remoteModuleId)

    private fun pruneStalePeerEntries() {
        val now = System.currentTimeMillis()
        moduleLastHelloMs.entries.removeIf { (_, at) -> now - at > config.moduleStaleMs }
        signalPeersByModule.keys.toList().forEach { moduleId ->
            val lastHello = moduleLastHelloMs[moduleId] ?: 0L
            if (lastHello <= 0L || now - lastHello > config.moduleStaleMs) {
                signalPeersByModule.remove(moduleId)
            }
        }
    }

    private fun retryPendingFloorRequests() {
        val now = System.currentTimeMillis()
        sessions.values.forEach { session ->
            if (session.type != SessionType.GROUP) return@forEach
            if (session.ptt.state != PttState.REQUEST_FLOOR) return@forEach
            if (now - session.lastFloorRequestMs < config.floorRetryMs) return@forEach
            session.lastFloorRequestMs = now
            val traceId = FloorTrace.nextId()
            val payload = FloorPayload.forRequest(
                groupLocalIdentity(session),
                session.floor.version(),
                session.floor.epoch(),
                EndpointPriority.NORMAL,
                traceId = traceId
            ).encode()
            dispatchFloorRequest(session, payload, traceId)
        }
    }

    private fun cleanupExpiredSessions() {
        stateSync.pruneStaleModules(config.moduleStaleMs)
        pruneStalePeerEntries()
        cleanupUnhealthySessions()
        cleanupExpiredPendingConferenceInvites()
        cleanupHostConferenceInviteTimeouts()
        retryPendingFloorRequests()
        val now = System.currentTimeMillis()
        sessions.values
            .filter { now - it.lastActiveMs > config.sessionIdleTimeoutMs }
            .map { it.id }
            .toList()
            .forEach { sessionId ->
                val session = sessions[sessionId] ?: return@forEach
                if (config.autoReDialOnModuleRecovery && session.accepted && session.remote != null) {
                    reDialByRemoteModule[session.remote!!.moduleId.value] = ReDialRecord(
                        session.local,
                        session.remote!!,
                        session.channelId,
                        session.groupMembers.takeIf { members -> members.isNotEmpty() },
                        session.type,
                        now
                    )
                }
                log("[${session.traceId}] Session timeout")
                hangupInternal(sessionId)
            }
    }

    private fun sendSessionHeartbeats() {
        sessions.values.filter { it.accepted }.forEach { session ->
            if (session.type.isMeshSession()) {
                session.touch()
            }
            targetsForSession(session).forEach { (peer, remote) ->
                sendSignal(
                    peer,
                    buildSignedEnvelope(SignalType.HEARTBEAT, session.local, remote, session.id, "")
                )
            }
        }
    }

    private fun tryReDialForModule(moduleId: String) {
        val record = reDialByRemoteModule[moduleId] ?: return
        if (sessions.values.any {
                it.type == SessionType.CONFERENCE &&
                    it.accepted &&
                    it.initiatorModuleId == localModuleId
            }
        ) {
            return
        }
        val now = System.currentTimeMillis()
        if (now - record.lastAttemptMs < 2_000L) return
        runCatching {
            if (record.channelId != null) {
                val conferenceOnChannel = sessions.values.any {
                    it.channelId == record.channelId && it.type == SessionType.CONFERENCE && it.accepted
                }
                if (conferenceOnChannel && record.meshSessionType != SessionType.CONFERENCE) {
                    return@runCatching
                }
                val members = record.groupMembers
                    ?: listOf(record.local, record.remote)
                val remotes = members.filter { it.moduleId != record.local.moduleId }
                when (record.meshSessionType) {
                    SessionType.CONFERENCE -> meshCallInternal(
                        record.local,
                        remotes,
                        record.channelId!!,
                        SessionType.CONFERENCE,
                        MeshSessionMode.CONFERENCE,
                        config.maxConferenceModules
                    )
                    else -> meshCallInternal(
                        record.local,
                        remotes,
                        record.channelId!!,
                        SessionType.GROUP,
                        MeshSessionMode.GROUP,
                        config.maxGroupModules
                    )
                }
                log("Auto redial ${record.meshSessionType.name} module=$moduleId ch=${record.channelId}")
            } else {
                placeCallInternal(record.local, record.remote, null, SessionType.UNICAST)
                log("Auto redial unicast module=$moduleId")
            }
            reDialByRemoteModule[moduleId] = record.copy(lastAttemptMs = now)
        }.onFailure {
            reDialByRemoteModule[moduleId] = record.copy(lastAttemptMs = now)
            log("Auto redial failed module=$moduleId err=${it.message}")
        }
    }

    private fun sessionTypeFromMode(mode: MeshSessionMode): SessionType = when (mode) {
        MeshSessionMode.CONFERENCE -> SessionType.CONFERENCE
        MeshSessionMode.GROUP -> SessionType.GROUP
    }

    private fun meshSessionModeFor(session: TalkbackSession): MeshSessionMode = when (session.type) {
        SessionType.CONFERENCE -> MeshSessionMode.CONFERENCE
        else -> MeshSessionMode.GROUP
    }

    private fun buildMemberViews(session: TalkbackSession): List<MemberView> {
        if (isConferenceSession(session)) {
            syncSessionParticipantMedia(session)
            return conferenceSnapshot(session).memberViews
        }
        syncSessionParticipantMedia(session)
        return session.groupMembers.mapNotNull { member ->
            val id = member.moduleId.value
            if (id == localModuleId.value) return@mapNotNull null
            val ps = session.participants[id]
            MemberView(
                key = member.key,
                moduleId = id,
                invite = ps?.invite ?: InviteState.NONE,
                media = ps?.media ?: MediaState.NONE
            )
        }
    }

    private fun syncSessionParticipantMedia(session: TalkbackSession) {
        val remoteIds = when (session.type) {
            SessionType.CONFERENCE -> conferenceMemberRemoteIds(session)
            else -> remoteModuleIds(session)
        }
        remoteIds.forEach { moduleId ->
            val ps = meshParticipant(session,moduleId)
            val ice = qosMonitor.snapshot(moduleId)?.iceState
            ps.media = mediaStateFromIce(ice, ps.media)
            if (IceConnectivity.isConnected(ice)) {
                if (ps.invite == InviteState.INVITING || ps.invite == InviteState.RINGING) {
                    ps.invite = InviteState.ACCEPTED
                }
            }
        }
    }

    private fun mediaStateFromIce(ice: String?, fallback: MediaState): MediaState = when {
        IceConnectivity.isConnected(ice) -> MediaState.CONNECTED
        IceConnectivity.isNegotiating(ice) -> MediaState.CONNECTING
        ice == "DISCONNECTED" -> MediaState.RECONNECTING
        ice == "FAILED" -> MediaState.FAILED
        else -> fallback
    }

    private fun cleanupExpiredPendingConferenceInvites() {
        val now = System.currentTimeMillis()
        pendingConferenceInvitesByChannel.entries.toList().forEach { (channelId, pending) ->
            if (now - pending.receivedAtMs <= config.conferenceInvitePendingTtlMs) return@forEach
            pendingConferenceInvitesByChannel.remove(channelId)
            val signal = pending.signal
            val callee = signal.to
            if (callee != null) {
                sendSignal(
                    pending.fromPeer,
                    buildSignedEnvelope(
                        SignalType.CALL_REJECT,
                        callee,
                        signal.from,
                        signal.sessionId,
                        "EXPIRED"
                    )
                )
            }
            releaseConferenceRuntimeAfterRemoteTermination(channelId, "PENDING_CONFERENCE_ABORT_TIMEOUT")
            log("Conference invite expired ch=$channelId from=${signal.from.key}")
        }
    }

    private fun cleanupHostConferenceInviteTimeouts() {
        val now = System.currentTimeMillis()
        sessions.values
            .filter {
                it.type == SessionType.CONFERENCE &&
                    it.accepted &&
                    it.initiatorModuleId == localModuleId
            }
            .forEach { session ->
                conferenceParticipantManager.participants(session.id).forEach { ps ->
                    if (ps.invite != InviteState.INVITING && ps.invite != InviteState.RINGING) return@forEach
                    if (now - ps.invitedAtMs <= config.conferenceInviteRingTimeoutMs) return@forEach
                    val remoteEndpoint = meshRoster(session).firstOrNull {
                        it.moduleId.value == ps.moduleId.value
                    }
                    if (remoteEndpoint != null && !qosMonitor.isGroupConnected(ps.moduleId.value)) {
                        val resent = sendConferenceInvitesInternal(session.id, listOf(remoteEndpoint))
                        if (resent > 0) {
                            log(
                                "[${session.traceId}] Conference invite re-sent to ${ps.moduleId.value} " +
                                    "after ring timeout"
                            )
                            return@forEach
                        }
                    }
                    if (countConnectedRemotes(session) > 0 &&
                        !qosMonitor.isGroupConnected(ps.moduleId.value) &&
                        now - ps.invitedAtMs < config.conferenceInviteRingTimeoutMs * 3
                    ) {
                        ps.invitedAtMs = now
                        log(
                            "[${session.traceId}] Conference invite for ${ps.moduleId.value} kept pending " +
                                "while other peers are connected"
                        )
                        return@forEach
                    }
                    ps.invite = InviteState.EXPIRED
                    val moduleId = ps.moduleId.value
                    if (remoteEndpoint != null &&
                        qosMonitor.snapshot(moduleId)?.iceState != "CONNECTED"
                    ) {
                        evictMeshInvitee(session, moduleId, InviteState.EXPIRED, "EXPIRED")
                        reDialByRemoteModule[moduleId] = ReDialRecord(
                            session.local,
                            remoteEndpoint,
                            session.channelId,
                            meshRoster(session),
                            SessionType.CONFERENCE,
                            now
                        )
                    }
                    log(
                        "[${session.traceId}] Conference invite expired for $moduleId " +
                            "(ring timeout)"
                    )
                }
            }
    }

    companion object {
        private val MESH_RETRY_DELAYS_MS = longArrayOf(500L, 1500L, 3000L)
        private const val RESUME_MESH_RETRY_DELAY_MS = 1_500L
        private const val GROUP_MESH_RECONNECT_THROTTLE_MS = 2_000L
        private const val BUSY_MESH_REPAIR_SUPPRESS_MS = 5_000L
        private val HOST_REJOIN_BACKOFF_MS = longArrayOf(2_000L, 5_000L, 10_000L)
        private val CONFERENCE_HOST_LINK_KICK_DELAYS_MS = longArrayOf(500L, 2_000L, 5_000L, 10_000L)
        private const val GROUP_PTT_RECOVERY_DELAY_MS = 300L
        private const val CONFERENCE_INVITE_MIN_INTERVAL_MS = 5_000L
        private const val ENSURE_CANONICAL_INVITE_COOLDOWN_MS = 5_000L
    }

    private fun sessionTag(session: TalkbackSession): String =
        "[${session.traceId}|${session.id}]"

    private fun isMeshRepairSuppressed(channelId: String): Boolean =
        System.currentTimeMillis() < (suppressMeshRepairUntilMsByChannel[channelId] ?: 0L)

    private fun notifyConferenceTransportChanged(
        session: TalkbackSession,
        reason: String = "transport_changed"
    ) {
        if (session.type != SessionType.CONFERENCE) return
        refreshConferenceReceivePlayback(session, reason)
    }

    private fun refreshConferenceReceivePlayback(session: TalkbackSession, reason: String) {
        if (session.type != SessionType.CONFERENCE || !session.accepted) return
        updateSessionReceivePlayback(session, reason)
    }

    private fun updateSessionReceivePlayback(session: TalkbackSession, reason: String = "refreshPlaybackState") {
        val foregroundSuspended = session.isForegroundSuspended()
        val enabled = when {
            foregroundSuspended -> false
            session.type == SessionType.UNICAST -> session.accepted
            session.type == SessionType.GROUP -> shouldEnableGroupReceivePlayback(session)
            session.type == SessionType.CONFERENCE ->
                ConferenceReceivePlaybackPolicy.shouldEnableReceivePlayback(
                    ConferenceReceivePlaybackPolicy.Input(
                        accepted = session.accepted,
                        foregroundSuspended = foregroundSuspended
                    )
                )
            else -> false
        }
        if (session.type == SessionType.CONFERENCE) {
            logConferencePlaybackDecision(
                session = session,
                reason = reason,
                accepted = session.accepted,
                muted = session.muted,
                foregroundSuspended = foregroundSuspended,
                enabled = enabled,
                caller = playbackMutationCaller()
            )
        }
        setPlaybackEnabled(session, enabled, reason)
        verifyGroupPlaybackInvariant(session, enabled)
    }

    private fun conferencePlaybackLifecycleLabel(session: TalkbackSession): String =
        when {
            session.disposition == SessionDisposition.TERMINATED -> "TERMINATED"
            !session.accepted -> "JOINING"
            isConferenceUiReady(session) -> "ESTABLISHED"
            else -> "CONNECTING"
        }

    private fun playbackMutationCaller(): String {
        val skip = setOf(
            "updateSessionReceivePlayback",
            "updateSessionReceivePlayback\$default",
            "refreshConferenceReceivePlayback",
            "notifyConferenceTransportChanged",
            "setPlaybackEnabled",
            "logConferencePlaybackDecision",
            "playbackMutationCaller"
        )
        return Thread.currentThread().stackTrace
            .asSequence()
            .drop(2)
            .firstOrNull { frame ->
                frame.className.contains("TalkbackCoordinator") &&
                    frame.methodName !in skip &&
                    !frame.methodName.startsWith("access$")
            }
            ?.methodName
            ?: "unknown"
    }

    private fun callMuteMutationCaller(): String {
        val skipMethods = setOf(
            "setCallMuted",
            "logCallMuteChanged",
            "callMuteMutationCaller"
        )
        return Thread.currentThread().stackTrace
            .asSequence()
            .drop(2)
            .firstOrNull { frame ->
                frame.className.startsWith("com.talkback") &&
                    !frame.className.contains("TalkbackCoordinator") &&
                    frame.methodName !in skipMethods &&
                    !frame.methodName.startsWith("access$")
            }
            ?.let { "${it.className.substringAfterLast('.')}#${it.methodName}" }
            ?: "unknown"
    }

    private fun logCallMuteChanged(
        session: TalkbackSession,
        old: Boolean,
        new: Boolean,
        reason: String,
        caller: String
    ) {
        val stack = Thread.currentThread().stackTrace
            .drop(2)
            .take(10)
            .joinToString(" <- ") { "${it.fileName}:${it.lineNumber}#${it.methodName}" }
        log(
            "CALL_MUTE_CHANGED\n" +
                "session=${session.id}\n" +
                "type=${session.type}\n" +
                "old=$old\n" +
                "new=$new\n" +
                "reason=$reason\n" +
                "caller=$caller\n" +
                "stack=$stack"
        )
    }

    private fun logConferencePlaybackDecision(
        session: TalkbackSession,
        reason: String,
        accepted: Boolean,
        muted: Boolean,
        foregroundSuspended: Boolean,
        enabled: Boolean,
        caller: String
    ) {
        log(
            "PLAYBACK_DECISION\n" +
                "session=${session.id}\n" +
                "type=CONFERENCE\n" +
                "reason=$reason\n" +
                "accepted=$accepted\n" +
                "muted=$muted\n" +
                "foregroundSuspended=$foregroundSuspended\n" +
                "lifecycle=${conferencePlaybackLifecycleLabel(session)}\n" +
                "enabled=$enabled\n" +
                "caller=$caller"
        )
    }

    private fun refreshGroupReceivePlaybackAll() {
        sessions.values
            .filter { it.type == SessionType.GROUP && it.accepted }
            .forEach { updateSessionReceivePlayback(it, "periodic_self_heal") }
    }

    private fun verifyGroupPlaybackInvariant(session: TalkbackSession, expected: Boolean) {
        if (session.type != SessionType.GROUP || !session.accepted) return
        session.remotePeersByModule.keys.forEach { moduleId ->
            val ice = qosMonitor.snapshot(moduleId)?.iceState
            val engine = getMeshEngine(moduleId) ?: return@forEach
            val actual = engine.isRemotePlaybackEnabled()
            if (IceConnectivity.isConnected(ice) && !actual) {
                log(
                    "${sessionTag(session)} ICE_CONNECTED playback=false peer=$moduleId " +
                        "(see PLAYBACK log for last reason)"
                )
            }
            if (actual != expected) {
                log(
                    "${sessionTag(session)} playback invariant mismatch " +
                        "expected=$expected actual=$actual peer=$moduleId"
                )
            }
        }
    }

    /**
     * GROUP receive is gated: only open remote playback when someone holds floor and a live
     * media path exists (direct mesh or anchor relay). Prevents idle warmup偷听.
     */
    private fun shouldEnableGroupReceivePlayback(session: TalkbackSession): Boolean {
        if (!session.accepted || session.isForegroundSuspended()) return false
        val channelId = session.channelId ?: return false
        if (ChannelLifecyclePolicy.blocksGroupReceivePlayback(channelGateState(channelId))) {
            log("PLAYBACK_DIAG ${sessionTag(session)} enable=false reason=CHANNEL_GATED")
            return false
        }
        val owner = session.floor.owner()
        if (owner == null) {
            log("PLAYBACK_DIAG ${sessionTag(session)} enable=false reason=NO_FLOOR_OWNER")
            return false
        }
        if (isLocalIdentity(session, owner)) return false
        val reachable = isFloorHolderAudioReachable(session, owner.moduleId.value)
        if (!reachable) {
            log(
                "PLAYBACK_DIAG ${sessionTag(session)} enable=false reason=HOLDER_AUDIO_UNREACHABLE " +
                    "owner=${owner.key}"
            )
        }
        return reachable
    }

    private fun isFloorHolderAudioReachable(session: TalkbackSession, holderModuleId: String): Boolean {
        if (isPeerMediaConnected(holderModuleId)) return true
        if (session.mediaTopology != GroupMediaTopology.ANCHOR) return false
        val anchorId = session.anchorModuleId?.value ?: return false
        if (holderModuleId == anchorId) return isPeerMediaConnected(anchorId)
        return isPeerMediaConnected(anchorId)
    }

    private fun setPlaybackEnabled(session: TalkbackSession, enabled: Boolean, reason: String) {
        val previous = lastPlaybackEnabledBySession[session.id]
        if (previous != enabled) {
            val stack = Thread.currentThread().stackTrace
                .drop(2)
                .take(10)
                .joinToString(" <- ") { "${it.fileName}:${it.lineNumber}#${it.methodName}" }
            log(
                "PLAYBACK\nsession=${session.id}\nold=$previous\nnew=$enabled\nreason=$reason\nstack=$stack"
            )
        }
        sessionMediaEngines(session).forEach { engine ->
            engine.setRemotePlaybackEnabled(enabled)
        }
        lastPlaybackEnabledBySession[session.id] = enabled
    }

    private fun sendSignal(target: PeerTarget, envelope: SignalEnvelope) {
        runCatching {
            signalingChannel.send(target, envelope)
        }.onFailure {
            log("Signal send failed type=${envelope.type} err=${it.message}")
        }
    }

    private fun buildSignedEnvelope(
        type: SignalType,
        from: EndpointAddress,
        to: EndpointAddress?,
        sessionId: String,
        payload: String
    ): SignalEnvelope {
        val unsigned = SignalEnvelope(
            type = type,
            from = from,
            to = to,
            sessionId = sessionId,
            timestampMs = System.currentTimeMillis(),
            payload = payload,
            nonce = UUID.randomUUID().toString(),
            signature = ""
        )
        return unsigned.copy(signature = SignalSecurity.sign(unsigned, config.sharedSecret))
    }

    private fun verifyIncomingSignal(signal: SignalEnvelope): Boolean {
        if (config.allowedModuleIds.isNotEmpty() &&
            !config.allowedModuleIds.contains(signal.from.moduleId.value)
        ) {
            return false
        }
        if (!SignalSecurity.verify(signal, config.sharedSecret)) return false
        val now = System.currentTimeMillis()
        if (kotlin.math.abs(now - signal.timestampMs) > config.replayWindowMs) return false
        val moduleKey = signal.from.moduleId.value
        val moduleNonces = seenNoncesByModule.getOrPut(moduleKey) { ConcurrentHashMap() }
        if (moduleNonces.containsKey(signal.nonce)) return false
        moduleNonces[signal.nonce] = signal.timestampMs
        cleanupSeenNonces(now)
        return true
    }

    private fun cleanupSeenNonces(now: Long) {
        val threshold = now - config.replayWindowMs
        seenNoncesByModule.values.forEach { nonces ->
            nonces.filterValues { it < threshold }.keys.forEach { nonces.remove(it) }
        }
    }

    private fun recordAuthorityDigestFromHello(payload: HelloPayload) {
        val channelId = payload.channelId ?: return
        if (payload.rosterEpoch <= 0L) return
        val session = sessions.values.firstOrNull {
            it.type == SessionType.GROUP && it.accepted && it.channelId == channelId
        } ?: return
        val authorityId = session.anchorModuleId?.value
            ?: resolveBootstrapPrimary(dialableRemoteModuleIds().plus(localModuleId))?.value
            ?: return
        if (payload.moduleId != authorityId) return
        lastSeenAuthorityDigestByChannel[channelId] = TopologyDigest(
            rosterEpoch = payload.rosterEpoch,
            anchorEpoch = payload.anchorEpoch,
            meshGeneration = payload.meshGeneration,
            memberHash = payload.memberHash
        )
    }

    /** Records the latest authority HELLO floor snapshot for epoch-divergence diagnostics. */
    private fun recordAuthorityFloorSnapshotFromHello(payload: HelloPayload) {
        val channelId = payload.channelId ?: return
        val digest = payload.floorSnapshot ?: return
        val sessionsForChannel = sessions.values.filter {
            it.type == SessionType.GROUP && it.accepted && it.channelId == channelId
        }
        val authorityId = sessionsForChannel.firstOrNull()?.let { session ->
            session.floorAuthorityModuleId?.value ?: session.initiatorModuleId?.value
        } ?: return
        if (payload.moduleId != authorityId) return
        lastSeenAuthorityFloorSnapshotByChannel[channelId] = digest
        sessionsForChannel.forEach { session ->
            val localEpoch = session.floor.epoch()
            FloorTrace.helloFloorSnapshot(
                "HELLO_SNAPSHOT_RECORDED",
                session.id,
                channelId,
                payload.moduleId,
                digest.epoch,
                digest.version,
                localEpoch,
                localEpoch,
                "recorded"
            )
        }
    }

    private fun helloSnapshotEpochLabel(session: TalkbackSession): String =
        session.channelId?.let { lastSeenAuthorityFloorSnapshotByChannel[it]?.epoch?.toString() } ?: "none"

    private fun requestEpochLabel(session: TalkbackSession, requesterKey: String): String {
        val requester = GroupFloorController.resolveFloorOwner(session, requesterKey) ?: return "n/a"
        return if (isLocalIdentity(session, requester) && session.lastFloorRequestEpoch >= 0L) {
            session.lastFloorRequestEpoch.toString()
        } else {
            "n/a"
        }
    }

    private fun floorEpochDivergenceDiagnostics(
        session: TalkbackSession,
        grantFrom: String?,
        grantEpoch: Long,
        localEpoch: Long,
        owner: EndpointAddress
    ): Map<String, String> {
        val authorityModule = session.floorAuthorityModuleId?.value
            ?: session.initiatorModuleId?.value
            ?: "null"
        val helloSnapshot = session.channelId?.let { lastSeenAuthorityFloorSnapshotByChannel[it] }
        val fields = linkedMapOf(
            "authorityModule" to authorityModule,
            "authorityEpoch" to (helloSnapshot?.epoch?.toString() ?: "unknown"),
            "helloSnapshotEpoch" to (helloSnapshot?.epoch?.toString() ?: "none"),
            "localEpoch" to localEpoch.toString(),
            "grantEpoch" to grantEpoch.toString(),
            "grantFrom" to (grantFrom ?: "unknown"),
            "owner" to owner.key,
            "localIsAuthority" to GroupFloorController.isFloorAuthority(session, localModuleId.value).toString(),
            "rosterEpoch" to session.rosterEpoch.toString()
        )
        if (isLocalIdentity(session, owner) && session.lastFloorRequestEpoch >= 0L) {
            fields["requestEpoch"] = session.lastFloorRequestEpoch.toString()
        }
        return fields
    }

    private fun membershipDigestAlignedWithAuthority(session: TalkbackSession): Boolean {
        if (isMembershipAuthority(session)) return true
        val channelId = session.channelId ?: return false
        val authorityDigest = lastSeenAuthorityDigestByChannel[channelId] ?: return false
        val localDigest = TopologyDigest.fromSession(session)
        return localDigest.rosterEpoch == authorityDigest.rosterEpoch &&
            localDigest.memberHash == authorityDigest.memberHash
    }

    private fun evaluateGroupIdentityStability(session: TalkbackSession): GroupIdentityStability.Result {
        val channelId = session.channelId
        val authorityDigestSeen = channelId != null && lastSeenAuthorityDigestByChannel.containsKey(channelId)
        return GroupIdentityStability.evaluate(
            session = session,
            localModuleId = localModuleId.value,
            authorityDigestSeen = authorityDigestSeen,
            membershipDigestAlignedWithAuthority = membershipDigestAlignedWithAuthority(session),
            verifiedHelloEndpointId = ::verifiedHelloEndpointIdForModule
        )
    }

    private fun verifiedHelloEndpointIdForModule(moduleId: String): String? {
        lastKnownPrimaryEndpointByModule[moduleId]?.let { return it }
        val endpoints = stateSync.get(moduleId)?.endpoints ?: return null
        return endpoints.filter { it.online }.minByOrNull { it.endpointId }?.endpointId
            ?: endpoints.minByOrNull { it.endpointId }?.endpointId
    }

    private fun isChannelGatedForGroup(session: TalkbackSession): Boolean {
        val channelId = session.channelId ?: return false
        if (channelModeFsm(channelId).mode == ChannelMode.CONFERENCE) return true
        return sessions.values.any {
            it.channelId == channelId &&
                it.type == SessionType.CONFERENCE &&
                it.accepted &&
                it.id != session.id
        }
    }

    private fun peerMediaConnectedForSession(session: TalkbackSession): Set<String> =
        GroupMembershipSupport.canonicalMemberModuleIds(session)
            .map { it.value }
            .filter { it != localModuleId.value && isPeerMediaConnected(it) }
            .toSet()

    private fun buildGroupRuntimeHealthInput(session: TalkbackSession): GroupRuntimeHealthInput {
        val convergenceAge = GroupConvergenceTracker.convergenceAgeMs(
            lastConvergenceAnchorMsBySession[session.id]
        )
        return GroupRuntimeHealthInput(
            localModuleId = localModuleId,
            session = session,
            dialablePeerCount = countDialableRemoteModules(),
            membershipDigestAlignedWithAuthority = membershipDigestAlignedWithAuthority(session),
            peerMediaConnected = peerMediaConnectedForSession(session),
            iceStateForModule = ::iceStateForModule,
            channelGated = isChannelGatedForGroup(session),
            convergenceAgeMs = convergenceAge
        )
    }

    private fun ensureConvergenceAnchor(session: TalkbackSession) {
        lastConvergenceAnchorMsBySession.putIfAbsent(session.id, System.currentTimeMillis())
    }

    private fun touchConvergenceAnchor(session: TalkbackSession) {
        lastConvergenceAnchorMsBySession[session.id] = System.currentTimeMillis()
    }

    private fun acceptedGroupSessionsOnChannel(channelId: String): List<TalkbackSession> =
        sessions.values.filter {
            it.type == SessionType.GROUP && it.accepted && it.channelId == channelId
        }

    private fun isGroupSessionPeer(session: TalkbackSession, remoteModuleId: String): Boolean =
        remoteModuleId == session.local.moduleId.value ||
            remoteModuleId in session.memberModules.map { it.value } ||
            remoteModuleId in session.groupMembers.map { it.moduleId.value }

    private fun emitGroupTopologyForChannel(reason: TopologySnapshotReason, channelId: String) {
        acceptedGroupSessionsOnChannel(channelId).forEach { emitGroupTopologySnapshot(reason, it) }
    }

    private fun emitGroupTopologySnapshot(reason: TopologySnapshotReason, session: TalkbackSession) {
        if (session.type != SessionType.GROUP) return
        val health = GroupRuntimeHealthProjector.project(buildGroupRuntimeHealthInput(session))
        log(TopologySnapshotLogger.format(reason, health))
    }

    private fun maybeEmitAppStartSnapshot(session: TalkbackSession) {
        if (session.type != SessionType.GROUP) return
        if (!groupAppStartSnapshotEmitted.add(session.id)) return
        emitGroupTopologySnapshot(TopologySnapshotReason.APP_START, session)
        onGroupConvergenceBoundary(session)
    }

    private fun maybeEmitIceTopologySnapshot(remoteModuleId: String) {
        val now = System.currentTimeMillis()
        val last = lastIceTopologySnapshotMsByPeer[remoteModuleId] ?: 0L
        if (now - last < config.iceTopologySnapshotThrottleMs) return
        lastIceTopologySnapshotMsByPeer[remoteModuleId] = now
        sessions.values
            .filter { it.type == SessionType.GROUP && it.accepted && isGroupSessionPeer(it, remoteModuleId) }
            .forEach { session ->
                emitGroupTopologySnapshot(TopologySnapshotReason.ICE_STATE_CHANGED, session)
                onGroupConvergenceBoundary(session)
            }
    }

    private fun maybeEmitPeriodicBuildingSnapshots() {
        val now = System.currentTimeMillis()
        sessions.values
            .filter { it.type == SessionType.GROUP && it.accepted }
            .forEach { session ->
                val health = GroupRuntimeHealthProjector.project(buildGroupRuntimeHealthInput(session))
                val lastPeriodic = lastPeriodicBuildingMsBySession[session.id] ?: 0L
                if (!GroupConvergenceTracker.shouldEmitPeriodicBuilding(
                        readiness = health.groupTopologyReadiness,
                        convergenceAgeMs = health.convergenceAgeMs,
                        lastPeriodicMs = lastPeriodic,
                        nowMs = now,
                        stallThresholdMs = config.buildingStallThresholdMs,
                        periodicWindowMs = config.periodicBuildingWindowMs
                    )
                ) {
                    return@forEach
                }
                lastPeriodicBuildingMsBySession[session.id] = now
                log(TopologySnapshotLogger.format(TopologySnapshotReason.PERIODIC_BUILDING, health))
            }
    }

    private fun onGovernanceTransitionTerminal(record: TransitionRecord) {
        if (record.trigger != TransitionTrigger.MEETING_END) return
        if (record.terminal != TransitionTerminalState.READY) return
        val snapshot = buildGroupTransitionReadinessSnapshot(
            channelId = record.channelId,
            meshRecoveryState = "transition_terminal_ready"
        )
        GroupTransitionReadinessLog.onTransitionTerminalReady(
            channelId = record.channelId,
            moduleId = localModuleId.value,
            record = record,
            snapshot = snapshot
        )
    }

    private fun observeGroupTransitionBootstrapAttempt(
        channelId: String,
        resolvedPrimary: String?,
        waitingForPrimary: Boolean,
        meshRecoveryState: String
    ) {
        val attemptId = GroupTransitionReadinessLog.bootstrapAttemptCount(channelId) + 1
        val snapshot = buildGroupTransitionReadinessSnapshot(
            channelId = channelId,
            waitingForPrimary = waitingForPrimary,
            meshRecoveryState = meshRecoveryState
        )
        GroupTransitionReadinessLog.onBootstrapAttempt(
            channelId = channelId,
            moduleId = localModuleId.value,
            attemptId = attemptId,
            resolvedPrimary = resolvedPrimary,
            waitingForPrimary = waitingForPrimary,
            snapshot = snapshot
        )
    }

    private fun observeGroupTransitionPrimaryResolve(channelId: String, resolvedPrimary: String?) {
        val snapshot = buildGroupTransitionReadinessSnapshot(
            channelId = channelId,
            meshRecoveryState = "primary_resolve"
        )
        GroupTransitionReadinessLog.onPrimaryResolve(
            channelId = channelId,
            moduleId = localModuleId.value,
            resolvedPrimary = resolvedPrimary,
            snapshot = snapshot
        )
    }

    private fun observeGroupTransitionReadinessChanged(channelId: String, meshRecoveryState: String) {
        val snapshot = buildGroupTransitionReadinessSnapshot(
            channelId = channelId,
            meshRecoveryState = meshRecoveryState
        )
        GroupTransitionReadinessLog.onReadinessChanged(snapshot)
    }

    private fun buildGroupTransitionReadinessSnapshot(
        channelId: String,
        waitingForPrimary: Boolean = false,
        meshRecoveryState: String? = null
    ): GroupTransitionReadinessLog.Snapshot {
        val nowMs = System.currentTimeMillis()
        val groupSession = sessions.values.firstOrNull {
            it.channelId == channelId && it.type == SessionType.GROUP && it.accepted
        } ?: sessions.values.firstOrNull {
            it.channelId == channelId && it.type == SessionType.GROUP
        }
        val dialable = dialableRemoteModuleIds()
        val allModules = dialable + localModuleId
        val resolvedPrimary = resolveBootstrapPrimary(allModules)?.value
        val primaryMeshAdmission = GroupTransitionReadinessLog.computePrimaryMeshAdmissionObserved(
            groupSession = groupSession,
            resolvedBootstrapPrimaryModuleId = resolvedPrimary,
            localModuleId = localModuleId.value
        )
        val orphanBelief = GroupTransitionReadinessLog.computeOrphanBelief(
            localModuleId = localModuleId.value,
            groupSession = groupSession,
            resolvedBootstrapPrimaryModuleId = resolvedPrimary,
            primaryMeshAdmissionObserved = primaryMeshAdmission
        )
        val health = groupSession?.let {
            GroupRuntimeHealthProjector.project(buildGroupRuntimeHealthInput(it))
        }
        val activeTransition = channelGovernance.activeTransition(channelId)
        val terminalReady = when {
            activeTransition == null -> true
            activeTransition.isActive -> false
            activeTransition.terminal == TransitionTerminalState.READY -> true
            else -> false
        }
        val transitionState = when {
            activeTransition == null -> "IDLE"
            activeTransition.isActive -> activeTransition.phase.name
            else -> "TERMINAL_${activeTransition.terminal?.name ?: "UNKNOWN"}"
        }
        val joinedMembers = groupSession?.let {
            GroupMembershipSupport.canonicalMemberModuleIds(it).map { member -> member.value }.sorted()
        }.orEmpty()
        val activeMembers = groupSession?.let {
            activeMemberModuleIds(it).map { member -> member.value }.sorted()
        }.orEmpty()
        val peerIceStates = linkedMapOf<String, String>()
        dialable.forEach { moduleId ->
            peerIceStates[moduleId.value] = iceStateForModule(moduleId.value) ?: "UNKNOWN"
        }
        val bootstrapAttemptCount = GroupTransitionReadinessLog.bootstrapAttemptCount(channelId)
        return GroupTransitionReadinessLog.Snapshot(
            channelId = channelId,
            moduleId = localModuleId.value,
            timestampMs = nowMs,
            transition = GroupTransitionReadinessLog.TransitionObservation(
                state = transitionState,
                trigger = activeTransition?.trigger?.name,
                transitionId = activeTransition?.id?.raw?.toString(),
                startedAtMs = activeTransition?.startedAtMs,
                terminalReady = terminalReady
            ),
            session = GroupTransitionReadinessLog.SessionIdentityObservation(
                sessionLineageId = GroupTransitionReadinessLog.sessionLineageId(channelId),
                sessionTraceId = groupSession?.traceId,
                parentTraceId = GroupTransitionReadinessLog.parentTraceId(channelId),
                localSessionId = groupSession?.id,
                initiatorModuleId = groupSession?.initiatorModuleId?.value,
                anchorModuleId = groupSession?.anchorModuleId?.value,
                floorAuthorityModuleId = groupSession?.floorAuthorityModuleId?.value,
                resolvedBootstrapPrimaryModuleId = resolvedPrimary,
                membershipEpoch = groupSession?.rosterEpoch,
                baselineMembers = GroupTransitionReadinessLog.baselineMembers(channelId),
                orphanBelief = orphanBelief,
                sessionRole = GroupTransitionReadinessLog.sessionRole(groupSession, localModuleId.value)
            ),
            bootstrap = GroupTransitionReadinessLog.BootstrapObservation(
                waitingForPrimary = waitingForPrimary,
                resolvedPrimary = resolvedPrimary,
                bootstrapAttemptCount = bootstrapAttemptCount,
                meshRecoveryState = meshRecoveryState
            ),
            readiness = GroupTransitionReadinessLog.ReadinessObservation(
                membershipReady = health?.membershipReconciled == true,
                transmitReady = health?.transmitMissingPeers?.isEmpty() == true,
                terminalReady = terminalReady,
                joinedMembers = joinedMembers,
                activeMembers = activeMembers,
                transmitRequiredPeers = health?.transmitRequiredPeers.orEmpty(),
                transmitConnectedPeers = health?.transmitReadyPeers.orEmpty(),
                peerIceStates = peerIceStates
            ),
            receive = buildGroupTransitionReceiveObservation(groupSession)
        )
    }

    private fun buildGroupTransitionReceiveObservation(
        groupSession: TalkbackSession?
    ): GroupTransitionReadinessLog.ReceiveCapabilityObservation {
        if (groupSession == null) {
            return GroupTransitionReadinessLog.ReceiveCapabilityObservation(
                sampled = false,
                floorHolder = null,
                holderAudioReachable = null,
                holderMediaConnected = null,
                failureReason = null
            )
        }
        if (groupSession.floorAuthorityModuleId == null) {
            return GroupTransitionReadinessLog.ReceiveCapabilityObservation(
                sampled = false,
                floorHolder = null,
                holderAudioReachable = null,
                holderMediaConnected = null,
                failureReason = null
            )
        }
        val floorOwner = groupSession.floor.owner() ?: return GroupTransitionReadinessLog.ReceiveCapabilityObservation(
            sampled = false,
            floorHolder = null,
            holderAudioReachable = null,
            holderMediaConnected = null,
            failureReason = null
        )
        if (isLocalIdentity(groupSession, floorOwner)) {
            return GroupTransitionReadinessLog.ReceiveCapabilityObservation(
                sampled = false,
                floorHolder = floorOwner.key,
                holderAudioReachable = null,
                holderMediaConnected = null,
                failureReason = null
            )
        }
        val holderModuleId = floorOwner.moduleId.value
        val reachable = isFloorHolderAudioReachable(groupSession, holderModuleId)
        val mediaConnected = isPeerMediaConnected(holderModuleId)
        return GroupTransitionReadinessLog.ReceiveCapabilityObservation(
            sampled = true,
            floorHolder = floorOwner.key,
            holderAudioReachable = reachable,
            holderMediaConnected = mediaConnected,
            failureReason = if (!reachable) "HOLDER_AUDIO_UNREACHABLE" else null
        )
    }

    private fun onGroupConvergenceBoundary(session: TalkbackSession) {
        if (session.type != SessionType.GROUP || !session.accepted) return
        val health = GroupRuntimeHealthProjector.project(buildGroupRuntimeHealthInput(session))
        val sessionId = health.sessionId ?: return
        val previous = lastGroupTopologyReadinessBySession.put(
            sessionId,
            health.groupTopologyReadiness
        )
        if (previous != health.groupTopologyReadiness) {
            log(TopologySnapshotLogger.format(TopologySnapshotReason.READINESS_CHANGED, health))
            session.channelId?.let { channelId ->
                observeGroupTransitionReadinessChanged(
                    channelId = channelId,
                    meshRecoveryState = health.groupTopologyReadiness.name
                )
            }
            if (health.groupTopologyReadiness == GroupTopologyReadiness.OPERATIONAL) {
                session.channelId?.let { channelId ->
                    MeetingRecoveryLog.onMeshOperational(channelId)
                    channelGovernance.maybeCompleteRecovery(channelId, recoveryReady = true)
                }
            }
        }
    }

    private fun onGrantAppliedForMetrics(session: TalkbackSession) {
        PttTimingLog.grantApplied(session.id)
        if (session.type == SessionType.GROUP) {
            session.channelId?.let { MeetingRecoveryLog.onFirstFloorGrant(it) }
        }
    }

    internal fun governanceSnapshotForChannel(channelId: String): GroupChannelSnapshot? {
        val session = sessions.values.firstOrNull {
            it.type == SessionType.GROUP && it.accepted && it.channelId == channelId
        } ?: return null
        val health = GroupRuntimeHealthProjector.project(buildGroupRuntimeHealthInput(session))
        val identity = evaluateGroupIdentityStability(session)
        val topology = when (health.groupTopologyReadiness) {
            GroupTopologyReadiness.DISCOVERING -> TopologyReadinessLabel.DISCOVERING
            GroupTopologyReadiness.MEMBERSHIP_PENDING -> TopologyReadinessLabel.MEMBERSHIP_PENDING
            GroupTopologyReadiness.BUILDING -> TopologyReadinessLabel.BUILDING
            GroupTopologyReadiness.OPERATIONAL -> TopologyReadinessLabel.OPERATIONAL
        }
        return GroupChannelSnapshot(
            channelId = channelId,
            membershipReconciled = health.membershipReconciled,
            membershipDigestAligned = health.membershipDigestAligned,
            topologyReadiness = topology,
            transmitMissingPeers = health.transmitMissingPeers,
            floorAuthorityKnown = session.floorAuthorityModuleId != null,
            identityStable = identity.stable
        )
    }

    internal fun governanceConferenceAdmission(channelId: String): CapabilityReadiness {
        val active = channelGovernance.transitionCoordinator.activeTransition(channelId)
        if (active?.isActive == true && active.trigger == TransitionTrigger.MEETING_END) {
            return CapabilityReadiness.RECONCILING
        }
        val conference = sessions.values.firstOrNull {
            it.channelId == channelId && it.type == SessionType.CONFERENCE && it.accepted
        }
        if (conference != null) {
            if (!isConferenceHostSession(conference) && !isConferenceUiReady(conference)) {
                return CapabilityReadiness.RECONCILING
            }
            return CapabilityReadiness.READY
        }
        if (blocksGroupOnChannel(channelId)) {
            return CapabilityReadiness.NOT_READY
        }
        return CapabilityReadiness.READY
    }

    internal fun governanceConferenceSessionMedia(channelId: String): CapabilityReadiness? {
        val session = sessions.values.firstOrNull {
            it.channelId == channelId && it.type == SessionType.CONFERENCE && it.accepted
        } ?: return null
        return if (isConferenceUiReady(session)) {
            CapabilityReadiness.READY
        } else {
            CapabilityReadiness.RECONCILING
        }
    }

    private fun maybeEvaluateMeetingStartCompletion(channelId: String) {
        val session = meshSessionForChannel(channelId) ?: return
        if (session.type != SessionType.CONFERENCE || !session.accepted || !isConferenceHostSession(session)) {
            return
        }
        val declaration = meetingStartDeclaration(channelId)
        val connected = connectedExpectedInviteeCount(session, declaration?.expectedInviteTargets ?: emptySet())
        val eval = MeetingStartCompletion.evaluate(
            declaration = declaration,
            conferenceAccepted = true,
            connectedInviteeCount = connected
        )
        if (eval.reason == "invalid_declaration") {
            failMeetingStartDeclaration(channelId, "INVALID_DECLARATION")
            return
        }
        channelGovernance.maybeCompleteMeetingStart(channelId, eval, declaration)
        if (channelGovernance.activeTransition(channelId)?.trigger != TransitionTrigger.MEETING_START) {
            clearMeetingStartDeclaration(channelId)
        }
        sessions.values
            .filter { it.channelId == channelId && it.type == SessionType.CONFERENCE }
            .forEach { emitConferenceRuntimeProjection(it) }
    }

    /**
     * ADR-0017: consume invite dispatch outcome only — Coordinator does not retry sends.
     * [sentCount] must reflect invites actually dispatched in this completion window.
     */
    private fun onMeetingStartInviteDispatchCompleted(
        channelId: String,
        targetCount: Int,
        sentCount: Int
    ) {
        val declaration = meetingStartDeclaration(channelId) ?: return
        if (declaration.isFrozen) return
        when (declaration.mode) {
            MeetingMode.SOLO_HOST -> {
                if (targetCount == 0) {
                    freezeMeetingStartDeclaration(channelId, inviteDispatchFinished = true)
                }
            }
            MeetingMode.MULTI_PARTY -> {
                if (targetCount == 0) return
                val declaredTargetCount = declaration.expectedInviteTargets.size
                when {
                    sentCount < targetCount || sentCount < declaredTargetCount ->
                        failMeetingStartDeclaration(channelId, "INVITE_DISPATCH_FAILED")
                    else ->
                        freezeMeetingStartDeclaration(channelId, inviteDispatchFinished = true)
                }
            }
        }
    }

    private fun resolveMeetingStartIntent(
        channelId: String,
        remoteEndpoints: List<EndpointAddress>
    ): Pair<MeetingMode, Set<EndpointId>>? {
        pendingMeetingStartIntentByChannel[channelId]?.let { return it }
        if (remoteEndpoints.isNotEmpty()) {
            return MeetingMode.MULTI_PARTY to remoteEndpoints.map { it.endpointId }.toSet()
        }
        return null
    }

    private fun consumeMeetingStartIntent(channelId: String) {
        pendingMeetingStartIntentByChannel.remove(channelId)
    }

    private fun connectedExpectedInviteeCount(
        session: TalkbackSession,
        expectedTargets: Set<EndpointId>
    ): Int {
        if (expectedTargets.isEmpty()) return 0
        return meshRoster(session).count { member ->
            member.moduleId != session.local.moduleId &&
                member.endpointId in expectedTargets &&
                isPeerMediaConnected(member.moduleId.value)
        }
    }

    private fun failMeetingStartDeclaration(channelId: String, reason: String) {
        channelGovernance.failMeetingStart(channelId, reason)
        clearMeetingStartDeclaration(channelId)
    }

    private fun ensureMeetingStartDeclarationForInviteDispatch(
        channelId: String,
        invitees: List<EndpointAddress>,
        local: EndpointAddress
    ): Boolean {
        if (meetingStartDeclaration(channelId) != null) return true
        val targets = invitees
            .filter { it.moduleId != local.moduleId }
            .map { it.endpointId }
            .toSet()
        if (targets.isEmpty()) return false
        return openMeetingStartDeclaration(channelId, MeetingMode.MULTI_PARTY, targets) != null
    }

    private fun openMeetingStartDeclaration(
        channelId: String,
        mode: MeetingMode,
        targets: Set<EndpointId>
    ): MeetingStartDeclaration? {
        val window = meetingStartDeclarationByChannel.getOrPut(channelId) { MeetingStartDeclarationWindow() }
        return window.open(mode, targets)
    }

    private fun freezeMeetingStartDeclaration(
        channelId: String,
        inviteDispatchFinished: Boolean
    ): MeetingStartDeclaration? {
        val window = meetingStartDeclarationByChannel[channelId] ?: return null
        val frozen = window.freeze(inviteDispatchFinished) ?: return null
        GovernanceObservabilityLog.declarationFrozen(channelId, frozen)
        return frozen
    }

    private fun meetingStartDeclaration(channelId: String): MeetingStartDeclaration? =
        meetingStartDeclarationByChannel[channelId]?.current()

    private fun clearMeetingStartDeclaration(channelId: String) {
        meetingStartDeclarationByChannel.remove(channelId)
        pendingMeetingStartIntentByChannel.remove(channelId)
    }

    internal fun testGovernanceActiveTransitionTrigger(channelId: String): TransitionTrigger? =
        channelGovernance.activeTransition(channelId)?.takeIf { it.isActive }?.trigger

    internal fun governanceDirectoryReadiness(): CapabilityReadiness =
        if (countDialableRemoteModules() > 0) {
            CapabilityReadiness.READY
        } else {
            CapabilityReadiness.NOT_READY
        }

    internal fun governanceUnicastRoutingReadiness(): CapabilityReadiness =
        CapabilityReadiness.READY

    private fun log(message: String) {
        TalkbackLog.i(message)
        onLog?.invoke(message)
    }

    private fun runOnCoordinator(block: () -> Unit) {
        if (stopped || coordinatorExecutor.isShutdown) return
        runCatching {
            coordinatorExecutor.execute {
                if (stopped) return@execute
                onCoordinatorThread.set(true)
                try {
                    block()
                } catch (e: Exception) {
                    log("Coordinator error: ${e.message}")
                } finally {
                    onCoordinatorThread.set(false)
                }
            }
        }.onFailure { log("Coordinator dispatch skipped: ${it.message}") }
    }

    private fun <T> runOnCoordinatorSync(block: () -> T): T {
        if (stopped || coordinatorExecutor.isShutdown) {
            throw IllegalStateException("Coordinator stopped")
        }
        if (onCoordinatorThread.get()) {
            return block()
        }
        return coordinatorExecutor.submit(block).get()
    }

}
