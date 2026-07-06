package com.talkback.appprod.runtime

import android.content.Context
import com.talkback.app.AudioEngineMode
import com.talkback.app.ConferenceInviteSnapshot
import com.talkback.app.RejoinableConferenceSnapshot
import com.talkback.app.TalkbackRuntime
import com.talkback.app.TalkbackRuntimeConfig
import com.talkback.core.util.TalkbackLog
import com.talkback.app.TalkbackRuntimeFactory
import com.talkback.app.TalkbackSessionSnapshot
import com.talkback.appprod.data.AppConfig
import com.talkback.appprod.data.AppConfigStore
import com.talkback.core.media.MediaTopologyPolicy
import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.EndpointId
import com.talkback.core.model.EndpointPriority
import com.talkback.core.model.ModuleId
import com.talkback.core.ptt.PttEmitTracer
import com.talkback.core.ptt.PttState
import com.talkback.core.qos.QosSnapshot
import com.talkback.core.session.ChannelMeshHostElection
import com.talkback.core.session.ChannelReadiness
import com.talkback.core.session.SessionType
import com.talkback.governance.transition.MeetingMode

data class ChannelMeetingQos(
    val networkLabel: String,
    val rttMs: Long?,
    val packetLossPercent: Double?
)

class TalkbackRuntimeManager(private val appContext: Context) {
    private val configStore = AppConfigStore(appContext)
    @Volatile
    private var runtime: TalkbackRuntime? = null
    @Volatile
    private var lastMeshCallAttemptMs = 0L
    @Volatile
    private var skipNextMeshBackoff = true
    @Volatile
    private var hadReachableTeammates = false
    @Volatile
    private var consecutiveWarmupFailures = 0
    @Volatile
    private var lastWarmupAttemptMs = 0L

    private companion object {
        const val MESH_CALL_BACKOFF_MS = 3_000L
        val WARMUP_BACKOFF_MS = longArrayOf(3_000L, 8_000L, 15_000L)
    }

    fun isRunning(): Boolean = runtime != null

    fun getRuntime(): TalkbackRuntime? = runtime

    fun modulePresenceSnapshot() = runtime?.modulePresenceSnapshot()

    fun acquireReleaseTimeoutMs(): Long =
        runtime?.acquireReleaseTimeoutMs() ?: 500L

    fun applyMeetingAutoJoinPolicy(meetingAutoJoin: Boolean) {
        runtime?.setAutoAcceptConferenceInvites(meetingAutoJoin)
    }

    fun setMeetingPreferred(preferred: Boolean, channelId: String? = null) {
        runtime?.setMeetingPreferred(preferred, channelId)
    }

    /** User-initiated meeting join should not be blocked by recent mesh backoff. */
    fun prioritizeNextMeshCall() {
        skipNextMeshBackoff = true
    }

    fun refreshStaleGroupSession(channelId: String) {
        runtime?.refreshStaleGroupSession(channelId)
    }

    fun refreshStaleConferenceSession(channelId: String) {
        runtime?.refreshStaleConferenceSession(channelId)
    }

    fun isConferenceHost(config: AppConfig): Boolean {
        val rt = runtime ?: return false
        return rt.isConferenceHostForChannel(config.defaultChannelId)
    }

    fun pendingConferenceInvite(channelId: String): ConferenceInviteSnapshot? =
        runtime?.pendingConferenceInvite(channelId)

    fun acceptPendingConferenceInvite(channelId: String): Boolean =
        runtime?.acceptPendingConferenceInvite(channelId) == true

    fun rejectPendingConferenceInvite(channelId: String, reason: String = "DECLINED"): Boolean =
        runtime?.rejectPendingConferenceInvite(channelId, reason) == true

    fun start(config: AppConfig): Result<Unit> {
        return runCatching {
            stop()
            val created = TalkbackRuntimeFactory.create(
                context = appContext,
                config = TalkbackRuntimeConfig(
                    localModuleId = ModuleId(config.moduleId.trim().uppercase()),
                    signalingPort = config.signalingPort,
                    autoAcceptIncoming = config.autoAcceptIncoming,
                    sessionIdleTimeoutMs = 120_000L,
                    cleanupIntervalMs = 5_000L,
                    heartbeatIntervalMs = 2_000L,
                    autoReDialOnModuleRecovery = config.autoRedial,
                    sharedSecret = config.sharedSecret,
                    allowedModuleIds = config.effectiveAllowedModuleIds(),
                    maxConferenceModules = MediaTopologyPolicy.DEFAULT_MAX_CONFERENCE_MODULES,
                    autoAcceptConferenceInvites = config.meetingAutoJoin,
                    acquireReleaseTimeoutMs = 500L
                ),
                mode = AudioEngineMode.REAL_WEBRTC,
                staticPeers = config.staticPeers(),
                onLog = { TalkbackLog.i(it) }
            )
            created.start()
            runtime = created
            skipNextMeshBackoff = true
            hadReachableTeammates = false
            lastMeshCallAttemptMs = 0L
            consecutiveWarmupFailures = 0
            lastWarmupAttemptMs = 0L
            bindLocalEndpoint(config)
        }.onFailure {
            stop()
        }
    }

