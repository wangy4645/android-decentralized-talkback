package com.talkback.app

import com.talkback.core.audio.AudioRouter
import com.talkback.core.audio.DefaultAudioRouter
import com.talkback.core.audio.ModuleAudioMixer
import com.talkback.core.channel.ChannelManager
import com.talkback.core.channel.ChannelMembershipSnapshot
import com.talkback.core.contacts.CallableModuleGate
import com.talkback.core.presence.ModulePresenceSnapshot
import com.talkback.core.presence.PresenceProjector
import com.talkback.core.presence.SessionPresenceSnapshot
import com.talkback.core.contacts.ContactEndpointRow
import com.talkback.core.contacts.ContactsProjection
import com.talkback.core.discovery.CompositeModuleDiscoveryService
import com.talkback.core.discovery.DiscoverySignalHandler
import com.talkback.core.discovery.GossipDiscoveryControl
import com.talkback.core.discovery.ModuleDiscoveryService
import com.talkback.core.discovery.ModulePresence
import com.talkback.core.discovery.StaticPeerEntry
import java.util.concurrent.CopyOnWriteArrayList
import com.talkback.core.model.ConferenceRejoinPayload
import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.EndpointId
import com.talkback.core.model.EndpointPriority
import com.talkback.core.model.FloorPayload
import com.talkback.core.model.FloorSnapshotDigest
import com.talkback.core.media.MediaTopologyPolicy
import com.talkback.core.model.GroupSessionPayload
import com.talkback.core.model.MembershipSnapshot
import com.talkback.core.model.MeshSessionMode
import com.talkback.core.model.HelloPayload
import com.talkback.core.model.ModuleId
import com.talkback.core.model.RemoteEndpointInfo
import com.talkback.core.model.SignalEnvelope
import com.talkback.core.model.SignalType
import com.talkback.core.ptt.FloorArbitrator
import com.talkback.core.ptt.FloorGrantResult
import com.talkback.core.ptt.FloorPriorityPolicy
import com.talkback.core.ptt.GroupFloorController
import com.talkback.core.ptt.SnapshotResult
import com.talkback.core.ptt.PttEvent
import com.talkback.core.ptt.PttState
import com.talkback.core.session.AnchorAuthority
import com.talkback.core.session.AnchorElection
import com.talkback.core.session.AnchorHealthSnapshot
import com.talkback.core.session.AnchorRanking
import com.talkback.core.session.ChannelMeshHostElection
import com.talkback.core.session.ChannelLifecycleEvent
import com.talkback.core.session.ChannelLifecyclePolicy
import com.talkback.core.session.ChannelMode
import com.talkback.core.session.ChannelModeFsm
import com.talkback.core.session.ChannelReadiness
import com.talkback.core.session.ConferenceParticipantManager
import com.talkback.core.session.ConferenceSnapshot
import com.talkback.core.session.GroupMediaTopology
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
import com.talkback.core.util.FloorTrace
import com.talkback.core.util.PttTimingLog
import com.talkback.core.util.TalkbackLog
import com.talkback.core.webrtc.ConferenceAudioBus
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
    val groupMemberEvictSuspectMs: Long = 27_000L
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
    val leftAtMs: Long
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
    private val programAudioBus = ProgramAudioBus(mediaRegistry::getGroup)
    private val conferenceAudioBus = ConferenceAudioBus(mediaRegistry::getGroup)
    private val groupMeshReconciler = GroupMeshReconciler()
    private val conferenceParticipantManager = ConferenceParticipantManager()
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
    @Volatile
    private var invariantF1BreakCount = 0

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

    private fun meshParticipant(session: TalkbackSession, moduleId: String): ParticipantState =
        if (isConferenceSession(session)) {
            conferenceParticipantManager.participant(session.id, moduleId)
        } else {
            session.participant(moduleId)
        }

    private fun markMeshLinkCompleted(session: TalkbackSession, moduleId: String) {
        session.meshCompletedModules.add(moduleId)
        if (isConferenceSession(session)) {
            conferenceParticipantManager.onMediaConnected(session.id, moduleId)
        }
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
    }

    private fun getMeshEngine(moduleId: String): WebRtcAudioEngine? = mediaRegistry.getGroup(moduleId)

    private fun getOrCreateMeshEngine(session: TalkbackSession, moduleId: String): WebRtcAudioEngine =
        getMeshEngine(moduleId) ?: meshEngineFor(session, moduleId)

    /**
     * Conference / fresh mesh invites must not inherit a wedged GROUP peer connection.
     * Reconnect paths may reuse a live CONNECTED link or ICE-restart the existing PC.
     */
    private fun acquireMeshEngine(
        session: TalkbackSession,
        moduleId: String,
        forReconnect: Boolean
    ): WebRtcAudioEngine {
        val existing = getMeshEngine(moduleId)
        if (forReconnect && existing != null) {
            val ice = qosMonitor.snapshot(moduleId)?.iceState
            if (IceConnectivity.isConnected(ice)) {
                return existing
            }
        }
        if (existing != null) {
            mediaRegistry.releaseGroup(moduleId)
            qosMonitor.resetGroup(moduleId)
            session.meshCompletedModules.remove(moduleId)
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
    private val lastGroupMeshReconnectMsByPeer = ConcurrentHashMap<String, Long>()
    private val suppressMeshRepairUntilMsByChannel = ConcurrentHashMap<String, Long>()
    private val lastPlaybackEnabledBySession = ConcurrentHashMap<String, Boolean>()
    private val lastEnsureCanonicalInviteMsByModule = ConcurrentHashMap<String, Long>()
    private val seenNoncesByModule = ConcurrentHashMap<String, MutableMap<String, Long>>()
    private val coordinatorExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "talkback-coordinator")
    }
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
        onChannelLifecycleEvent(channelId, ChannelLifecycleEvent.ConferenceEnded)
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
            leftAtMs = record.leftAtMs
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
        sendConferenceRejoinInternal(channelId, authority, hostSessionId)
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
            { runOnCoordinator { cleanupExpiredSessions() } },
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

        val allMembers = meshRoster(session)
        applyGroupTopology(session, allMembers.size)
        val payloadBase = groupPayloadBase(session).copy(rejoin = rejoin)

        var sent = 0
        inviteTargets.forEach { remote ->
            val moduleId = remote.moduleId.value
            conferenceParticipantManager.leftMemberEndpoints(session.id)?.remove(moduleId)
            val forReconnect = rejoin
            if (!canSendConferenceInvite(session, moduleId, forReconnect = forReconnect)) {
                return@forEach
            }
            session.meshCompletedModules.remove(moduleId)
            val peer = resolvePeerForModule(moduleId)
            if (peer == null) {
                log("[${session.traceId}] Conference invite skipped: $moduleId not discovered")
                return@forEach
            }
            session.remotePeersByModule[moduleId] = peer
            conferenceParticipantManager.onInviteSent(
                session.id,
                moduleId,
                System.currentTimeMillis(),
                forReconnect
            )
            val existingEngine = getMeshEngine(moduleId)
            val engine = if (forReconnect && existingEngine != null &&
                IceConnectivity.isConnected(qosMonitor.snapshot(moduleId)?.iceState)
            ) {
                existingEngine
            } else {
                if (forReconnect) {
                    releasePeerMediaOnly(session, moduleId)
                    session.remotePeersByModule[moduleId] = peer
                }
                acquireMeshEngine(session, moduleId, forReconnect = forReconnect)
            }
            wireIceCallback(session, moduleId, engine)
            val offer = engine.createOffer(iceRestart = forReconnect)
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
                ReDialRecord(local, remote, channelId, allMembers, SessionType.CONFERENCE)
            sent++
            val label = if (rejoin) "Conference rejoin invite" else "Conference invite"
            log("[${session.traceId}] $label sent -> ${remote.key}")
        }
        return sent
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
        endConflictingMeshSessions(channelId, sessionType)
        findReusableMeshSession(channelId, sessionType)?.let { existing ->
            log("[${existing.traceId}] Reuse ${sessionType.name} session for channel=$channelId")
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
        val allMembers = ChannelMembershipSnapshot.resolveInitialInvites(
            configured = channelSnap,
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
        if (soloConference) {
            tryEnsureConferenceDuplex(session)
            log("[${session.traceId}] $label ${local.key} solo on ch=$channelId")
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
        session.localAcquireTimedOut = false
        val currentOwner = session.floor.owner()
        log(
            "PTT_GATE ${sessionTag(session)} sid=$sessionId " +
                "hash=${System.identityHashCode(session)} " +
                "owner=${currentOwner?.key ?: "null"} " +
                "version=${session.floor.version()} epoch=${session.floor.epoch()} " +
                "ptt=${session.ptt.state}"
        )
        if (currentOwner != null && currentOwner != session.local) {
            log(
                "PTT_GATE ${sessionTag(session)} silent-return-owner " +
                    "owner=${currentOwner.key} version=${session.floor.version()} " +
                    "epoch=${session.floor.epoch()}"
            )
            return
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
        val traceId = FloorTrace.nextId()
        val payload = FloorPayload.forRequest(
            session.local,
            version,
            session.floor.epoch(),
            priority,
            traceId = traceId
        ).encode()
        session.lastFloorRequestMs = System.currentTimeMillis()
        dispatchFloorRequest(session, payload, traceId)
    }

    private fun onPttReleasedInternal(sessionId: String) {
        PttTimingLog.pttUp(sessionId)
        val session = sessions[sessionId] ?: return
        if (!session.type.usesFloorControl()) return
        acquireReleaseWatchdog.onFloorLost(sessionId)
        val state = session.ptt.onEvent(PttEvent.Release)
        session.pendingTransmit = false
        stopSessionCapture(session)
        moduleMixer.setActiveCapture(null)
        if (state == PttState.RELEASE_FLOOR && session.floor.release(session.local)) {
            broadcastFloorRelease(session)
        }
        updateSessionReceivePlayback(session, "ptt_released")
    }

    fun hangup(sessionId: String) = runOnCoordinatorSync { hangupInternal(sessionId) }

    /** Leave a conference locally without ending it for remaining participants. */
    fun leaveConference(sessionId: String) = runOnCoordinator { leaveConferenceInternal(sessionId) }

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

    fun setCallMuted(sessionId: String, muted: Boolean) = runOnCoordinatorSync {
        val session = sessions[sessionId] ?: return@runOnCoordinatorSync
        if (session.type != SessionType.UNICAST && session.type != SessionType.CONFERENCE) {
            return@runOnCoordinatorSync
        }
        session.muted = muted
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
        if (stopped) return@runOnCoordinatorSync ChannelReadiness.NO_SERVICE
        val dialablePeers = countDialableRemoteModules()
        val session = meshSessionForChannel(channelId)
        when {
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
        memberViews = conferenceSnap?.memberViews ?: buildMemberViews(session),
        connectedRemoteCount = countConnectedRemotes(session),
        callPhase = session.unicastPhase,
        remoteKey = session.remote?.key,
        localInitiated = session.localInitiated,
        muted = session.muted
    )
    }

    fun networkQualityLabel(): String = runOnCoordinatorSync {
        val states = qosMonitor.all()
        when {
            states.isEmpty() -> "N/A"
            states.any { it.iceState == "FAILED" || it.iceState == "DISCONNECTED" } -> "Poor"
            states.all { IceConnectivity.isConnected(it.iceState) } -> "Excellent"
            else -> "Good"
        }
    }

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
        if (owner == session.local) return@runOnCoordinatorSync true
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

    internal fun testIsSessionCapturing(sessionId: String): Boolean = runOnCoordinatorSync {
        val session = sessions[sessionId] ?: return@runOnCoordinatorSync false
        sessionMediaEngines(session).any { it.isCapturing() }
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
            SignalType.FLOOR_REQUEST -> handleFloorRequest(signal)
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
            .forEach { reconcileGroupMembership(it, "hello_${payload.moduleId}") }
        assertCanonicalConsistencyFromHello(payload)
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
                val result = GroupFloorController.applyAuthorityFloorSnapshot(
                    session = session,
                    authorityModuleId = payload.moduleId,
                    digest = digest,
                    onOwnerChanged = { updateSessionReceivePlayback(session) }
                )
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
                        if (session.floor.owner() == session.local) {
                            PttTimingLog.grantApplied(session.id)
                            onLocalProtocolFloorAcquired(session)
                        } else if (session.floor.owner() != null && session.floor.owner() != session.local) {
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
                lastRejoinableConferenceByChannel.entries
                    .filter { it.value.hostSessionId == signal.sessionId }
                    .forEach { (channelId, _) -> clearConferenceRejoinState(channelId) }
                log("Conference rejoin rejected: meeting ended session=${signal.sessionId}")
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
        if (callerModuleId in conferenceMemberRemoteIds(hostConference) &&
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

    private fun rememberRejoinableConference(session: TalkbackSession) {
        val channelId = session.channelId ?: return
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
            leftAtMs = System.currentTimeMillis()
        )
        lastRejoinableConferenceByChannel[channelId] = record
        pendingRejoinByChannel[channelId] = session.id
        log("[${session.traceId}] Conference rejoin memory saved ch=$channelId host=$hostModuleId")
    }

    private fun clearConferenceRejoinState(channelId: String) {
        lastRejoinableConferenceByChannel.remove(channelId)
        pendingRejoinByChannel.remove(channelId)
        conferenceRejoinStartedAtByChannel.remove(channelId)
        cancelHostRejoinRetry(channelId)
    }

    private fun clearRejoinHintsForSession(channelId: String, sessionId: String) {
        lastRejoinableConferenceByChannel[channelId]?.takeIf { it.hostSessionId == sessionId }?.let {
            clearConferenceRejoinState(channelId)
        }
        if (pendingRejoinByChannel[channelId] == sessionId) {
            clearConferenceRejoinState(channelId)
        }
    }

    private fun sendConferenceRejoinInternal(
        channelId: String,
        authority: EndpointAddress,
        hostSessionId: String
    ): Boolean {
        val peer = resolvePeerForModule(authority.moduleId.value) ?: run {
            log("Conference rejoin skipped: authority ${authority.moduleId.value} not reachable")
            return false
        }
        val payload = ConferenceRejoinPayload(
            channelId = channelId,
            hostSessionId = hostSessionId
        ).encode()
        val local = EndpointAddress(localModuleId, localEndpointId())
        sendSignal(
            peer,
            buildSignedEnvelope(
                SignalType.CONFERENCE_REJOIN,
                local,
                authority,
                hostSessionId.ifBlank { UUID.randomUUID().toString() },
                payload
            )
        )
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
            "Conference rejoin requested by ${local.moduleId.value} -> authority " +
                "${authority.moduleId.value} session=$hostSessionId ch=$channelId"
        )
        return true
    }

    private fun handleConferenceRejoin(signal: SignalEnvelope, fromPeer: PeerTarget) {
        val payload = ConferenceRejoinPayload.decode(signal.payload) ?: return
        val rejoinerId = signal.from.moduleId.value
        val channelId = payload.channelId
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
        log("[${hostConference.traceId}] Conference rejoin pull-in $rejoinerId sent=$sent")
    }

    private fun markConferenceParticipantLeft(session: TalkbackSession, moduleId: String) {
        if (moduleId == localModuleId.value) return
        removeConferenceParticipant(session, moduleId)
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

        val session = TalkbackSession(signal.sessionId, sessionType, callee, channelId)
        populateGroupSessionMetadata(session, payload, members, caller, fromPeer)
        freezeChannelMemberSnapshot(session)
        sessions[signal.sessionId] = session
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
        drainPendingGroupJoins(session.id)
        scheduleGroupMeshRetries(session.id)
        updateSessionReceivePlayback(session)
        return true
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
                log("Rejecting GROUP invite while conference active/preferred/pending on $channelId")
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
        if (incomingType == SessionType.GROUP && channelId != null && blocksGroupOnChannel(channelId)) {
            log("Rejecting GROUP_JOIN while conference active/preferred/pending on $channelId")
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
            val answer = engine.applyRemoteOffer(payload.sdp, politeForMeshPair(peerId))
            drainPendingIce(session.id, peerId, engine)
            sendSignal(
                fromPeer,
                buildSignedEnvelope(SignalType.GROUP_ACCEPT, callee, caller, signal.sessionId, answer)
            )
            log("${sessionTag(session)} Group mesh ICE restart accepted $peerId ice=$ice")
            if (session.type == SessionType.CONFERENCE) {
                markConferenceParticipantActive(session, caller.moduleId.value)
            }
            updateSessionReceivePlayback(session)
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
            else -> getMeshEngine(moduleId)
        }
        if (engine == null) {
            queuePendingIce(signal.sessionId, moduleId, signal.payload)
            return
        }
        engine.addIceCandidate(signal.payload)
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
            localIsAuthority
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
        processFloorArbitration(session, signal, floorPayload)
    }

    private fun processFloorArbitration(
        session: TalkbackSession,
        signal: SignalEnvelope,
        floorPayload: FloorPayload
    ) {
        if (!session.type.usesFloorControl()) return
        val traceId = floorPayload.traceId
        val requester = signal.from
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
        val memberPresent = session.groupMembers.any { it.key == requester.key }
        val result = session.floor.tryGrant(
            requester = requester,
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
                val grantPayload = FloorPayload.forRequest(
                    requester,
                    session.floor.version(),
                    session.floor.epoch(),
                    effectivePriority,
                    traceId = traceId
                )
                applyFloorGrant(
                    session,
                    requester,
                    grantPayload.floorVersion,
                    grantPayload.floorEpoch,
                    effectivePriority,
                    floorAlreadyGranted = true,
                    traceId = traceId
                )
                broadcastFloorGranted(session, grantPayload.encode(), traceId)
                if (result == FloorGrantResult.PREEMPTED &&
                    previousOwner != null &&
                    previousOwner != requester
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
                if (requester == session.local) {
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
                session.floor.epoch()
            )
            val owner = GroupFloorController.resolveFloorOwner(session, floorPayload.requesterKey)
            log(
                "GRANT_OBSERVED ${sessionTag(session)} sid=${session.id} " +
                    "from=${signal.from.key} requesterKey=${floorPayload.requesterKey} " +
                    "resolved=${if (owner != null) "OK" else "ROSTER_MISS"} " +
                    "grantEpoch=${floorPayload.floorEpoch} localEpoch=${session.floor.epoch()} " +
                    "ownerIsLocal=${owner == session.local}"
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
                traceId = traceId
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
        traceId: Long = 0L
    ) {
        if (session.type != SessionType.GROUP) return
        var grantAccepted = floorAlreadyGranted
        if (!floorAlreadyGranted) {
            val localEpochBefore = session.floor.epoch()
            val staleEpoch = floorEpoch < localEpochBefore
            if (staleEpoch) {
                FloorTrace.grantDropped(
                    traceId,
                    session.id,
                    "STALE_EPOCH",
                    mapOf(
                        "grantEpoch" to floorEpoch.toString(),
                        "localEpoch" to localEpochBefore.toString(),
                        "owner" to owner.key
                    )
                )
            }
            GroupFloorController.applyRemoteGrant(
                session,
                owner,
                floorVersion,
                floorEpoch,
                priority
            )
            if (!staleEpoch) {
                grantAccepted = true
            }
        }
        PttTimingLog.grantApplied(session.id)
        if (owner == session.local) {
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
        val targets = targetsForSession(session).joinToString(",") { (peer, remote) ->
            "${remote.key}@${peer.host}:${peer.port}"
        }
        FloorTrace.grantBroadcast(traceId, session.id, "[$targets]")
        log(
            "GRANT_BROADCAST ${sessionTag(session)} sid=${session.id} " +
                "targets=[$targets]"
        )
        broadcastFloorSignal(session, SignalType.FLOOR_GRANTED, grantPayload)
    }

    private fun handleFloorDeny(signal: SignalEnvelope) {
        val session = sessions[signal.sessionId] ?: return
        if (!session.type.usesFloorControl()) return
        if (signal.to == session.local) {
            session.ptt.onEvent(PttEvent.Rejected)
            session.pendingTransmit = false
            acquireReleaseWatchdog.onFloorLost(session.id)
            stopSessionCapture(session)
        }
    }

    private fun handleFloorPreempted(signal: SignalEnvelope) {
        val session = sessions[signal.sessionId] ?: return
        if (!session.type.usesFloorControl()) return
        if (signal.to != session.local) return
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
        if (session.local == signal.from) {
            session.ptt.onEvent(PttEvent.Release)
        }
        acquireReleaseWatchdog.onFloorLost(session.id)
        if (session.floor.owner() != session.local) {
            stopSessionCapture(session)
        }
        syncProgramRelay(session)
        updateSessionReceivePlayback(session)
    }

    private fun handleHangup(signal: SignalEnvelope) {
        clearPendingConferenceInvite(sessionId = signal.sessionId)
        val session = sessions.remove(signal.sessionId) ?: return
        val wasUnicast = session.type == SessionType.UNICAST
        pendingGroupJoinsBySession.remove(signal.sessionId)
        pendingIceBySession.remove(signal.sessionId)
        session.remote?.moduleId?.value?.let { reDialByRemoteModule.remove(it) }
        session.remotePeersByModule.keys.forEach { reDialByRemoteModule.remove(it) }
        if (session.type == SessionType.CONFERENCE) {
            session.channelId?.let { clearRejoinHintsForSession(it, session.id) }
        }
        session.channelId?.let { ch ->
            groupMeshReconciler.clearChannel(ch)
            if (sessions.values.none { it.channelId == ch && it.type == SessionType.CONFERENCE }) {
                if (session.type == SessionType.CONFERENCE) {
                    releaseConferenceChannelForGroupPtt(ch, "remote_hangup")
                } else {
                    releaseChannelModeIfIdle(ch)
                }
            }
        }
        session.ptt.onEvent(PttEvent.RemoteHangup)
        releaseSessionMedia(session)
        log("${sessionTag(session)} Remote hangup")
        if (wasUnicast) {
            resumeGroupSessionsAfterUnicast(signal.sessionId)
        }
    }

    private fun handleGroupLeave(signal: SignalEnvelope) {
        val session = sessions[signal.sessionId] ?: return
        if (session.type != SessionType.CONFERENCE) return
        val leavingModuleId = signal.from.moduleId.value
        if (leavingModuleId == localModuleId.value) return
        if (leavingModuleId == session.initiatorModuleId?.value) {
            log("[${session.traceId}] Conference host left via GROUP_LEAVE, ending session")
            hangupInternal(session.id)
            return
        }
        // Do not apply the leaver's member roster — it is often incomplete (e.g. mesh still
        // negotiating) and can drop remaining participants from the meeting.
        markConferenceParticipantLeft(session, leavingModuleId)
        repairConferenceMeshAfterLeave(session)
        log(
            "[${session.traceId}] Conference peer left: $leavingModuleId " +
                "remaining=${session.groupMembers.size} connected=${countConnectedRemotes(session)}"
        )
    }

    private fun leaveConferenceInternal(sessionId: String) {
        val active = sessions[sessionId] ?: return
        if (active.initiatorModuleId == localModuleId) {
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
        targetsForSession(session).forEach { (peer, remote) ->
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

    private fun removeConferenceParticipant(session: TalkbackSession, moduleId: String) {
        if (moduleId == localModuleId.value) return
        releaseFloorIfHolderUnavailable(session, moduleId)
        conferenceParticipantManager.applyPrune(session.id, moduleId)
        session.memberModules.remove(ModuleId(moduleId))
        releaseMeshPeer(session, moduleId)
        if (session.remote?.moduleId?.value == moduleId) {
            val nextRemote = meshRoster(session).firstOrNull { it.moduleId != localModuleId }
            session.remote = nextRemote
            session.remotePeer = nextRemote?.moduleId?.value?.let { session.remotePeersByModule[it] }
        }
        session.touch()
        resumeConferenceAudioAfterPeerLeft(session)
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

    private fun canSendConferenceInvite(
        session: TalkbackSession,
        moduleId: String,
        forReconnect: Boolean = false
    ): Boolean {
        if (qosMonitor.isGroupConnected(moduleId)) return false
        if (forReconnect) return true
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
            getMeshEngine(id) != null && qosMonitor.isGroupConnected(id)
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
        val wasUnicast = session.type == SessionType.UNICAST
        if (session.type == SessionType.CONFERENCE) {
            session.channelId?.let { channelId ->
                clearRejoinHintsForSession(channelId, session.id)
                cancelHostRejoinRetry(channelId)
            }
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
            return moduleIds.mapNotNull { getMeshEngine(it) }
        }
        val fallback = session.remote?.moduleId?.value ?: return emptyList()
        return listOfNotNull(getMeshEngine(fallback))
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
        return moduleIds.mapNotNull { getMeshEngine(it) }
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

    private fun startSessionCapture(session: TalkbackSession) {
        if (session.type == SessionType.GROUP) {
            val floorOwner = session.floor.owner()
            if (floorOwner != session.local) {
                invariantF1BreakCount++
                TalkbackLog.e(
                    "INVARIANT_F1_BREAK session=${session.id} " +
                        "local=${session.local.key} floorOwner=${floorOwner?.key ?: "null"}"
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

    private fun broadcastFloorRequest(session: TalkbackSession, payload: String) {
        targetsForSession(session).forEach { (peer, remote) ->
            sendSignal(
                peer,
                buildSignedEnvelope(SignalType.FLOOR_REQUEST, session.local, remote, session.id, payload)
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
                session.local.key,
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
            from = session.local,
            to = session.local,
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
                session.local.key,
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
                session.local.key,
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
            buildSignedEnvelope(SignalType.FLOOR_REQUEST, session.local, remote, session.id, payload)
        )
        FloorTrace.requestSend(
            traceId,
            session.id,
            session.local.key,
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
                buildSignedEnvelope(type, session.local, remote, session.id, payload)
            )
        }
    }

    private fun broadcastFloorRelease(session: TalkbackSession) {
        val payload = FloorPayload(session.floor.version(), session.floor.epoch()).encode()
        targetsForSession(session).forEach { (peer, remote) ->
            sendSignal(
                peer,
                buildSignedEnvelope(SignalType.FLOOR_RELEASE, session.local, remote, session.id, payload)
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
            buildSignedEnvelope(type, session.local, remote, session.id, payload)
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
        val hostId = session.initiatorModuleId?.value ?: return false
        return !IceConnectivity.isConnected(qosMonitor.snapshot(hostId)?.iceState)
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
        session.groupMembers.map { it.moduleId }.toSet()

    private fun iceStateForModule(moduleId: String): String? =
        qosMonitor.snapshot(moduleId)?.iceState

    private fun activeMemberModuleIds(session: TalkbackSession): Set<ModuleId> =
        GroupMembershipSupport.activeMemberModuleIds(session, ::iceStateForModule)

    private fun bumpRosterEpoch(session: TalkbackSession, reason: String) {
        val epoch = GroupMembershipSupport.bumpRosterEpoch(session)
        log("${sessionTag(session)} rosterEpoch=$epoch reason=$reason")
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
                completeGroupMesh(session)
                updateSessionReceivePlayback(session)
                tryFlushPendingTransmit(session)
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

    private fun groupPayloadBase(session: TalkbackSession): GroupSessionPayload {
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
                .takeIf { session.type == SessionType.GROUP } ?: 0
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

    private fun syncConferenceRelay(session: TalkbackSession) {
        if (session.type != SessionType.CONFERENCE) return
        conferenceAudioBus.updateParticipants(session, localModuleId)
    }

    private fun releaseFloorIfHolderUnavailable(session: TalkbackSession, moduleId: String) {
        if (!session.type.usesFloorControl()) return
        val owner = session.floor.owner() ?: return
        if (owner.moduleId.value != moduleId) return
        session.floor.release(owner)
        if (owner == session.local) {
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
        val offer = engine.createOffer(iceRestart = meshPeer)
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
        if (session.floor.owner() != session.local) return
        if (session.ptt.state == PttState.REQUEST_FLOOR) {
            session.ptt.onEvent(PttEvent.Granted)
        }
        beginTransmitIfReady(session)
        scheduleAcquireReleaseIfNeeded(session)
    }

    private fun scheduleAcquireReleaseIfNeeded(session: TalkbackSession) {
        if (!session.type.usesFloorControl()) return
        if (session.floor.owner() != session.local) return
        val capturing = sessionMediaEngines(session).any { it.isCapturing() }
        acquireReleaseWatchdog.onLocalGrantApplied(session.id, alreadyCapturing = capturing)
    }

    private fun onAcquireReleaseTimeout(sessionId: String) {
        val session = sessions[sessionId] ?: return
        if (!session.type.usesFloorControl()) return
        if (session.floor.owner() != session.local) return
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
        if (session.floor.release(session.local)) {
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
                    ownerKey = floor.owner()?.key,
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
                ownerKey = session.floor.owner()?.key,
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
        pending.forEach { engine.addIceCandidate(it) }
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
            qosMonitor.updateIceState(remoteModuleId, state)
            log("ICE $remoteModuleId state=$state ${qosMonitor.formatSummary()}")
            if (IceConnectivity.isConnected(state)) {
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
                            conferenceParticipantManager.onMediaConnected(session.id, remoteModuleId)
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
                    }
                sessions.values
                    .filter { it.type == SessionType.CONFERENCE && it.accepted }
                    .forEach {
                        updateSessionReceivePlayback(it, "ice_$state")
                        tryEnsureConferenceDuplex(it)
                    }
                sessions.values
                    .filter {
                        it.mediaTopology == GroupMediaTopology.ANCHOR &&
                            it.accepted &&
                            (it.type == SessionType.GROUP || it.type == SessionType.CONFERENCE)
                    }
                    .forEach { session ->
                        syncConferenceRelay(session)
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
                tryRecoverStuckCheckingPeer(remoteModuleId)
                tryRecoverStuckCheckingConferencePeer(remoteModuleId)
            }
            if (state == "FAILED" || state == "DISCONNECTED") {
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
                            remoteModuleId in conferenceMemberRemoteIds(it)
                    }
                    .toList()
                    .forEach { session ->
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
        getMeshEngine(moduleId) != null && qosMonitor.isGroupConnected(moduleId)

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
            val stackActing = activityStack.topActingEndpointId()
            if (stackActing != null && stackActing != session.local.key) {
                log(
                    "CAPTURE_STACK_MISMATCH ${sessionTag(session)} " +
                        "stackActing=$stackActing local=${session.local.key}"
                )
                return
            }
            moduleMixer.setActiveCapture(session.local)
            audioRouter.selectInput(session.local)
            startSessionCapture(session)
            syncProgramRelay(session)
        } else {
            session.pendingTransmit = true
            stopSessionCapture(session)
            if (session.floor.owner() == session.local) {
                scheduleAcquireReleaseIfNeeded(session)
            }
        }
    }

    private fun tryFlushPendingTransmit(session: TalkbackSession) {
        if (!session.pendingTransmit) return
        if (session.ptt.state != PttState.TALK) return
        if (session.floor.owner() != session.local) return
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
            if (getMeshEngine(id) == null) return false
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
                getMeshEngine(id) != null &&
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
            getMeshEngine(id) != null &&
                IceConnectivity.isNegotiating(qosMonitor.snapshot(id)?.iceState)
        }
    }

    private fun hasLiveMeshNegotiation(
        session: TalkbackSession,
        remoteIds: Collection<String>
    ): Boolean = remoteIds.any { moduleId ->
        val ice = qosMonitor.snapshot(moduleId)?.iceState
        if (IceConnectivity.isLiveNegotiation(ice)) return true
        return getMeshEngine(moduleId) != null || moduleId in session.meshCompletedModules
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
            val engine = getMeshEngine(id)
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

    private fun scheduleParticipantPrune(
        session: TalkbackSession,
        moduleId: String,
        iceState: String
    ) {
        if (iceState == "FAILED") {
            cancelParticipantPrune(session.id, moduleId)
            log("[${session.traceId}] Conference ICE FAILED for $moduleId, pruning peer")
            removeConferenceParticipant(session, moduleId)
            repairConferenceMeshAfterLeave(session)
            return
        }
        if (iceState != "DISCONNECTED") return
        val graceMs = config.conferenceParticipantPruneGraceMs
        if (graceMs <= 0L) {
            cancelParticipantPrune(session.id, moduleId)
            log("[${session.traceId}] Conference ICE DISCONNECTED for $moduleId, pruning peer")
            removeConferenceParticipant(session, moduleId)
            repairConferenceMeshAfterLeave(session)
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
                        val ice = qosMonitor.snapshot(moduleId)?.iceState ?: "UNKNOWN"
                        log("[${current.traceId}] Conference peer $moduleId still $ice after grace, pruning")
                        removeConferenceParticipant(current, moduleId)
                        repairConferenceMeshAfterLeave(current)
                    }
                }
            }
        }, graceMs, TimeUnit.MILLISECONDS)
        bySession[moduleId] = future
    }

    private fun handleParticipantHostLinkLoss(session: TalkbackSession, iceState: String) {
        val channelId = session.channelId ?: return
        val hostId = session.initiatorModuleId?.value ?: return
        markConferenceReconnecting(session)
        log("[${session.traceId}] Conference host ICE $iceState (participant waiting for recovery)")
        if (iceState == "FAILED" || !config.iceReconnectEnabled) {
            scheduleHostRejoinRetry(channelId, session, immediate = true)
            return
        }
        val hostEndpoint = session.groupMembers.find { it.moduleId.value == hostId }
            ?: EndpointAddress(ModuleId(hostId), session.local.endpointId)
        val restarted = attemptConferencePeerIceRestart(session, hostId, hostEndpoint)
        if (!restarted) {
            scheduleHostRejoinRetry(channelId, session, immediate = false)
        }
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
                    val hostEndpoint = current.groupMembers.find { it.moduleId.value == hostModuleId }
                        ?: return@runOnCoordinator
                    if (attemptConferencePeerIceRestart(current, hostModuleId, hostEndpoint)) {
                        log("${sessionTag(current)} Conference host link kick -> $hostModuleId delay=${delayMs}ms")
                    }
                }
            }, delayMs, TimeUnit.MILLISECONDS)
        }
    }

    private fun attemptConferencePeerIceRestart(
        session: TalkbackSession,
        remoteModuleId: String,
        remote: EndpointAddress
    ): Boolean {
        if (!config.iceReconnectEnabled) return false
        val peer = session.remotePeersByModule[remoteModuleId] ?: return false
        val engine = getMeshEngine(remoteModuleId) ?: return false
        wireIceCallback(session, remoteModuleId, engine)
        val offer = engine.createOffer(iceRestart = true)
        drainPendingIce(session.id, remoteModuleId, engine)
        sendSignal(
            peer,
            buildSignedEnvelope(
                SignalType.GROUP_JOIN,
                session.local,
                remote,
                session.id,
                groupPayloadBase(session).copy(sdp = offer).encode()
            )
        )
        log("[${session.traceId}] ICE restart offer sent -> $remoteModuleId")
        return true
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
                val authority = session.groupMembers.find { it.moduleId.value == hostId }
                    ?: hint?.let {
                        EndpointAddress(
                            ModuleId(it.hostModuleId),
                            EndpointId(it.hostKey.substringAfter('-', "E01"))
                        )
                    }
                    ?: EndpointAddress(ModuleId(hostId), session.local.endpointId)
                val sent = sendConferenceRejoinInternal(channelId, authority, hostSessionId)
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
            syncConferenceRelay(session)
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
        syncConferenceRelay(session)
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
        if (accepted || getMeshEngine(moduleId) != null) {
            if (attemptConferencePeerIceRestart(session, moduleId, remote)) {
                log("[${session.traceId}] Forcing conference ICE-restart for $moduleId stuck in $ice")
            }
            return
        }
        val sent = sendConferenceInvitesInternal(session.id, listOf(remote))
        if (sent > 0) {
            log("[${session.traceId}] Re-invited conference peer $moduleId stuck in $ice")
        }
    }

    private fun recoverStuckConferenceHostLink(session: TalkbackSession, hostId: String, ice: String) {
        val hostEndpoint = session.groupMembers.find { it.moduleId.value == hostId } ?: return
        if (attemptConferencePeerIceRestart(session, hostId, hostEndpoint)) {
            log("${sessionTag(session)} Forcing conference host ICE-restart for $hostId stuck in $ice")
            return
        }
        session.channelId?.let { scheduleHostRejoinRetry(it, session, immediate = false) }
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
                return
            }
            val inviteModuleIds = GroupMeshPlanner.inviteTargets(localModuleId, allModules)
            if (inviteModuleIds.isEmpty()) return
            val inviteEndpoints = inviteModuleIds.mapNotNull { endpointForDialableModule(it) }
            if (inviteEndpoints.isEmpty()) return
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
     * After meeting tests, a lingering CONFERENCE session blocks GROUP on the same channel.
     * Reclaim the channel when the app is no longer in meeting-preferred mode.
     */
    private fun isLiveConferenceSession(session: TalkbackSession): Boolean {
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

    private fun endStaleConferenceBlockingGroup(channelId: String) {
        if (meetingPreferred || uiMeetingPreferredChannels[channelId] == true) return
        if (pendingConferenceInvitesByChannel.containsKey(channelId)) return
        val stale = sessions.values.filter {
            it.channelId == channelId && it.type == SessionType.CONFERENCE
        }
        if (stale.isEmpty()) return
        if (stale.any(::isLiveConferenceSession)) return
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
        }
        session.anchorModuleId = winnerPrimary
        session.backupAnchorModuleId = null
        if (localModuleId != winnerPrimary) {
            offerGroupMeshJoin(session, winnerPrimary)
        }
        maintainBackupStandby(session)
        updateSessionReceivePlayback(session, "split_brain_recovery")
        syncConferenceRelay(session)
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
                    session.type == SessionType.CONFERENCE -> session.accepted && !session.muted
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
                    syncConferenceRelay(session)
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
        if (moduleId in conferenceMemberRemoteIds(hostConference) &&
            qosMonitor.isGroupConnected(moduleId)
        ) {
            return false
        }
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
        val deadRemotes = conferenceMemberRemoteIds(session).filter { moduleId ->
            if (hostId != null && hostId != localModuleId.value && moduleId == hostId) {
                return@filter false
            }
            canPruneConferenceParticipant(session, moduleId, now)
        }
        if (deadRemotes.isEmpty()) return
        deadRemotes.forEach { moduleId ->
            log("[${session.traceId}] Pruning unhealthy conference peer $moduleId")
            removeConferenceParticipant(session, moduleId)
        }
        repairConferenceMeshAfterLeave(session)
    }

    /**
     * Conference prune invariant: invite==ACCEPTED, was connected in this session, and media long-failed.
     * Never prune invitees who have not completed a mesh link in this session (stale module ICE ignored).
     */
    private fun canPruneConferenceParticipant(
        session: TalkbackSession,
        moduleId: String,
        now: Long
    ): Boolean {
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
                getMeshEngine(moduleId) == null &&
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
                session.local,
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
            if (sessions.values.none { it.channelId == channelId && it.type == SessionType.CONFERENCE }) {
                leaveChannelMode(channelId)
            }
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

    private fun updateSessionReceivePlayback(session: TalkbackSession, reason: String = "refreshPlaybackState") {
        val enabled = when {
            session.isForegroundSuspended() -> false
            session.type == SessionType.UNICAST -> session.accepted
            session.type == SessionType.GROUP -> shouldEnableGroupReceivePlayback(session)
            session.type == SessionType.CONFERENCE -> session.accepted && !session.muted
            else -> false
        }
        setPlaybackEnabled(session, enabled, reason)
        verifyGroupPlaybackInvariant(session, enabled)
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
        if (owner == session.local) return false
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

    private fun log(message: String) {
        TalkbackLog.i(message)
        onLog?.invoke(message)
    }

    private fun runOnCoordinator(block: () -> Unit) {
        if (stopped || coordinatorExecutor.isShutdown) return
        runCatching {
            coordinatorExecutor.execute {
                if (stopped) return@execute
                try {
                    block()
                } catch (e: Exception) {
                    log("Coordinator error: ${e.message}")
                }
            }
        }.onFailure { log("Coordinator dispatch skipped: ${it.message}") }
    }

    private fun <T> runOnCoordinatorSync(block: () -> T): T {
        if (stopped || coordinatorExecutor.isShutdown) {
            throw IllegalStateException("Coordinator stopped")
        }
        return coordinatorExecutor.submit(block).get()
    }

}
