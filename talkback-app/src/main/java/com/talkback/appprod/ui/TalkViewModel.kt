package com.talkback.appprod.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.talkback.app.TalkbackSessionSnapshot
import com.talkback.appprod.R
import com.talkback.appprod.TalkbackApp
import com.talkback.appprod.data.AppConfig
import com.talkback.appprod.data.AppConfigStore
import com.talkback.appprod.data.ChannelMode
import com.talkback.appprod.runtime.ChannelWarmupPolicy
import com.talkback.core.session.ChannelReadiness
import com.talkback.core.session.ConferenceRuntimePhase
import com.talkback.appprod.data.TaskProfile
import com.talkback.appprod.data.TaskProfileManager
import com.talkback.appprod.service.TalkbackForegroundService
import com.talkback.core.model.EndpointAddress
import com.talkback.core.model.EndpointId
import com.talkback.core.model.ModuleId
import com.talkback.core.ptt.PttEmitTracer
import com.talkback.core.ptt.PttState
import com.talkback.core.session.ConferenceParticipantViewState
import com.talkback.core.session.SessionType
import com.talkback.core.util.ChannelObservabilityLog
import com.talkback.core.util.TalkbackLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class TalkViewModel(
    private val appContext: Context,
    private val configStore: AppConfigStore
) : ViewModel() {
    private val app = TalkbackApp.get(appContext)
    private val manager get() = app.runtimeManager
    private val taskProfileManager = TaskProfileManager(appContext)

    private val _uiState = MutableStateFlow(buildIdleState(configStore.load()))
    val uiState: StateFlow<TalkUiState> = _uiState.asStateFlow()

    private var pollJob: Job? = null
    private var pollConsumerCount = 0
    private var serviceRunning = false
    private var serviceDetail = ""
    private val pttMutex = Mutex()
    @Volatile
    private var armedPttSessionId: String? = null
    @Volatile
    private var meetingStartedAtMs: Long? = null
    @Volatile
    private var wasConferenceActive = false
    @Volatile
    private var conferenceEndReason: ConferenceEndReason = ConferenceEndReason.NONE
    @Volatile
    private var lastSyncedMeetingPreferred: Boolean? = null
    /** Talk page PTT/Meeting tab selection — navigation only; does not drive input binding. */
    @Volatile
    private var userSelectedTab: UserSelectedTab = UserSelectedTab.PTT

    private val _openMeetingEvents = MutableSharedFlow<MeetingNavigation>(extraBufferCapacity = 1)
    val openMeetingEvents: SharedFlow<MeetingNavigation> = _openMeetingEvents.asSharedFlow()

    private val _toastMessageRes = MutableSharedFlow<Int>(extraBufferCapacity = 1)
    val toastMessageRes: SharedFlow<Int> = _toastMessageRes.asSharedFlow()

    init {
        MeetingPresenceDisplay.receivePathLivenessProvider =
            RuntimeReceivePathLivenessProvider { manager.getRuntime() }
        taskProfileManager.ensureInitialized()
        val config = configStore.load()
        if (config.isConferenceMode()) {
            configStore.save(config.copy(channelMode = ChannelMode.GROUP_PTT))
        }
    }

    fun listTaskProfiles(): List<TaskProfile> = taskProfileManager.listProfiles()

    fun activeTaskProfile(): TaskProfile? = taskProfileManager.activeProfile()

    fun switchTaskProfile(profileId: String): Result<TaskProfile> {
        val restart = serviceRunning
        return taskProfileManager.applyProfile(profileId, restartService = restart).also { result ->
            if (result.isSuccess) {
                viewModelScope.launch(Dispatchers.Default) { refreshInternal() }
            }
        }
    }

    fun startPolling() {
        pollConsumerCount++
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch(Dispatchers.Default) {
            while (isActive) {
                refreshInternal()
                delay(1_000L)
            }
        }
    }

    fun stopPolling() {
        if (pollConsumerCount > 0) pollConsumerCount--
        if (pollConsumerCount > 0) return
        pollJob?.cancel()
        pollJob = null
    }

    fun onServiceState(state: String, detail: String) {
        serviceRunning = state == TalkbackForegroundService.STATE_RUNNING
        serviceDetail = detail
        if (serviceRunning) {
            lastSyncedMeetingPreferred = null
        }
        viewModelScope.launch(Dispatchers.Default) { refreshInternal() }
    }

    fun syncServiceState() {
        val appRunning = TalkbackApp.get(appContext).serviceRunning
        val runtimeUp = manager.isRunning()
        serviceRunning = appRunning && runtimeUp
    }

    fun isServiceReady(): Boolean {
        syncServiceState()
        return serviceRunning && manager.getRuntime() != null
    }

    fun refresh() {
        viewModelScope.launch(Dispatchers.Default) { refreshInternal() }
    }

    private suspend fun refreshInternal() {
        syncServiceState()
        var config = configStore.load()
        var activeSession = if (serviceRunning && manager.getRuntime() != null) {
            manager.activeChannelSession(config)
        } else {
            null
        }
        manager.applyMeetingAutoJoinPolicy(config.meetingAutoJoin)
        val conferenceActive = activeSession?.type == SessionType.CONFERENCE
        syncMeetingPreferredToCoordinator(
            config,
            userSelectedTab == UserSelectedTab.MEETING || conferenceActive
        )
        if (serviceRunning && manager.getRuntime() != null && manager.activeUnicastSession() == null) {
            val channelId = config.defaultChannelId
            when (activeSession?.type) {
                SessionType.GROUP -> manager.refreshStaleGroupSession(channelId)
                SessionType.CONFERENCE -> manager.refreshStaleConferenceSession(channelId)
                else -> Unit
            }
            activeSession = manager.activeChannelSession(config)
            val meetingPreferred = userSelectedTab == UserSelectedTab.MEETING || conferenceActive
            if (ChannelWarmupPolicy.shouldWarmup(
                    config,
                    manager,
                    userSelectedTab == UserSelectedTab.PTT,
                    meetingPreferred,
                    manager.activeUnicastSession() != null,
                    activeSession
                )
            ) {
                runCatching { manager.warmupChannel(config) }
            } else if (
                userSelectedTab == UserSelectedTab.PTT &&
                !meetingPreferred &&
                activeSession == null &&
                manager.hasReachableTeammates(config)
            ) {
                runCatching { manager.ensureChannelSession(config) }
            }
        }
        config = configStore.load()
        activeSession = if (serviceRunning && manager.getRuntime() != null) {
            manager.activeChannelSession(config)
        } else {
            null
        }
        activeSession?.sessionId?.let { sessionId ->
            if (manager.consumeFloorPreempted(sessionId)) {
                _toastMessageRes.emit(R.string.ptt_floor_preempted)
            }
            if (manager.consumeAcquireTimedOut(sessionId)) {
                _toastMessageRes.emit(R.string.floor_acquire_timeout)
            }
        }
        val state = buildState(config)
        val endReason = if (wasConferenceActive && !state.conferenceActive) {
            val reason = conferenceEndReason
            conferenceEndReason = ConferenceEndReason.NONE
            if (reason != ConferenceEndReason.NONE) reason else ConferenceEndReason.REMOTE_ENDED
        } else {
            ConferenceEndReason.NONE
        }
        val displayState = if (endReason == ConferenceEndReason.REMOTE_ENDED) {
            returnTabToPttAfterRemoteMeetingEnd(config)
            buildState(config)
        } else {
            state
        }
        if (state.conferenceActive && !wasConferenceActive && config.meetingAutoJoin) {
            _openMeetingEvents.emit(MeetingNavigation.MAIN)
        }
        wasConferenceActive = state.conferenceActive
        _uiState.value = displayState.copy(conferenceEndReason = endReason)
    }

    fun isConferenceHost(): Boolean {
        syncServiceState()
        if (!serviceRunning || manager.getRuntime() == null) return false
        return manager.isConferenceHost(configStore.load())
    }

    suspend fun onPttDown(): PttDownResult = pttMutex.withLock {
        syncServiceState()
        val config = configStore.load()
        val local = config.moduleId.trim().uppercase()
        val initialSessionId = manager.activeChannelSession(config)?.sessionId ?: "ch:${config.defaultChannelId}"
        PttEmitTracer.recordUiTrigger(initialSessionId, local, source = "UI_ON_PTT_DOWN")
        if (!serviceRunning || manager.getRuntime() == null) {
            PttEmitTracer.recordBlocked(initialSessionId, local, "UI", "SERVICE_STOPPED")
            return PttDownResult.ServiceStopped
        }
        val beforeSession = manager.activeChannelSession(config)
        if (beforeSession?.type == SessionType.CONFERENCE) {
            PttEmitTracer.recordBlocked(
                beforeSession?.sessionId ?: initialSessionId,
                local,
                "UI",
                "MEETING_ACTIVE"
            )
            return PttDownResult.MeetingActive
        }
        manager.clearConferencePttCooldown(config.defaultChannelId)
        manager.prioritizeNextMeshCall()
        manager.refreshStaleGroupSession(config.defaultChannelId)
        runCatching { manager.ensureChannelSession(config) }
        val localKey = localEndpointKey(config)
        val before = manager.activeChannelSession(config) ?: run {
            PttEmitTracer.recordBlocked(initialSessionId, local, "UI", "NO_TEAMMATES")
            return PttDownResult.NoTeammates
        }
        floorBusySpeaker(before, localKey)?.let { busy ->
            PttEmitTracer.recordBlocked(before.sessionId, local, "UI", "FLOOR_BUSY", "speaker=$busy")
            return PttDownResult.FloorBusy(busy)
        }

        val sessionId = before.sessionId
        val ready = manager.isChannelMediaReady(config)
        manager.pressPtt(sessionId)
        val after = manager.activeChannelSession(config) ?: run {
            PttEmitTracer.recordBlocked(sessionId, local, "UI", "SESSION_LOST_AFTER_PRESS")
            return PttDownResult.NoTeammates
        }
        when (after.localPttState) {
            PttState.IDLE -> {
                floorBusySpeaker(after, localKey)?.let { busy ->
                    PttEmitTracer.recordBlocked(sessionId, local, "UI", "FLOOR_BUSY_AFTER_PRESS", "speaker=$busy")
                    return PttDownResult.FloorBusy(busy)
                }
            }
            PttState.REQUEST_FLOOR -> {
                armedPttSessionId = sessionId
                when (val decision = awaitFloorDecision(config, localKey)) {
                    is FloorDecision.Denied -> {
                        armedPttSessionId = null
                        return PttDownResult.FloorBusy(formatEndpointLabel(decision.ownerKey))
                    }
                    FloorDecision.Granted -> Unit
                    FloorDecision.Timeout, FloorDecision.Lost -> Unit
                }
            }
            PttState.TALK -> armedPttSessionId = sessionId
            else -> armedPttSessionId = sessionId
        }
        refreshInternal()
        return if (ready) PttDownResult.Ok else PttDownResult.Connecting
    }

    suspend fun onPttUp() = pttMutex.withLock {
        val config = configStore.load()
        if (config.isConferenceMode()) {
            return@withLock
        }
        val sessionId = armedPttSessionId ?: manager.activeChannelSession(config)?.sessionId
        armedPttSessionId = null
        if (sessionId != null) {
            manager.releasePtt(sessionId)
        }
        refreshInternal()
    }

    suspend fun toggleMeetingMute(): PttDownResult = pttMutex.withLock {
        toggleConferenceMute()
    }

    private suspend fun toggleConferenceMute(): PttDownResult {
        val config = configStore.load()
        val session = manager.activeChannelSession(config) ?: return PttDownResult.NoPeers
        if (session.type != SessionType.CONFERENCE) return PttDownResult.NoPeers
        val nextMuted = !session.muted
        manager.setCallMuted(session.sessionId, nextMuted, "toggleMeetingMute")
        refreshInternal()
        return if (manager.isChannelMediaReady(config)) PttDownResult.Ok else PttDownResult.Connecting
    }

    fun resetTalkTabToPtt() {
        if (userSelectedTab == UserSelectedTab.PTT) return
        userSelectedTab = UserSelectedTab.PTT
        viewModelScope.launch(Dispatchers.Default) { refreshInternal() }
    }

    private fun returnTabToPttAfterRemoteMeetingEnd(config: AppConfig) {
        if (userSelectedTab == UserSelectedTab.PTT) return
        userSelectedTab = UserSelectedTab.PTT
        lastSyncedMeetingPreferred = false
        manager.setMeetingPreferred(false, config.defaultChannelId)
    }

    fun selectPttMode() {
        viewModelScope.launch(Dispatchers.Default) {
            if (userSelectedTab == UserSelectedTab.PTT) {
                refreshInternal()
                return@launch
            }
            val config = configStore.load()
            val active = manager.activeChannelSession(config)
            userSelectedTab = UserSelectedTab.PTT
            lastSyncedMeetingPreferred = false
            manager.setMeetingPreferred(false, config.defaultChannelId)
            if (active?.type == SessionType.CONFERENCE) {
                _toastMessageRes.emit(R.string.meeting_ptt_tab_blocked)
                refreshInternal()
                return@launch
            }
            manager.refreshStaleGroupSession(config.defaultChannelId)
            refreshInternal()
        }
    }

    fun selectMeetingMode() {
        viewModelScope.launch(Dispatchers.Default) {
            val config = configStore.load()
            userSelectedTab = UserSelectedTab.MEETING
            lastSyncedMeetingPreferred = true
            manager.setMeetingPreferred(true, config.defaultChannelId)
            refreshInternal()
            val active = manager.activeChannelSession(config)
            if (active?.type == SessionType.CONFERENCE ||
                manager.pendingConferenceInvite(config.defaultChannelId) != null
            ) {
                _openMeetingEvents.emit(MeetingNavigation.MAIN)
            }
        }
    }

    fun requestMeetingScreen(target: MeetingNavigation = MeetingNavigation.MAIN) {
        viewModelScope.launch {
            _openMeetingEvents.emit(target)
        }
    }

    suspend fun joinMeeting(reason: String = "unspecified"): PttDownResult = pttMutex.withLock {
        syncServiceState()
        val config = configStore.load()
        val activeBefore = manager.activeChannelSession(config)
        ChannelObservabilityLog.joinMeetingTrace(
            reason = reason,
            channelId = config.defaultChannelId,
            talkTabMode = userSelectedTab.name,
            meetingPreferred = lastSyncedMeetingPreferred,
            coordinatorChannelMode = manager.getRuntime()?.channelMode(config.defaultChannelId)?.name,
            configChannelMode = config.channelMode.name,
            conferenceSessionActive = activeBefore?.type == SessionType.CONFERENCE
        )
        if (!serviceRunning || manager.getRuntime() == null) {
            return PttDownResult.ServiceStopped
        }
        val meetingConfig = meetingSessionConfig(config)
        lastSyncedMeetingPreferred = true
        manager.setMeetingPreferred(true, meetingConfig.defaultChannelId)
        manager.prioritizeNextMeshCall()
        var existing = manager.activeChannelSession(config)
        if (existing != null && existing.type != SessionType.CONFERENCE) {
            manager.leaveChannelSession(
                config,
                reason = "SWITCH_TO_CONFERENCE",
                caller = "TalkViewModel.joinMeeting"
            )
            existing = manager.activeChannelSession(config)
        }
        if (existing?.type == SessionType.CONFERENCE) {
            if (manager.isChannelMediaReady(config)) {
                refreshInternal()
                TalkbackLog.i(
                    "joinMeeting: reuse conference ready=true visible=${existing.visibleParticipantCount}"
                )
                return PttDownResult.Ok
            }
            TalkbackLog.i(
                "joinMeeting: conference connecting visible=${existing.visibleParticipantCount}, waiting for mesh"
            )
            if (manager.pendingConferenceInvite(meetingConfig.defaultChannelId) != null) {
                if (!acceptPendingOrRejoinConference(meetingConfig)) {
                    return PttDownResult.NoPeers
                }
            } else if (manager.hasRejoinableConference(meetingConfig)) {
                manager.requestConferenceRejoin(meetingConfig)
            } else if (manager.isConferenceReconnectFailed(meetingConfig)) {
                manager.requestConferenceRejoin(meetingConfig)
            } else if (manager.shouldLocalInitiateConference(meetingConfig)) {
                manager.ensureChannelSession(meetingConfig)
            } else {
                manager.requestConferenceRejoin(meetingConfig)
            }
            refreshInternal()
            val ready = manager.isChannelMediaReady(configStore.load())
            val visible = manager.activeChannelSession(configStore.load())?.visibleParticipantCount ?: 0
            TalkbackLog.i("joinMeeting: resume conference ready=$ready visible=$visible")
            return if (ready || manager.isConferenceHost(configStore.load())) {
                PttDownResult.Ok
            } else {
                PttDownResult.Connecting
            }
        }
        if (manager.pendingConferenceInvite(meetingConfig.defaultChannelId) != null) {
            if (!acceptPendingOrRejoinConference(meetingConfig)) {
                return PttDownResult.NoPeers
            }
        } else if (manager.hasRejoinableConference(meetingConfig)) {
            TalkbackLog.i("joinMeeting: silent rejoin into prior host conference")
            manager.requestConferenceRejoin(meetingConfig)
        } else if (manager.shouldLocalInitiateConference(meetingConfig)) {
            manager.ensureChannelSession(meetingConfig)
        } else {
            manager.requestConferenceRejoin(meetingConfig)
        }
        refreshInternal()
        val joined = manager.activeChannelSession(configStore.load())
        if (joined?.type != SessionType.CONFERENCE) {
            if (manager.isConferenceRejoinInProgress(meetingConfig)) {
                TalkbackLog.i("joinMeeting: rejoin in progress, waiting for host pull-in")
                return PttDownResult.Connecting
            }
            return PttDownResult.NoPeers
        }
        val ready = manager.isChannelMediaReady(configStore.load())
        val joinedRemotes = joined.visibleParticipantCount
        TalkbackLog.i("joinMeeting: new conference ready=$ready visible=$joinedRemotes")
        return if (ready || manager.isConferenceHost(configStore.load())) {
            PttDownResult.Ok
        } else {
            PttDownResult.Connecting
        }
    }

    fun leaveMeeting() {
        conferenceEndReason = ConferenceEndReason.USER_LEFT
        viewModelScope.launch(Dispatchers.Default) {
            val config = configStore.load()
            manager.leaveChannelSession(
                config,
                reason = "USER_LEAVE_MEETING",
                caller = "TalkViewModel.leaveMeeting"
            )
            manager.clearConferencePttCooldown(config.defaultChannelId)
            lastSyncedMeetingPreferred = false
            manager.setMeetingPreferred(false, config.defaultChannelId)
            manager.prioritizeNextMeshCall()
            runCatching { manager.ensureChannelSession(config) }
            refreshInternal()
        }
    }

    fun acceptIncomingMeeting() {
        viewModelScope.launch(Dispatchers.Default) {
            val config = configStore.load()
            val meetingConfig = meetingSessionConfig(config)
            userSelectedTab = UserSelectedTab.MEETING
            lastSyncedMeetingPreferred = true
            manager.setMeetingPreferred(true, meetingConfig.defaultChannelId)
            if (!acceptPendingOrRejoinConference(meetingConfig)) {
                _toastMessageRes.emit(R.string.meeting_invite_accept_failed)
                return@launch
            }
            refreshInternal()
            _openMeetingEvents.emit(MeetingNavigation.MAIN)
        }
    }

    fun rejectIncomingMeeting() {
        viewModelScope.launch(Dispatchers.Default) {
            manager.rejectPendingConferenceInvite(configStore.load().defaultChannelId)
            refreshInternal()
        }
    }

    fun meetingSessionId(): String? = _uiState.value.meeting.sessionId

    /** WebRTC audio level 0..1 for the current meeting center speaker. */
    fun meetingSpeakerAudioLevel(): Float {
        val state = _uiState.value
        if (!state.conferenceActive ||
            state.meeting.runtimePhase != ConferenceRuntimePhase.ACTIVE ||
            state.conferenceMuted
        ) {
            return 0f
        }
        val speaker = state.endpoints.firstOrNull { it.status == EndpointStatus.SPEAKING } ?: return 0f
        return manager.meetingSpeakerAudioLevel(configStore.load(), speaker.key)
    }

    fun endMeetingForAll() {
        conferenceEndReason = ConferenceEndReason.USER_LEFT
        viewModelScope.launch(Dispatchers.Default) {
            val config = configStore.load()
            manager.endMeetingForAll(config)
            manager.clearConferencePttCooldown(config.defaultChannelId)
            lastSyncedMeetingPreferred = false
            manager.setMeetingPreferred(false, config.defaultChannelId)
            manager.prioritizeNextMeshCall()
            runCatching { manager.ensureChannelSession(config) }
            refreshInternal()
        }
    }

    fun inviteMeetingMembers(moduleIds: List<String>): Result<Int> {
        val config = configStore.load()
        val result = manager.invitePeersToMeeting(config, moduleIds)
        if (result.isSuccess) {
            viewModelScope.launch(Dispatchers.Default) { refreshInternal() }
        }
        return result
    }

    fun inviteCandidates(): List<EndpointUiItem> {
        val state = _uiState.value
        val memberModules = state.meeting.memberKeys
            .map { key ->
                val dash = key.indexOf('-')
                if (dash <= 0) key.uppercase() else key.substring(0, dash).uppercase()
            }
            .toSet()
        return state.endpoints.filter { item ->
            if (item.isLocal) return@filter false
            val dash = item.key.indexOf('-')
            val module = if (dash <= 0) item.key.uppercase() else item.key.substring(0, dash).uppercase()
            item.status != EndpointStatus.OFFLINE && module !in memberModules
        }
    }

    fun setMeetingLocked(locked: Boolean) {
        val config = configStore.load()
        configStore.save(config.copy(meetingLocked = locked))
        refresh()
    }

    fun setMeetingAutoGain(enabled: Boolean) {
        val config = configStore.load()
        configStore.save(config.copy(meetingAutoGain = enabled))
        refresh()
    }

    fun setMeetingNoiseSuppression(enabled: Boolean) {
        val config = configStore.load()
        configStore.save(config.copy(meetingNoiseSuppression = enabled))
        refresh()
    }

    fun buildMeetingLink(): String {
        val config = configStore.load()
        val sessionId = meetingSessionId() ?: "pending"
        return "voxa://meeting/${config.defaultChannelId}/$sessionId"
    }

    fun initiateGroupCall(): String? {
        val config = configStore.load()
        return runCatching { manager.prepareChannelSession(config) }.getOrNull()
    }

    fun pressPtt(sessionId: String) {
        manager.pressPtt(sessionId)
    }

    fun releasePtt(sessionId: String) {
        manager.releasePtt(sessionId)
    }

    fun currentSessionId(): String? {
        val config = configStore.load()
        return manager.activeChannelSession(config)?.sessionId
    }

    fun preparePttSession(): String? = initiateGroupCall()

    fun placeCall(remoteKey: String): String? {
        val config = configStore.load()
        if (!isServiceReady()) {
            return "SERVICE_STOPPED"
        }
        val remote = endpointAddressFromKey(config, remoteKey) ?: return "INVALID_PEER"
        val result = manager.placeCall(config, remote)
        if (result.isSuccess) {
            viewModelScope.launch(Dispatchers.Default) { refreshInternal() }
            return null
        }
        val message = result.exceptionOrNull()?.message.orEmpty()
        return when {
            message.contains("BUSY_ACTIVE_SESSION", ignoreCase = true) ||
                message.contains("BUSY", ignoreCase = true) ||
                message.contains("session limit", ignoreCase = true) -> "BUSY"
            message.contains("not discovered", ignoreCase = true) -> "UNREACHABLE"
            else -> "FAILED"
        }
    }

    fun acceptIncomingCall() {
        val sessionId = manager.activeUnicastSession()?.sessionId ?: return
        viewModelScope.launch(Dispatchers.Default) {
            runCatching { manager.acceptCall(sessionId) }
            refreshInternal()
        }
    }

    fun rejectIncomingCall() {
        val sessionId = manager.activeUnicastSession()?.sessionId ?: return
        viewModelScope.launch(Dispatchers.Default) {
            runCatching { manager.rejectCall(sessionId) }
            refreshInternal()
        }
    }

    fun hangupActiveCall() {
        val sessionId = manager.activeUnicastSession()?.sessionId ?: return
        viewModelScope.launch(Dispatchers.Default) {
            runCatching { manager.hangupCall(sessionId) }
            refreshInternal()
        }
    }

    fun toggleCallMute() {
        val session = manager.activeUnicastSession() ?: return
        viewModelScope.launch(Dispatchers.Default) {
            runCatching { manager.setCallMuted(session.sessionId, !session.muted, "toggleCallMute") }
            refreshInternal()
        }
    }

    fun loadConfig(): AppConfig = configStore.load()

    private fun endpointAddressFromKey(config: AppConfig, key: String): EndpointAddress? {
        val dash = key.indexOf('-')
        if (dash <= 0) return null
        val moduleId = key.substring(0, dash).trim().uppercase()
        if (moduleId == config.moduleId.trim().uppercase()) return null
        val endpointId = key.substring(dash + 1).trim().uppercase()
        if (endpointId == "—" || endpointId == "?") {
            val rt = manager.getRuntime() ?: return null
            val resolved = rt.primaryEndpointIdForModule(moduleId) ?: return null
            return EndpointAddress(ModuleId(moduleId), EndpointId(resolved))
        }
        return EndpointAddress(ModuleId(moduleId), EndpointId(endpointId))
    }

    private fun meetingSessionConfig(config: AppConfig): AppConfig =
        config.copy(channelMode = ChannelMode.CONFERENCE)

    /** Accept a pending host invite, or signal the host to pull us in if the invite timed out. */
    private fun acceptPendingOrRejoinConference(meetingConfig: AppConfig): Boolean {
        val channelId = meetingConfig.defaultChannelId
        if (manager.pendingConferenceInvite(channelId) != null) {
            if (manager.acceptPendingConferenceInvite(channelId)) return true
            TalkbackLog.i("Pending conference accept failed on $channelId, trying rejoin")
        }
        return manager.requestConferenceRejoin(meetingConfig) != null
    }

    private fun syncMeetingPreferredToCoordinator(config: AppConfig, preferred: Boolean) {
        if (lastSyncedMeetingPreferred == preferred) return
        lastSyncedMeetingPreferred = preferred
        manager.setMeetingPreferred(preferred, config.defaultChannelId)
    }

    private fun conferenceModePreferred(config: AppConfig): Boolean =
        userSelectedTab == UserSelectedTab.MEETING ||
            manager.pendingConferenceInvite(config.defaultChannelId) != null

    private fun localEndpointKey(config: AppConfig): String = EndpointAddress(
        ModuleId(config.moduleId),
        EndpointId(config.endpointId)
    ).key

    private fun floorBusySpeaker(
        session: com.talkback.app.TalkbackSessionSnapshot,
        localKey: String
    ): String? {
        val ownerKey = session.protocolFloorOwnerKey ?: return null
        if (ownerKey == localKey) return null
        if (session.localPttState == PttState.TALK) return null
        return moduleLabel(ownerKey)
    }

    private sealed class FloorDecision {
        data object Granted : FloorDecision()
        data class Denied(val ownerKey: String) : FloorDecision()
        data object Timeout : FloorDecision()
        data object Lost : FloorDecision()
    }

    private suspend fun awaitFloorDecision(
        config: AppConfig,
        localKey: String,
        timeoutMs: Long = 500L
    ): FloorDecision {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            delay(30L)
            val session = manager.activeChannelSession(config) ?: return FloorDecision.Lost
            when (session.localPttState) {
                PttState.TALK -> return FloorDecision.Granted
                PttState.IDLE -> {
                    val ownerKey = session.protocolFloorOwnerKey
                    return if (ownerKey != null && ownerKey != localKey) {
                        FloorDecision.Denied(ownerKey)
                    } else {
                        FloorDecision.Timeout
                    }
                }
                else -> Unit
            }
        }
        return FloorDecision.Timeout
    }

    private fun profileUiFields(): Pair<String, String> {
        val profile = taskProfileManager.activeProfile()
        return (profile?.name ?: "") to (profile?.rfKeyLabel ?: "")
    }

    private fun buildIdleState(config: AppConfig): TalkUiState {
        val (taskName, rfLabel) = profileUiFields()
        return TalkUiState(
        serviceRunning = false,
        serviceDetail = "",
        taskProfileName = taskName,
        rfKeyLabel = rfLabel,
        channelTitle = config.channelTitle(),
        channelSubtitle = taskName.ifBlank { config.channelDisplayName },
        onlineCount = 0,
        floorOwner = "--",
        localEndpointKey = localEndpointKey(config),
        talking = "--",
        networkLabel = "N/A",
        sessionActive = false,
        pttActive = false,
        endpoints = emptyList()
        )
    }

    private fun buildState(config: AppConfig): TalkUiState {
        val (taskName, rfLabel) = profileUiFields()
        val runtime = manager.getRuntime()
        if (!serviceRunning || runtime == null) {
            return buildIdleState(config).copy(
                serviceRunning = serviceRunning,
                serviceDetail = serviceDetail
            )
        }

        val session = manager.activeChannelSession(config)
        val conferenceActive = session?.type == SessionType.CONFERENCE
        val conferenceMode = userSelectedTab == UserSelectedTab.MEETING
        val conferenceMuted = session?.muted == true
        val localRawKey = EndpointAddress(
            ModuleId(config.moduleId),
            EndpointId(config.endpointId)
        ).key
        val floorOwnerKey = session?.protocolFloorOwnerKey
        val localPttActive = session?.localPttState == PttState.TALK ||
            session?.localPttState == PttState.REQUEST_FLOOR
        // Control plane: who holds the floor, derived ONLY from floor protocol (never ICE).
        val floorPresentation = buildFloorPresentation(config, session, localRawKey, conferenceActive)
        val floorOwner = when {
            conferenceActive -> appContext.getString(R.string.conference_floor_all)
            floorPresentation is FloorPresentation.Acquiring -> {
                if (floorPresentation.degraded) {
                    appContext.getString(R.string.floor_acquiring_degraded)
                } else {
                    appContext.getString(R.string.floor_acquiring)
                }
            }
            floorPresentation is FloorPresentation.Speaking -> moduleLabel(floorPresentation.moduleId)
            else -> "--"
        }
        val talking = when {
            conferenceActive && conferenceMuted -> appContext.getString(R.string.conference_status_muted)
            conferenceActive && manager.isChannelMediaReady(config) -> appContext.getString(R.string.conference_status_live)
            conferenceActive -> appContext.getString(R.string.conference_status_connecting)
            floorPresentation is FloorPresentation.Acquiring -> moduleLabel(localRawKey)
            localPttActive -> moduleLabel(localRawKey)
            floorPresentation is FloorPresentation.Speaking -> moduleLabel(floorPresentation.moduleId)
            else -> "--"
        }

        // UI identity is the module: endpoint keys never participate in matching.
        val speakingModuleId = when {
            conferenceActive && !conferenceMuted && manager.isChannelMediaReady(config) ->
                resolveConferenceSpeakingKey(config, localRawKey, session)?.let { moduleIdFromKey(it) }
            localPttActive -> moduleIdFromKey(localRawKey)
            floorPresentation is FloorPresentation.Speaking -> floorPresentation.moduleId
            else -> null
        }
        val localLanOnline = serviceRunning && LocalNetworkHelper.hasLanLink(appContext)
        val endpoints = buildEndpointList(
            config,
            localRawKey,
            speakingModuleId,
            localLanOnline,
            conferenceMuted
        )

        val unicast = manager.activeUnicastSession()
        val callState = if (unicast != null) {
            val remoteKey = unicast.remoteKey
            val moduleId = remoteKey?.let { moduleIdFromKey(it) }
            val qos = moduleId?.let { runtime.qosSnapshotForModule(it) }
            CallUiState(
                active = true,
                sessionId = unicast.sessionId,
                remoteKey = remoteKey,
                remoteLabel = remoteKey?.let(::formatEndpointLabel),
                teamName = config.channelDisplayName,
                phase = unicast.callPhase,
                localInitiated = unicast.localInitiated,
                muted = unicast.muted,
                networkLabel = runtime.conferenceNetworkIndicator().toQualityLabel(),
                rttMs = qos?.rttMs?.takeIf { it >= 0 },
                packetLossPercent = qos?.packetLossPercent?.takeIf { it >= 0.0 }
            )
        } else {
            CallUiState()
        }

        val channelReady = manager.isChannelMediaReady(config)
        val channelConnecting = !channelReady && manager.isChannelConnecting(config)
        val channelReadiness = manager.channelReadiness(
            config,
            meetingTabPreferred = conferenceModePreferred(config)
        )
        val channelAwaitingHost =
            channelReadiness == ChannelReadiness.AWAITING_PRIMARY && session == null

        val runtimePhase = session?.conferenceRuntimeState?.phase
        val conferenceMediaLive = conferenceActive && channelReady

        if (conferenceMediaLive) {
            if (meetingStartedAtMs == null) {
                meetingStartedAtMs = System.currentTimeMillis()
            }
        } else if (!conferenceActive) {
            meetingStartedAtMs = null
        }
        val meetingQos = if (conferenceActive) manager.channelMeetingQos(config) else null
        val pendingInvite = manager.pendingConferenceInvite(config.defaultChannelId)
        val incomingMeetingInvite = pendingInvite?.let { invite ->
            IncomingMeetingInviteUi(
                sessionId = invite.sessionId,
                hostLabel = formatEndpointLabel(invite.hostKey),
                channelTitle = config.channelTitle()
            )
        }
        val rejoinableMeeting = if (!conferenceActive && incomingMeetingInvite == null) {
            manager.rejoinableConference(config)?.let { hint ->
                RejoinableMeetingUi(
                    hostSessionId = hint.hostSessionId,
                    hostLabel = formatEndpointLabel(hint.hostKey),
                    channelTitle = config.channelTitle()
                )
            }
        } else {
            null
        }
        val conferenceRejoinInProgress = manager.isConferenceRejoinInProgress(config)
        val conferenceReconnecting = conferenceActive &&
            runtimePhase == ConferenceRuntimePhase.RECOVERING &&
            !manager.isConferenceReconnectFailed(config)
        val conferenceReconnectFailed = conferenceActive &&
            runtimePhase == ConferenceRuntimePhase.RECOVERING &&
            manager.isConferenceReconnectFailed(config)
        val effectiveInteractionMode = EffectiveInteractionModeResolver.resolve(
            conferenceActive = conferenceActive,
            runtimePhase = runtimePhase,
            hasPendingInvite = pendingInvite != null
        )
        val primaryInteractionAction = PrimaryInteractionActionResolver.resolve(
            userSelectedTab = userSelectedTab,
            effectiveInteractionMode = effectiveInteractionMode
        )

        if (conferenceMediaLive) {
            val presence = session?.conferencePresenceProjection
            val connected = presence?.connectedCount ?: session?.visibleParticipantCount ?: 0
            val joined = presence?.joinedCount ?: session?.joinedParticipantCount ?: 0
            val recovering = presence?.recoveringPeers?.joinToString(",") ?: ""
            val awaiting = session?.awaitingAdditionalParticipants == true
            val display = MeetingPresenceDisplay.participantCountLabel(joined)
            val connectingHint = meetingAvailabilityHint(session, speakingModuleId, conferenceMuted)
            TalkbackLog.i(
                "Meeting pill: display=$display connected=$connected joined=$joined " +
                    "connectingHint=$connectingHint recovering=[$recovering] awaiting=$awaiting " +
                    "phase=$runtimePhase channelReady=$channelReady"
            )
        }
        if (conferenceActive && session != null) {
            logReachabilityProbe(session, speakingModuleId, conferenceMuted, localRawKey)
        }

        val conferencePresence = session?.conferencePresenceProjection
        val meetingConnectedCount = conferencePresence?.connectedCount
            ?: session?.visibleParticipantCount
            ?: 0
        val meetingJoinedCount = conferencePresence?.joinedCount
            ?: session?.joinedParticipantCount
            ?: 0
        val meetingRecoveringPeers = conferencePresence?.recoveringPeers ?: emptySet()
        val meetingParticipantLabel = MeetingPresenceDisplay.participantCountLabel(meetingJoinedCount)
        val awaitingAdditionalParticipants = session?.awaitingAdditionalParticipants == true
        val connectingParticipantHint = if (conferenceMediaLive && awaitingAdditionalParticipants) {
            meetingAvailabilityHint(session, speakingModuleId, conferenceMuted)
        } else {
            null
        }
        val meetingNetworkLabel = meetingQos?.networkLabel
            ?: runtime.conferenceNetworkIndicator().toQualityLabel()
        val conferenceDisplay = ConferenceDisplayStateResolver.resolve(
            lifecycle = ConferenceLifecycleFacts(
                conferenceActive = conferenceActive,
                conferenceMode = conferenceMode,
                sessionActive = session != null,
                runtimePhase = runtimePhase
            ),
            connectivity = ConferenceConnectivityFacts(
                channelReady = channelReady,
                channelConnecting = channelConnecting,
                reconnecting = conferenceReconnecting,
                reconnectFailed = conferenceReconnectFailed,
                awaitingRejoin = conferenceMode && !conferenceActive
            ),
            membership = ConferenceMembershipFacts(
                awaitingAdditionalParticipants = awaitingAdditionalParticipants,
                connectingParticipantHint = connectingParticipantHint
            ),
            muted = conferenceMuted,
            poorNetwork = meetingNetworkLabel == "Poor" || runtime.conferenceNetworkIndicator().toQualityLabel() == "Poor"
        )

        return TalkUiState(
            serviceRunning = true,
            serviceDetail = serviceDetail,
            taskProfileName = taskName,
            rfKeyLabel = rfLabel,
            channelTitle = config.channelTitle(),
            channelSubtitle = if (taskName.isNotBlank()) taskName else config.channelDisplayName,
            onlineCount = if (conferenceActive) {
                meetingJoinedCount.coerceAtLeast(1)
            } else {
                endpoints.count { it.status != EndpointStatus.OFFLINE }
            },
            floorOwner = floorOwner,
            floorOwnerKey = floorOwnerKey,
            floorPresentation = floorPresentation,
            localEndpointKey = localRawKey,
            talking = talking,
            networkLabel = runtime.conferenceNetworkIndicator().toQualityLabel(),
            sessionActive = session != null,
            pttActive = if (conferenceActive) {
                conferenceMuted
            } else {
                session?.localPttState == PttState.TALK || session?.localPttState == PttState.REQUEST_FLOOR
            },
            localTransmitting = !conferenceActive && session?.localPttState == PttState.TALK,
            channelReady = channelReady,
            channelConnecting = channelConnecting,
            channelReadiness = channelReadiness,
            channelAwaitingHost = channelAwaitingHost,
            userSelectedTab = userSelectedTab,
            effectiveInteractionMode = effectiveInteractionMode,
            primaryInteractionAction = primaryInteractionAction,
            conferenceMode = conferenceMode,
            conferenceActive = conferenceActive,
            conferenceMuted = conferenceMuted,
            incomingMeetingInvite = incomingMeetingInvite,
            rejoinableMeeting = rejoinableMeeting,
            conferenceRejoinInProgress = conferenceRejoinInProgress,
            conferenceReconnecting = conferenceReconnecting,
            conferenceReconnectFailed = conferenceReconnectFailed,
            channelMemberModuleIds = session?.channelMemberModuleIds.orEmpty(),
            sessionMemberKeys = session?.memberKeys.orEmpty(),
            endpoints = endpoints,
            call = callState,
            meeting = MeetingUiState(
                sessionId = session?.sessionId?.takeIf { conferenceActive },
                memberKeys = session?.memberKeys.orEmpty(),
                visibleParticipantCount = session?.visibleParticipantCount ?: 0,
                joinedParticipantCount = meetingJoinedCount,
                connectedParticipantCount = meetingConnectedCount,
                recoveringPeers = meetingRecoveringPeers,
                participantCountLabel = meetingParticipantLabel,
                connectingParticipantHint = connectingParticipantHint,
                awaitingAdditionalParticipants = awaitingAdditionalParticipants,
                runtimePhase = runtimePhase,
                startedAtMs = meetingStartedAtMs,
                networkLabel = meetingNetworkLabel,
                rttMs = meetingQos?.rttMs,
                packetLossPercent = meetingQos?.packetLossPercent,
                autoGain = config.meetingAutoGain,
                noiseSuppression = config.meetingNoiseSuppression,
                locked = config.meetingLocked
            ),
            conferenceDisplay = conferenceDisplay
        )
    }

    private fun buildEndpointList(
        config: AppConfig,
        localRawKey: String,
        speakingModuleId: String?,
        localLanOnline: Boolean,
        conferenceMuted: Boolean
    ): List<EndpointUiItem> {
        val runtime = manager.getRuntime() ?: return emptyList()
        val items = mutableListOf<EndpointUiItem>()
        val seenKeys = mutableSetOf<String>()
        val seenModules = mutableSetOf<String>()

        fun addItem(item: EndpointUiItem) {
            if (seenKeys.add(item.key)) {
                items += item
            }
        }

        val session = manager.activeChannelSession(config)
        val conferenceActive = session?.type == SessionType.CONFERENCE
        val roster = runtime.peerDisplayRoster()
        val rosterOnlineByModule = roster.associate { moduleIdFromKey(it.endpointKey) to it.online }

        fun addMemberKey(key: String) {
            if (key == localRawKey) return
            val moduleId = moduleIdFromKey(key)
            if (moduleId in seenModules) return
            seenModules.add(moduleId)
            val online = rosterOnlineByModule[moduleId] ?: false
            val displayKey = roster.firstOrNull { moduleIdFromKey(it.endpointKey) == moduleId }?.endpointKey
                ?: runtime.primaryEndpointIdForModule(moduleId)?.let { "$moduleId-$it" }
                ?: key
            addItem(endpointItem(displayKey, online, speakingModuleId, localRawKey))
        }

        val recoveringPeers = session?.conferencePresenceProjection?.recoveringPeers.orEmpty()

        if (conferenceActive) {
            val visible = session?.visibleParticipants.orEmpty()
            val sessionId = session?.sessionId.orEmpty()
            if (visible.isNotEmpty()) {
                visible.forEach { participant ->
                    val moduleId = participant.moduleId
                    if (moduleId in seenModules) return@forEach
                    seenModules.add(moduleId)
                    val displayKey = roster.firstOrNull { moduleIdFromKey(it.endpointKey) == moduleId }?.endpointKey
                        ?: runtime.primaryEndpointIdForModule(moduleId)?.let { "$moduleId-$it" }
                        ?: participant.key
                    addItem(
                        conferenceVisibleEndpointItem(
                            sessionId,
                            displayKey,
                            participant,
                            speakingModuleId,
                            edgeRecoveringPeer(runtime, sessionId, moduleId),
                            edgeMediaUnavailablePeer(runtime, sessionId, moduleId),
                            conferenceMuted
                        )
                    )
                }
            } else {
                addItem(
                    EndpointUiItem(
                        key = localRawKey,
                        displayLabel = localYouLabel(localRawKey),
                        status = if (localLanOnline) EndpointStatus.ONLINE else EndpointStatus.OFFLINE,
                        signalBars = if (localLanOnline) 3 else 0,
                        isLocal = true
                    )
                )
            }
        } else {
            val localModuleId = moduleIdFromKey(localRawKey)
            addItem(
                EndpointUiItem(
                    key = localRawKey,
                    displayLabel = localYouLabel(localRawKey),
                    status = when {
                        !localLanOnline -> EndpointStatus.OFFLINE
                        speakingModuleId == localModuleId -> EndpointStatus.SPEAKING
                        else -> EndpointStatus.ONLINE
                    },
                    signalBars = if (localLanOnline) 3 else 0,
                    isLocal = true
                )
            )
            seenModules.add(localModuleId)
            roster.forEach { row ->
                val moduleId = moduleIdFromKey(row.endpointKey)
                if (moduleId in seenModules) return@forEach
                seenModules.add(moduleId)
                addItem(endpointItem(row.endpointKey, row.online, speakingModuleId, localRawKey))
            }
            session?.memberKeys?.forEach(::addMemberKey)
        }

        return items
    }

    /**
     * Build the floor presentation from the control plane only.
     *
     * Per Presentation Rule #5, ownership comes exclusively from
     * [TalkbackSessionSnapshot.protocolFloorOwnerKey];
     * reachability is an independent media-plane attribute fetched via the Runtime semantic API
     * ([TalkbackRuntime.isCurrentSpeakerReachable]) and never used to suppress a present owner.
     * Conference active-speaker is a different (multi-speaker) model and is not represented here.
     */
    private fun buildFloorPresentation(
        config: AppConfig,
        session: com.talkback.app.TalkbackSessionSnapshot?,
        localRawKey: String,
        conferenceActive: Boolean
    ): FloorPresentation {
        if (conferenceActive) return FloorPresentation.Idle
        val localModuleId = moduleIdFromKey(localRawKey)
        if (session?.localPttState == PttState.REQUEST_FLOOR &&
            session.protocolFloorOwnerKey != localRawKey
        ) {
            return FloorPresentation.Requesting
        }
        val ownerKey = session?.protocolFloorOwnerKey ?: return FloorPresentation.Idle
        val ownerModuleId = moduleIdFromKey(ownerKey)
        if (ownerModuleId == localModuleId) {
            val uplinkGrant = manager.modulePresenceSnapshot()?.localUplinkGrant ?: false
            if (!uplinkGrant) {
                val degraded = !manager.isChannelMediaReady(config)
                return FloorPresentation.Acquiring(degraded = degraded)
            }
        }
        val reachable = if (ownerModuleId == localModuleId) {
            true
        } else {
            manager.getRuntime()?.isCurrentSpeakerReachable(config.defaultChannelId) ?: false
        }
        return FloorPresentation.Speaking(ownerModuleId, reachable)
    }

    /** UI label for a floor/endpoint key: always the module, never the endpoint. */
    private fun moduleLabel(key: String): String = moduleIdFromKey(key)

    private fun resolveConferenceSpeakingKey(
        config: AppConfig,
        localRawKey: String,
        session: TalkbackSessionSnapshot?
    ): String? {
        val threshold = 0.02f
        val keys = LinkedHashSet<String>()
        keys.add(localRawKey)
        session?.memberKeys.orEmpty().forEach { keys.add(it) }
        session?.visibleParticipants.orEmpty().forEach { keys.add(it.key) }
        return keys
            .mapNotNull { key ->
                val level = manager.meetingSpeakerAudioLevel(config, key)
                if (level >= threshold) key to level else null
            }
            .maxByOrNull { it.second }
            ?.first
    }

    private fun localYouLabel(key: String): String =
        "${moduleLabel(key)} ${appContext.getString(R.string.you_suffix)}"

    private fun isLocalEndpointKey(key: String, localRawKey: String): Boolean =
        moduleIdFromKey(key).equals(moduleIdFromKey(localRawKey), ignoreCase = true)

    fun teamDisplayName(): String = configStore.load().channelDisplayName

    private fun moduleIdFromKey(key: String): String {
        val dash = key.indexOf('-')
        return if (dash <= 0) key.uppercase() else key.substring(0, dash).uppercase()
    }

    private fun formatEndpointLabel(key: String): String {
        val dash = key.indexOf('-')
        if (dash <= 0 || dash >= key.length - 1) return key
        val module = key.substring(0, dash)
        val endpoint = key.substring(dash + 1)
        if (endpoint == "—" || endpoint == "?") return module
        return "$module / $endpoint"
    }

    // [DEBUG-rprobe] Diagnostic instrumentation — see logReachabilityProbe.

    companion object {
        /**
         * [DEBUG-rprobe] Reachability probe switch — ADR-0030 reference instrument.
         * Resident diagnostic for presence projection issues (mesh pruning, anchor
         * failover, backup standby). Keep OFF in normal builds; flip to true when
         * diagnosing presence/reachability regressions.
         */
        private const val ENABLE_REACHABILITY_PROBE = true
    }

    /**
     * [DEBUG-rprobe] REACHABILITY_PROBE — temporary instrumentation to split Bug B fork A
     * (media staleness: ICE lost but receivePathLive still true) from fork B (projection
     * drops the module from visibleParticipants). Iterates the canonical roster, NOT
     * visibleParticipants, so a dropped module still gets a probe line with visible=false.
     * Read-only; no behavior change. Remove via grep DEBUG-rprobe.
     */
    private fun logReachabilityProbe(
        session: TalkbackSessionSnapshot,
        speakingModuleId: String?,
        conferenceMuted: Boolean,
        localRawKey: String
    ) {
        if (!ENABLE_REACHABILITY_PROBE) return
        val runtime = manager.getRuntime() ?: return
        val localModuleId = moduleIdFromKey(localRawKey)
        val presence = session.conferencePresenceProjection
        val recoveringPeers = presence?.recoveringPeers.orEmpty()
        val everConnectedModules = session.conferenceEverConnectedModuleIds
        val visibleByModule = session.visibleParticipants.associateBy { it.moduleId }
        val rosterModules = session.memberKeys.map(::moduleIdFromKey).toSet()
        // Canonical iteration domain: roster ∪ visible ∪ recovering ∪ transportEverConnected,
        // so no layer can hide a module from the probe.
        val allModules = rosterModules + visibleByModule.keys + recoveringPeers + everConnectedModules
        val authorityId = session.channelId?.let { runtime.conferenceAuthorityModuleId(it) }
        val authorityReachable = runtime.conferenceAuthorityReachable(session.sessionId)
        val hostIce = authorityId?.let { runtime.qosSnapshotForModule(it)?.iceState } ?: "-"
        allModules.sorted().forEach { moduleId ->
            if (moduleId == localModuleId) return@forEach
            val rosterContains = moduleId in rosterModules
            val participantRecordExists =
                runtime.conferenceParticipantRecordExists(session.sessionId, moduleId)
            val rawMedia = runtime.conferenceParticipantMedia(session.sessionId, moduleId)
            val iceConnectionState = runtime.qosSnapshotForModule(moduleId)?.iceState ?: "UNKNOWN"
            val sessionEdgeRecovering = session.conferenceRuntimeState?.edgeRecovering == true
            val recoveringPeer = edgeRecoveringPeer(runtime, session.sessionId, moduleId)
            val mediaUnavailablePeer = edgeMediaUnavailablePeer(runtime, session.sessionId, moduleId)
            val transportEverConnected = moduleId in everConnectedModules
            val mediaEverLive = runtime.mediaEverLive(session.sessionId, moduleId)
            val receivePathLive = runtime.receivePathLive(session.sessionId, moduleId)
            val edgeLineage = runtime.conferenceEdgeRecoveryLineage(session.sessionId, moduleId)
            val edgeRecoveryPhase = edgeLineage?.phase?.name ?: "-"
            val obligationOpen = edgeLineage?.obligationOpen ?: false
            val viewState = visibleByModule[moduleId]
            val finalPresence = if (viewState != null) {
                MeetingPresenceDisplay.resolveParticipantPresentation(
                    participantPresentationFacts(
                        session.sessionId,
                        viewState,
                        speakingModuleId,
                        recoveringPeer,
                        mediaUnavailablePeer,
                        conferenceMuted
                    )
                ).endpointStatus.name
            } else {
                "NOT_PROJECTED"
            }
            TalkbackLog.i(
                "[DEBUG-rprobe] REACHABILITY_PROBE session=${session.sessionId}" +
                    " authorityId=${authorityId ?: "-"}" +
                    " authorityReachable=$authorityReachable" +
                    " hostIce=$hostIce" +
                    " module=$moduleId" +
                    " rosterContains=$rosterContains" +
                    " participantRecordExists=$participantRecordExists" +
                    " media=$rawMedia" +
                    " sessionEdgeRecovering=$sessionEdgeRecovering" +
                    " mediaUnavailable=$mediaUnavailablePeer" +
                    " iceConnectionState=$iceConnectionState" +
                    " inRecoveringPeers=${moduleId in recoveringPeers}" +
                    " edgeRecoveryPhase=$edgeRecoveryPhase" +
                    " obligationOpen=$obligationOpen" +
                    " controllerEdgeRecovering=$recoveringPeer" +
                    " transportEverConnected=$transportEverConnected" +
                    " mediaEverLive=$mediaEverLive" +
                    " receivePathLive=$receivePathLive" +
                    " visible=${viewState != null}" +
                    " displayState=${viewState?.displayState?.name ?: "-"}" +
                    " finalPresence=$finalPresence"
            )
        }
    }

    private fun meetingAvailabilityHint(
        session: com.talkback.app.TalkbackSessionSnapshot?,
        speakingModuleId: String?,
        conferenceMuted: Boolean
    ): String? {
        if (session == null) return null
        val runtime = manager.getRuntime() ?: return null
        val states = session.visibleParticipants.map { participant ->
            MeetingPresenceDisplay.resolveParticipantPresentation(
                participantPresentationFacts(
                    session.sessionId,
                    participant,
                    speakingModuleId,
                    edgeRecoveringPeer(runtime, session.sessionId, participant.moduleId),
                    edgeMediaUnavailablePeer(runtime, session.sessionId, participant.moduleId),
                    conferenceMuted
                )
            )
        }
        return MeetingPresenceDisplay.aggregateAvailabilityHint(states, conferenceMuted)
    }

    private fun edgeRecoveringPeer(
        runtime: com.talkback.app.TalkbackRuntime,
        sessionId: String,
        moduleId: String
    ): Boolean = runtime.conferenceEdgeRecovering(sessionId, moduleId)

    /** ADR-0030 per-peer fact; live controller read (not presence aggregate snapshot). */
    private fun edgeMediaUnavailablePeer(
        runtime: com.talkback.app.TalkbackRuntime,
        sessionId: String,
        moduleId: String
    ): Boolean = runtime.conferenceMediaUnavailable(sessionId, moduleId)

    private fun participantPresentationFacts(
        sessionId: String,
        viewState: com.talkback.core.session.ConferenceParticipantViewState,
        speakingModuleId: String?,
        isRecoveringPeer: Boolean,
        mediaUnavailablePeer: Boolean,
        conferenceMuted: Boolean
    ): MeetingPresenceDisplay.ParticipantPresentationFacts {
        val moduleId = viewState.moduleId
        val speaking = speakingModuleId != null &&
            speakingModuleId.equals(moduleId, ignoreCase = true)
        return MeetingPresenceDisplay.ParticipantPresentationFacts(
            sessionId = sessionId,
            moduleId = moduleId,
            isLocal = viewState.isLocal,
            displayState = viewState.displayState,
            isRecoveringPeer = isRecoveringPeer,
            mediaUnavailablePeer = mediaUnavailablePeer,
            speaking = speaking,
            captureBlocked = viewState.isLocal && conferenceMuted
        )
    }

    private fun conferenceVisibleEndpointItem(
        sessionId: String,
        key: String,
        viewState: com.talkback.core.session.ConferenceParticipantViewState,
        speakingModuleId: String?,
        isRecoveringPeer: Boolean,
        mediaUnavailablePeer: Boolean,
        conferenceMuted: Boolean
    ): EndpointUiItem {
        val presentation = MeetingPresenceDisplay.resolveParticipantPresentation(
            participantPresentationFacts(
                sessionId,
                viewState,
                speakingModuleId,
                isRecoveringPeer,
                mediaUnavailablePeer,
                conferenceMuted
            )
        )
        return EndpointUiItem(
            key = key,
            displayLabel = if (viewState.isLocal) localYouLabel(key) else moduleLabel(key),
            status = presentation.endpointStatus,
            signalBars = ConferenceEndpointStatusMapper.signalBarsFor(presentation.endpointStatus),
            isLocal = viewState.isLocal
        )
    }

    private fun endpointItem(
        key: String,
        online: Boolean,
        speakingModuleId: String?,
        localRawKey: String
    ): EndpointUiItem {
        val status = when {
            !online -> EndpointStatus.OFFLINE
            speakingModuleId == moduleIdFromKey(key) -> EndpointStatus.SPEAKING
            else -> EndpointStatus.ONLINE
        }
        return EndpointUiItem(
            key = key,
            displayLabel = moduleLabel(key),
            status = status,
            signalBars = if (online) 3 else 0,
            isLocal = isLocalEndpointKey(key, localRawKey)
        )
    }
}