    fun stop() {
        runtime?.stop()
        runtime = null
        skipNextMeshBackoff = true
        hadReachableTeammates = false
        lastMeshCallAttemptMs = 0L
        consecutiveWarmupFailures = 0
        lastWarmupAttemptMs = 0L
    }

    fun resetDiscovery() {
        runtime?.resetDiscovery()
    }

    private fun bindLocalEndpoint(config: AppConfig) {
        val endpointId = EndpointId(config.endpointId.trim().uppercase())
        runtime?.upsertLocalEndpoint(
            endpointId,
            "PrimaryHandset",
            online = true,
            priority = config.localPriority
        )
    }

    fun consumeFloorPreempted(sessionId: String): Boolean =
        runtime?.consumeFloorPreempted(sessionId) == true

    fun consumeAcquireTimedOut(sessionId: String): Boolean =
        runtime?.consumeAcquireTimedOut(sessionId) == true

    fun refreshLocalEndpoint(config: AppConfig) {
        bindLocalEndpoint(config)
    }

    fun localAddress(config: AppConfig): EndpointAddress =
        EndpointAddress(
            ModuleId(config.moduleId.trim().uppercase()),
            EndpointId(config.endpointId.trim().uppercase())
        )

    fun prepareChannelSession(config: AppConfig): String? {
        val rt = runtime ?: return null
        val channelId = config.defaultChannelId
        if (rt.pendingConferenceInvite(channelId) != null) return null
        rt.sessionSnapshotForChannel(channelId)?.let { return it.sessionId }
        return initiateChannelMeshCall(config, allowReplaceIdle = true)
    }

    fun initiateChannelMeshCall(
        config: AppConfig,
        allowReplaceIdle: Boolean = true
    ): String? {
        val rt = runtime ?: return null
        val channelId = config.defaultChannelId
        if (rt.pendingConferenceInvite(channelId) != null) return null
        val existing = rt.sessionSnapshotForChannel(channelId)
        if (existing != null) {
            val wantConference = config.isConferenceMode()
            val isConference = existing.type == SessionType.CONFERENCE
            if (wantConference == isConference) {
                if (isConference) {
                    if (existing.visibleParticipantCount <= 1) {
                        val invitees = conferenceRejoinInvitees(config)
                        if (!invitees.isNullOrEmpty()) {
                            val sent = rt.sendConferenceInvites(existing.sessionId, invitees)
                            if (sent > 0) {
                                val label = if (rt.isConferenceHostForChannel(channelId)) {
                                    "Solo host re-invites"
                                } else {
                                    "Conference rejoin"
                                }
                                TalkbackLog.i("$label sent=$sent on $channelId")
                            }
                        }
                    }
                    return existing.sessionId
                }
                rt.refreshStaleGroupSession(channelId)
                val reusable = rt.sessionSnapshotForChannel(channelId)
                if (reusable != null) {
                    if (rt.isChannelMediaReady(channelId) || rt.isChannelConnecting(channelId)) {
                        return reusable.sessionId
                    }
                    if (!allowReplaceIdle || !rt.isGroupSessionTrulyIdle(channelId)) {
                        return reusable.sessionId
                    }
                    TalkbackLog.i("Replacing idle GROUP session on $channelId before remesh")
                    rt.hangup(reusable.sessionId)
                }
            }
            if (isConference && !wantConference) {
                TalkbackLog.i("Blocked GROUP mesh while CONFERENCE active on $channelId")
                return null
            }
            rt.hangup(existing.sessionId)
        }

        val local = localAddress(config)
        val remotes = collectChannelRemotes(config)

        return if (config.isConferenceMode()) {
            noteReachableTeammatesIf(remotes != null)
            val now = System.currentTimeMillis()
            if (!skipNextMeshBackoff && now - lastMeshCallAttemptMs < MESH_CALL_BACKOFF_MS) {
                return null
            }
            skipNextMeshBackoff = false
            lastMeshCallAttemptMs = now
            if (!shouldLocalInitiateConference(config)) {
                return requestConferenceRejoin(config)
            }
            TalkbackLog.i("Conference solo host by ${local.moduleId.value} on $channelId")
            val inviteTargets = remotes?.map { it.endpointId }?.toSet() ?: emptySet()
            val meetingMode = if (remotes != null) MeetingMode.MULTI_PARTY else MeetingMode.SOLO_HOST
            if (!rt.submitMeetingStartIntent(channelId, meetingMode, inviteTargets)) {
                TalkbackLog.i("MEETING_START intent rejected on $channelId mode=$meetingMode")
                return null
            }
            val sessionId = rt.conferenceCall(local, emptyList(), channelId) ?: return null
            if (remotes != null) {
                val sent = rt.sendConferenceInvites(sessionId, remotes)
                TalkbackLog.i("Conference invites sent=$sent on $channelId")
            }
            sessionId
        } else {
            if (remotes == null) return null
            noteReachableTeammatesIf(true)
            val now = System.currentTimeMillis()
            if (!skipNextMeshBackoff && now - lastMeshCallAttemptMs < MESH_CALL_BACKOFF_MS) {
                return null
            }
            skipNextMeshBackoff = false
            lastMeshCallAttemptMs = now
            rt.reconcileGroupMesh(channelId)
            rt.sessionSnapshotForChannel(channelId)?.let { return it.sessionId }
            if (!isChannelMeshHost(config)) return null
            val pairwiseTargets = remotes.filter { local.moduleId.value < it.moduleId.value }
            if (pairwiseTargets.isEmpty()) return null
            TalkbackLog.i(
                "Group call initiated by ${local.moduleId.value} on $channelId " +
                    "pairwiseTargets=${pairwiseTargets.size}"
            )
            return runCatching {
                rt.groupCall(local, pairwiseTargets, channelId)
            }.onFailure { error ->
                TalkbackLog.w("Group call blocked on $channelId: ${error.message}")
            }.getOrNull()
        }
    }

    fun reconcileChannelMesh(config: AppConfig): String? {
        val rt = runtime ?: return null
        if (rt.activeUnicastSession() != null) return null
        if (!hasReachableTeammates(config)) return null
        val channelId = config.defaultChannelId
        rt.reconcileGroupMesh(channelId)
        return rt.sessionSnapshotForChannel(channelId)?.sessionId
    }

    private fun noteReachableTeammatesIf(hasTeammates: Boolean) {
        if (!hasTeammates) return
        if (!hadReachableTeammates) {
            hadReachableTeammates = true
            skipNextMeshBackoff = true
        }
    }

    fun conferenceAuthorityModuleId(config: AppConfig): String? {
        val rt = runtime ?: return null
        return rt.conferenceAuthorityModuleId(config.defaultChannelId)
    }

    fun shouldLocalInitiateConference(config: AppConfig): Boolean {
        val rt = runtime ?: return true
        return rt.shouldLocalInitiateConference(config.defaultChannelId)
    }

    private fun endpointForModule(moduleId: String): EndpointAddress? {
        val rt = runtime ?: return null
        if (!rt.isRemoteModuleDialable(moduleId)) return null
        val endpointId = rt.primaryEndpointIdForModule(moduleId) ?: "E01"
        return EndpointAddress(ModuleId(moduleId), EndpointId(endpointId))
    }

    /** Conference initiator re-invites everyone; participants only ping the meeting authority. */
    private fun conferenceRejoinInvitees(config: AppConfig): List<EndpointAddress>? {
        val rt = runtime ?: return null
        return if (rt.isConferenceHostForChannel(config.defaultChannelId)) {
            collectChannelRemotes(config)
        } else {
            conferenceAuthorityModuleId(config)?.let { endpointForModule(it) }?.let { listOf(it) }
        }
    }

    private fun requestConferenceRejoinFromAuthority(
        config: AppConfig,
        hint: RejoinableConferenceSnapshot? = null
    ): String? {
        val rt = runtime ?: return null
        val local = localAddress(config)
        val channelId = config.defaultChannelId
        val resolvedHint = hint ?: rt.rejoinableConference(channelId)
        val authorityModuleId = conferenceAuthorityModuleId(config)
            ?: resolvedHint?.hostModuleId
            ?: run {
                TalkbackLog.i("Conference rejoin skipped: no meeting authority on $channelId")
                return null
            }
        if (authorityModuleId == local.moduleId.value) return null
        val authorityEndpoint = endpointForModule(authorityModuleId) ?: run {
            TalkbackLog.i("Conference rejoin skipped: authority $authorityModuleId not dialable on $channelId")
            return null
        }
        val hostSessionId = resolvedHint?.hostSessionId.orEmpty()
        TalkbackLog.i(
            "Conference rejoin requested by ${local.moduleId.value} -> authority $authorityModuleId " +
                "session=$hostSessionId on $channelId"
        )
        if (!rt.sendConferenceRejoin(channelId, authorityEndpoint, hostSessionId)) return null
        return hostSessionId.ifBlank { "rejoin-pending" }
    }

    private fun collectChannelRemotes(config: AppConfig): List<EndpointAddress>? {
        val rt = runtime ?: return null
        val local = localAddress(config)
        val localModule = local.moduleId.value
        val remotes = LinkedHashMap<String, EndpointAddress>()
        config.staticPeers().forEach { entry ->
            val moduleId = entry.moduleId.value
            if (moduleId == localModule || moduleId in remotes) return@forEach
            val endpointId = rt.primaryEndpointIdForModule(moduleId) ?: "E01"
            remotes[moduleId] = EndpointAddress(ModuleId(moduleId), EndpointId(endpointId))
        }
        rt.remoteModuleStates().forEach { state ->
            val moduleId = state.presence.moduleId.value
            if (moduleId == localModule || moduleId in remotes) return@forEach
            if (!rt.isRemoteModuleDialable(moduleId)) return@forEach
            val endpointId = rt.primaryEndpointIdForModule(moduleId) ?: "E01"
            remotes[moduleId] = EndpointAddress(ModuleId(moduleId), EndpointId(endpointId))
        }
        if (remotes.isEmpty()) return null
        return remotes.values.toList()
    }

    fun activeChannelSession(config: AppConfig): TalkbackSessionSnapshot? {
        val rt = runtime ?: return null
        return rt.sessionSnapshotForChannel(config.defaultChannelId)
    }

    fun ensureChannelSession(config: AppConfig): String? {
        if (runtime?.activeUnicastSession() != null) return null
        return initiateChannelMeshCall(config, allowReplaceIdle = true)
    }

    /**
     * Participant rejoin: signal the meeting authority (conference initiator) only — never start a
     * competing solo conference that invites every peer on the channel.
     */
    fun requestConferenceRejoin(config: AppConfig): String? {
        if (runtime?.activeUnicastSession() != null) return null
        val channelId = config.defaultChannelId
        if (runtime?.pendingConferenceInvite(channelId) != null) return null
        val rejoinHint = runtime?.rejoinableConference(channelId)
        if (rejoinHint != null) {
            return requestConferenceRejoinFromAuthority(config, rejoinHint)
        }
        // Reuse an existing CONFERENCE session rather than creating a competing one.
        val existing = runtime?.sessionSnapshotForChannel(channelId)
        if (existing?.type == SessionType.CONFERENCE) {
            return initiateChannelMeshCall(config, allowReplaceIdle = false)
        }
        if (shouldLocalInitiateConference(config)) {
            return initiateChannelMeshCall(config, allowReplaceIdle = true)
        }
        return requestConferenceRejoinFromAuthority(config, null)
    }

    fun rejoinableConference(config: AppConfig): RejoinableConferenceSnapshot? =
        runtime?.rejoinableConference(config.defaultChannelId)

    fun isConferenceRejoinInProgress(config: AppConfig): Boolean =
        runtime?.isConferenceRejoinInProgress(config.defaultChannelId) == true

    fun isConferenceReconnecting(config: AppConfig): Boolean =
        runtime?.isConferenceReconnecting(config.defaultChannelId) == true

    fun isConferenceReconnectFailed(config: AppConfig): Boolean =
        runtime?.isConferenceReconnectFailed(config.defaultChannelId) == true

    fun hasRejoinableConference(config: AppConfig): Boolean = rejoinableConference(config) != null

    fun warmupChannel(config: AppConfig): String? {
        if (runtime?.activeUnicastSession() != null) return null
        if (!hasReachableTeammates(config)) return null
        if (!isWarmupBackoffElapsed()) return null
        lastWarmupAttemptMs = System.currentTimeMillis()
        val sessionId = reconcileChannelMesh(config)
            ?: if (isChannelMeshHost(config)) {
                initiateChannelMeshCall(config, allowReplaceIdle = false)
            } else {
                null
            }
        if (sessionId != null) {
            consecutiveWarmupFailures = 0
        } else if (!isChannelMeshHost(config)) {
            // Non-primary waits for bootstrap host — not a warmup failure.
        } else {
            consecutiveWarmupFailures = (consecutiveWarmupFailures + 1).coerceAtMost(WARMUP_BACKOFF_MS.size)
        }
        return sessionId
    }

    fun isWarmupBackoffElapsed(): Boolean {
        if (consecutiveWarmupFailures <= 0) return true
        val index = (consecutiveWarmupFailures - 1).coerceAtMost(WARMUP_BACKOFF_MS.lastIndex)
        val backoff = WARMUP_BACKOFF_MS[index]
        return System.currentTimeMillis() - lastWarmupAttemptMs >= backoff
    }

    fun isChannelMeshHost(config: AppConfig): Boolean {
        val local = localAddress(config).moduleId
        return ChannelMeshHostElection.isLocalHost(local, reachableModuleIds(config))
    }

    fun reachableModuleIds(config: AppConfig): Set<String> =
        collectChannelRemotes(config)?.map { it.moduleId.value }?.toSet() ?: emptySet()

    fun channelReadiness(
        config: AppConfig,
        meetingTabPreferred: Boolean
    ): ChannelReadiness {
        val rt = runtime ?: return ChannelReadiness.NO_SERVICE
        if (rt.pendingConferenceInvite(config.defaultChannelId) != null) {
            return ChannelReadiness.BLOCKED
        }
        if (meetingTabPreferred) {
            return ChannelReadiness.BLOCKED
        }
        val active = activeChannelSession(config)
        if (active?.type == SessionType.CONFERENCE) {
            return ChannelReadiness.BLOCKED
        }
        return rt.channelReadiness(config.defaultChannelId)
    }

    fun hasReachableTeammates(config: AppConfig): Boolean =
        collectChannelRemotes(config)?.isNotEmpty() == true

    fun isChannelMediaReady(config: AppConfig): Boolean {
        val rt = runtime ?: return false
        return rt.isChannelMediaReady(config.defaultChannelId)
    }

    fun isChannelConnecting(config: AppConfig): Boolean {
        val rt = runtime ?: return false
        return rt.isChannelConnecting(config.defaultChannelId)
    }

    fun isActiveConference(config: AppConfig): Boolean =
        activeChannelSession(config)?.type == SessionType.CONFERENCE

    fun meetingSpeakerAudioLevel(config: AppConfig, speakerEndpointKey: String): Float {
        val rt = runtime ?: return 0f
        return rt.meetingSpeakerAudioLevel(config.defaultChannelId, speakerEndpointKey)
    }

    fun pressPtt(sessionId: String): PttState {
        val config = configStore.load()
        val local = config.moduleId.trim().uppercase()
        PttEmitTracer.recordUiTrigger(sessionId, local, source = "RUNTIME_PRESS_PTT")
        val priority = config.localPriority
        val rt = runtime
        if (rt == null) {
            PttEmitTracer.recordBlocked(sessionId, local, "RUNTIME", "RUNTIME_NULL")
            return PttState.IDLE
        }
        return rt.pressPtt(sessionId, priority)
    }

    fun releasePtt(sessionId: String) {
        runtime?.releasePtt(sessionId)
    }

    fun localPttState(config: AppConfig): PttState {
        return activeChannelSession(config)?.localPttState ?: PttState.IDLE
    }

    fun placeCall(config: AppConfig, remote: EndpointAddress): Result<String> {
        val rt = runtime ?: return Result.failure(IllegalStateException("SERVICE_STOPPED"))
        return runCatching { rt.call(localAddress(config), remote) }
            .fold(
                onSuccess = { Result.success(it) },
                onFailure = { Result.failure(it) }
            )
    }

    fun activeUnicastSession(): TalkbackSessionSnapshot? =
        runtime?.activeUnicastSession()

    fun acceptCall(sessionId: String) {
        runCatching { runtime?.acceptCall(sessionId) }
    }

    fun rejectCall(sessionId: String) {
        runCatching { runtime?.rejectCall(sessionId) }
    }

    fun hangupCall(sessionId: String) {
        runCatching { runtime?.hangup(sessionId) }
    }

    fun leaveChannelSession(config: AppConfig) {
        val session = activeChannelSession(config) ?: return
        if (session.type == SessionType.CONFERENCE) {
            runCatching { runtime?.leaveConference(session.sessionId) }
        } else {
            hangupCall(session.sessionId)
        }
    }

    fun clearConferencePttCooldown(channelId: String) {
        runtime?.clearConferencePttCooldown(channelId)
    }

    fun endMeetingForAll(config: AppConfig) {
        val session = activeChannelSession(config) ?: return
        hangupCall(session.sessionId)
    }

    fun setCallMuted(sessionId: String, muted: Boolean) {
        runCatching { runtime?.setCallMuted(sessionId, muted) }
    }

    fun preparePttSession(config: AppConfig): String? = prepareChannelSession(config)

    fun initiateChannelGroupCall(config: AppConfig): String? = initiateChannelMeshCall(config)

    fun channelMeetingQos(config: AppConfig): ChannelMeetingQos? {
        val rt = runtime ?: return null
        val session = activeChannelSession(config) ?: return null
        if (session.type != SessionType.CONFERENCE) return null
        val localModule = config.moduleId.trim().uppercase()
        val snapshots = session.memberKeys
            .map(::moduleIdFromMemberKey)
            .filter { it != localModule }
            .mapNotNull { rt.qosSnapshotForModule(it) }
        return aggregateMeetingQos(rt.networkQualityLabel(), snapshots)
    }

    fun invitePeersToMeeting(config: AppConfig, moduleIds: List<String>): Result<Int> {
        val rt = runtime ?: return Result.failure(IllegalStateException("SERVICE_STOPPED"))
        val session = activeChannelSession(config)
            ?: return Result.failure(IllegalStateException("NO_MEETING"))
        if (session.type != SessionType.CONFERENCE) {
            return Result.failure(IllegalStateException("NOT_MEETING"))
        }
        val localModule = config.moduleId.trim().uppercase()
        val targetModules = LinkedHashSet<String>()
        session.memberKeys.forEach { targetModules.add(moduleIdFromMemberKey(it)) }
        moduleIds.map { it.trim().uppercase() }
            .filter { it.isNotEmpty() && it != localModule }
            .forEach { targetModules.add(it) }
        val newModules = moduleIds.map { it.trim().uppercase() }
            .filter { it.isNotEmpty() && it != localModule }
            .filter { moduleId ->
                session.memberKeys.none { moduleIdFromMemberKey(it) == moduleId }
            }
        if (newModules.isEmpty()) return Result.success(0)

        val remotes = collectRemotesForModules(config, newModules.toSet())
        if (remotes.isEmpty()) {
            return Result.failure(IllegalStateException("NO_PEERS"))
        }
        val sent = rt.sendConferenceInvites(session.sessionId, remotes)
        if (sent <= 0) {
            return Result.failure(IllegalStateException("INVITE_FAILED"))
        }
        TalkbackLog.i("Conference re-invite sent=$sent for modules=$newModules")
        return Result.success(sent)
    }

    private fun collectRemotesForModules(
        config: AppConfig,
        moduleIds: Set<String>
    ): List<EndpointAddress> {
        val rt = runtime ?: return emptyList()
        return moduleIds.mapNotNull { moduleId ->
            if (!rt.isRemoteModuleDialable(moduleId)) return@mapNotNull null
            val endpointId = rt.primaryEndpointIdForModule(moduleId) ?: return@mapNotNull null
            EndpointAddress(ModuleId(moduleId), EndpointId(endpointId))
        }
    }

    private fun aggregateMeetingQos(
        globalLabel: String,
        snapshots: List<QosSnapshot>
    ): ChannelMeetingQos {
        if (snapshots.isEmpty()) {
            return ChannelMeetingQos(globalLabel, null, null)
        }
        val rttValues = snapshots.map { it.rttMs }.filter { it >= 0 }
        val lossValues = snapshots.map { it.packetLossPercent }.filter { it >= 0.0 }
        val avgRtt = rttValues.average().takeIf { !it.isNaN() }?.toLong()
        val avgLoss = lossValues.average().takeIf { !it.isNaN() }
        val network = when {
            globalLabel == "Poor" -> "Poor"
            snapshots.any { it.iceState == "CONNECTED" } -> "Excellent"
            else -> globalLabel
        }
        return ChannelMeetingQos(network, avgRtt, avgLoss)
    }

    private fun moduleIdFromMemberKey(key: String): String {
        val dash = key.indexOf('-')
        return if (dash <= 0) key.uppercase() else key.substring(0, dash).uppercase()
    }
}
