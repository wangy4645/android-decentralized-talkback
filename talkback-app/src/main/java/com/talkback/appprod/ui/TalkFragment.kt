package com.talkback.appprod.ui

import android.content.Context
import android.media.AudioManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.talkback.appprod.R
import com.talkback.core.session.ChannelReadiness
import kotlinx.coroutines.launch

class TalkFragment : Fragment() {
    private val viewModel: TalkViewModel by activityViewModels { TalkViewModelFactory(requireContext()) }
    private var pttLocked = false
    private var pttHeld = false
    private var lockSlideTracking = false
    private var thumbSlideMax = 0f
    private var thumbDownRawX = 0f
    private var thumbStartTranslation = 0f
    private var appliedLayoutScale = -1f
    private var boundMeetingMode: Boolean? = null
    private var actionTilesMeetingMode: Boolean? = null
    private var lastPttTransmitActive = false
    private var lastFloorDenyToastAt = 0L
    private lateinit var btnPttRef: FrameLayout
    private lateinit var txtPttHintRef: TextView
    private var pttRippleAnimator: PttTransmitRippleAnimator? = null
    private lateinit var layoutPttRadarRings: View

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_talk, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        btnPttRef = view.findViewById(R.id.btnPtt)
        txtPttHintRef = view.findViewById(R.id.txtPttHint)
        layoutPttRadarRings = view.findViewById(R.id.layoutPttRadarRings)
        pttRippleAnimator = PttTransmitRippleAnimator(
            view.findViewById(R.id.pttTransmitRipple1),
            view.findViewById(R.id.pttTransmitRipple2),
        )

        val barPttLock = view.findViewById<View>(R.id.barPttLockSlide)
        val thumb = view.findViewById<View>(R.id.viewPttLockThumb)
        val trackFill = view.findViewById<View>(R.id.viewPttLockTrackFill)
        val txtPttLockSlide = view.findViewById<TextView>(R.id.txtPttLockSlide)
        val imgChevrons = view.findViewById<ImageView>(R.id.imgPttLockChevrons)
        setupPttLockSlide(barPttLock, thumb, trackFill, txtPttHintRef, txtPttLockSlide, imgChevrons, btnPttRef)

        val scrollContent = view.findViewById<View>(R.id.talkScrollContent)
        scrollContent.post { applyResponsiveLayout(scrollContent, barPttLock, thumb) }
        scrollContent.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            if (v.width > 0) applyResponsiveLayout(v, barPttLock, thumb)
        }

        view.findViewById<TextView>(R.id.btnModePtt).setOnClickListener {
            viewModel.selectPttMode()
        }
        view.findViewById<TextView>(R.id.btnModeMeeting).setOnClickListener {
            viewModel.selectMeetingMode()
        }


        view.findViewById<View>(R.id.channelHeaderArea).setOnClickListener {
            showTaskProfilePicker()
        }

        view.findViewById<View>(R.id.channelOnlineArea).setOnClickListener {
            lifecycleScope.launch {
                when (viewModel.uiState.value.primaryInteractionAction) {
                    PrimaryInteractionAction.OPEN_MEETING_CONTROL -> openMeetingScreen()
                    PrimaryInteractionAction.JOIN_MEETING -> openMeetingScreen()
                    PrimaryInteractionAction.PTT_HOLD -> {
                        viewModel.initiateGroupCall()
                        viewModel.refresh()
                    }
                    PrimaryInteractionAction.DISABLED -> Unit
                }
            }
        }

        view.findViewById<View>(R.id.btnViewAllEndpoints).setOnClickListener {
            Toast.makeText(requireContext(), R.string.feature_coming_soon, Toast.LENGTH_SHORT).show()
        }

        view.findViewById<View>(R.id.btnToolbarMenu).setOnClickListener {
            Toast.makeText(requireContext(), R.string.feature_coming_soon, Toast.LENGTH_SHORT).show()
        }
        view.findViewById<View>(R.id.btnToolbarMore).setOnClickListener {
            Toast.makeText(requireContext(), R.string.feature_coming_soon, Toast.LENGTH_SHORT).show()
        }
        view.findViewById<View>(R.id.btnToolbarVolume).setOnClickListener {
            val audio = requireContext().getSystemService(Context.AUDIO_SERVICE) as AudioManager
            requireActivity().volumeControlStream = AudioManager.STREAM_VOICE_CALL
            audio.adjustStreamVolume(
                AudioManager.STREAM_VOICE_CALL,
                AudioManager.ADJUST_SAME,
                AudioManager.FLAG_SHOW_UI
            )
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state -> bindState(view, state) }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.toastMessageRes.collect { resId ->
                    Toast.makeText(requireContext(), resId, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.startPolling()
        viewModel.refresh()
    }

    override fun onDestroyView() {
        pttRippleAnimator?.stop()
        pttRippleAnimator = null
        super.onDestroyView()
    }

    override fun onPause() {
        viewModel.stopPolling()
        if (pttHeld) {
            view?.findViewById<FrameLayout>(R.id.btnPtt)?.let { releasePtt(it) }
                ?: run {
                    pttHeld = false
                    lifecycleScope.launch { viewModel.onPttUp() }
                }
        }
        super.onPause()
    }

    private fun bindState(view: View, state: TalkUiState) {
        if (boundMeetingMode != state.conferenceMode) {
            boundMeetingMode = state.conferenceMode
            bindPttInteraction(state.conferenceMode)
        }
        bindModeToggle(view, state.conferenceMode)
        view.findViewById<View>(R.id.barPttLockSlide).isVisible = !state.conferenceMode
        if (actionTilesMeetingMode != state.conferenceMode) {
            actionTilesMeetingMode = state.conferenceMode
            bindActionTiles(view, state.conferenceMode)
        }
        if (state.conferenceMode) {
            view.findViewById<TextView>(R.id.txtActionBroadcast).text =
                if (state.conferenceMuted) {
                    getString(R.string.call_control_unmute)
                } else {
                    getString(R.string.call_control_mute)
                }
        }

        view.findViewById<TextView>(R.id.txtChannelTitle).text = state.channelTitle
        view.findViewById<TextView>(R.id.txtChannelSubtitle).text = buildChannelSubtitle(state)
        view.findViewById<TextView>(R.id.txtOnlineCount).text =
            if (state.conferenceMode && state.conferenceActive) {
                state.meeting.participantCountLabel
            } else {
                getString(R.string.online_count_compact, state.onlineCount)
            }

        val floorOwnerLabel = view.findViewById<TextView>(R.id.txtFloorOwnerLabel)
        val talkingLabel = view.findViewById<TextView>(R.id.txtTalkingLabel)
        val imgFloorIndicator = view.findViewById<ImageView>(R.id.imgFloorIndicator)
        val talkingIcon = view.findViewById<ImageView>(R.id.imgTalkingIndicator)

        if (state.conferenceMode) {
            floorOwnerLabel.text = getString(R.string.meeting_status_label)
            talkingLabel.text = getString(R.string.meeting_participants_label)
            val display = state.conferenceDisplay
            view.findViewById<TextView>(R.id.txtFloorOwner).text = when (display.statusPill) {
                ConferenceStatusPillKind.MUTED -> getString(R.string.conference_status_muted)
                ConferenceStatusPillKind.LIVE,
                ConferenceStatusPillKind.POOR_NETWORK -> getString(R.string.meeting_in_progress)
                ConferenceStatusPillKind.RECONNECT_FAILED -> getString(R.string.meeting_reconnect_failed)
                ConferenceStatusPillKind.RECOVERING -> getString(R.string.meeting_reconnecting)
                ConferenceStatusPillKind.CONNECTING -> getString(R.string.conference_status_connecting)
                ConferenceStatusPillKind.INACTIVE -> "--"
            }
            view.findViewById<TextView>(R.id.txtTalking).text = state.meeting.participantCountLabel
            imgFloorIndicator.isVisible = false
            talkingIcon.isVisible = false
        } else {
            floorOwnerLabel.text = getString(R.string.floor_owner)
            talkingLabel.text = getString(R.string.talking)
            view.findViewById<TextView>(R.id.txtFloorOwner).text = state.floorOwner
            view.findViewById<TextView>(R.id.txtTalking).text = state.talking
            imgFloorIndicator.isVisible = true
            talkingIcon.isVisible = true
            val floorActive = state.floorPresentation.isSpeaking ||
                state.floorPresentation is FloorPresentation.Acquiring
            val talkingActive = state.talking != "--"
            val speakerReachable = when (val presentation = state.floorPresentation) {
                is FloorPresentation.Speaking -> presentation.reachable
                is FloorPresentation.Acquiring -> !presentation.degraded
                else -> true
            }
            imgFloorIndicator.alpha = when {
                state.floorPresentation is FloorPresentation.Acquiring -> {
                    if ((state.floorPresentation as FloorPresentation.Acquiring).degraded) 0.45f else 0.7f
                }
                !floorActive -> 0.35f
                !speakerReachable -> 0.5f
                else -> 1f
            }
            talkingIcon.alpha = if (talkingActive) 1f else 0.4f
        }

        view.findViewById<TextView>(R.id.txtNetwork).text = state.networkLabel
        val networkDot = when (state.networkLabel) {
            "Excellent", "Good" -> R.drawable.ic_network_dot_good
            "Poor" -> R.drawable.ic_network_dot_poor
            else -> R.drawable.ic_network_dot_off
        }
        view.findViewById<ImageView>(R.id.imgNetworkIndicator).setImageResource(networkDot)
        val networkColor = when (state.networkLabel) {
            "Excellent", "Good" -> R.color.tb_primary
            "Poor" -> R.color.tb_warning
            else -> R.color.tb_text_muted
        }
        view.findViewById<TextView>(R.id.txtNetwork).setTextColor(
            ContextCompat.getColor(requireContext(), networkColor)
        )

        view.findViewById<TextView>(R.id.txtEndpointHeader).text =
            getString(R.string.online_endpoints_count, state.endpoints.size)
        val container = view.findViewById<LinearLayout>(R.id.containerEndpoints)
        container.removeAllViews()
        val inflater = LayoutInflater.from(requireContext())
        state.endpoints.forEach { item ->
            val row = inflater.inflate(R.layout.item_endpoint, container, false)
            EndpointAdapter.bindHolder(EndpointAdapter.VH(row), item)
            container.addView(row)
        }

        val txtTalkHint = view.findViewById<TextView>(R.id.txtTalkHint)
        val pendingInvite = state.incomingMeetingInvite != null
        val rejoinableMeeting = state.rejoinableMeeting != null
        val display = state.conferenceDisplay
        txtTalkHint.visibility = when {
            !state.serviceRunning -> View.GONE
            state.conferenceMode && !state.conferenceActive && !rejoinableMeeting -> View.GONE
            pendingInvite -> View.VISIBLE
            rejoinableMeeting -> View.VISIBLE
            display.membershipHintVisible -> View.VISIBLE
            state.conferenceActive && display.mediaConnecting -> View.VISIBLE
            state.conferenceActive && display.recovering -> View.VISIBLE
            state.channelReadiness == ChannelReadiness.DISCOVERING -> View.VISIBLE
            state.channelReadiness == ChannelReadiness.AWAITING_PRIMARY ||
            (state.channelReadiness == ChannelReadiness.DIRECTORY_SYNC && state.channelAwaitingHost) -> View.VISIBLE
            state.channelReadiness == ChannelReadiness.DIRECTORY_SYNC -> View.VISIBLE
            !state.conferenceMode &&
                state.channelReadiness == ChannelReadiness.CONNECTING -> View.VISIBLE
            !state.conferenceMode && state.channelConnecting -> View.VISIBLE
            !state.conferenceMode && !state.channelReady && !state.sessionActive -> View.VISIBLE
            else -> View.GONE
        }
        txtTalkHint.text = when {
            pendingInvite -> getString(R.string.meeting_invite_pending)
            rejoinableMeeting -> getString(R.string.meeting_rejoin_hint)
            display.membershipHintVisible ->
                display.membershipHint ?: getString(R.string.conference_status_connecting)
            state.conferenceActive && display.recovering -> when (display.statusPill) {
                ConferenceStatusPillKind.RECONNECT_FAILED -> getString(R.string.meeting_reconnect_failed)
                else -> getString(R.string.meeting_reconnecting)
            }
            state.conferenceActive && display.mediaConnecting ->
                getString(R.string.conference_status_connecting)
            state.channelReadiness == ChannelReadiness.DISCOVERING ->
                getString(R.string.channel_discovering)
            state.channelReadiness == ChannelReadiness.AWAITING_PRIMARY ||
                (state.channelReadiness == ChannelReadiness.DIRECTORY_SYNC && state.channelAwaitingHost) ->
                getString(R.string.channel_awaiting_host)
            state.channelReadiness == ChannelReadiness.DIRECTORY_SYNC ->
                getString(R.string.channel_syncing)
            !state.conferenceMode &&
                (
                    state.channelReadiness == ChannelReadiness.CONNECTING ||
                        state.channelConnecting
                    ) -> getString(R.string.channel_connecting)
            state.sessionActive && !state.channelReady -> getString(R.string.ptt_setting_up_channel)
            !state.sessionActive -> getString(R.string.ptt_join_hint)
            else -> getString(R.string.ptt_join_hint)
        }
        txtTalkHint.setOnClickListener(null)

        val txtPttLabel = view.findViewById<TextView>(R.id.txtPttLabel)
        val txtPttHint = view.findViewById<TextView>(R.id.txtPttHint)
        if (pttHeld && !state.conferenceMode) {
            val floorTaken = state.floorOwnerKey != null &&
                state.floorOwnerKey != state.localEndpointKey
            if (lastPttTransmitActive && !state.pttActive && floorTaken) {
                val now = System.currentTimeMillis()
                if (now - lastFloorDenyToastAt > 800L) {
                    lastFloorDenyToastAt = now
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.ptt_floor_busy, state.floorOwner),
                        Toast.LENGTH_SHORT
                    ).show()
                    btnPttRef.setBackgroundResource(R.drawable.bg_ptt_button)
                }
            }
        } else if (!pttHeld) {
            lastFloorDenyToastAt = 0L
        }
        lastPttTransmitActive = state.pttActive

        val buttonPresentation = TalkButtonPresentationResolver.resolve(
            tab = state.userSelectedTab,
            context = buildTalkButtonContext(state)
        )
        txtPttLabel.text = buttonPresentation.label
        if (!pttLocked) {
            txtPttHint.text = buttonPresentation.hint.orEmpty()
        }
        btnPttRef.isEnabled = buttonPresentation.enabled
        btnPttRef.alpha = if (buttonPresentation.visualState == TalkButtonVisualState.DISABLED) 0.6f else 1f
        btnPttRef.setBackgroundResource(
            when (buttonPresentation.visualState) {
                TalkButtonVisualState.ACTIVE -> R.drawable.bg_ptt_button_active
                TalkButtonVisualState.DEFAULT,
                TalkButtonVisualState.DISABLED -> R.drawable.bg_ptt_button
            }
        )
        val localRipple = !state.conferenceMode && state.localTransmitting
        layoutPttRadarRings.isVisible = !localRipple
        pttRippleAnimator?.setActive(localRipple)
    }

    private fun buildTalkButtonContext(state: TalkUiState): TalkButtonContext =
        TalkButtonContext(
            participantCountLabel = state.meeting.participantCountLabel,
            meetingLive = state.conferenceDisplay.live,
            runtimePhase = state.meeting.runtimePhase,
            conferenceReconnectFailed = state.conferenceReconnectFailed,
            conferenceReconnecting = state.conferenceReconnecting,
            conferenceRejoinInProgress = state.conferenceRejoinInProgress,
            hasRejoinableMeeting = state.rejoinableMeeting != null,
            pttActive = state.pttActive,
            pttHeld = pttHeld,
            labels = buildTalkButtonLabels()
        )

    private fun buildTalkButtonLabels() = TalkButtonPresentationLabels(
        meetingTitle = getString(R.string.meeting_title),
        pttLabel = getString(R.string.ptt_label),
        defaultPttHint = getString(R.string.ptt_hold_hint_lines),
        tapToJoin = getString(R.string.meeting_tap_to_join),
        tapToView = getString(R.string.meeting_tap_to_view),
        reconnectFailed = getString(R.string.meeting_reconnect_failed),
        reconnecting = getString(R.string.meeting_reconnecting)
    )

    private fun bindModeToggle(view: View, meetingMode: Boolean) {
        val btnPtt = view.findViewById<TextView>(R.id.btnModePtt)
        val btnMeeting = view.findViewById<TextView>(R.id.btnModeMeeting)
        val selectedText = ContextCompat.getColor(requireContext(), R.color.tb_bg)
        val unselectedText = ContextCompat.getColor(requireContext(), R.color.tb_text_muted)
        if (meetingMode) {
            btnPtt.background = null
            btnPtt.setTextColor(unselectedText)
            btnMeeting.setBackgroundResource(R.drawable.bg_mode_pill_tab_selected)
            btnMeeting.setTextColor(selectedText)
        } else {
            btnPtt.setBackgroundResource(R.drawable.bg_mode_pill_tab_selected)
            btnPtt.setTextColor(selectedText)
            btnMeeting.background = null
            btnMeeting.setTextColor(unselectedText)
        }
    }

    private fun bindPttInteraction(meetingMode: Boolean) {
        if (meetingMode) {
            btnPttRef.isEnabled = true
            btnPttRef.setOnTouchListener(null)
            btnPttRef.setOnClickListener { openMeetingScreen() }
        } else {
            btnPttRef.isEnabled = true
            btnPttRef.setOnClickListener(null)
            btnPttRef.setOnTouchListener { _, event ->
                if (pttLocked) {
                    if (event.action == MotionEvent.ACTION_DOWN) {
                        toggleLockedPtt(btnPttRef, txtPttHintRef)
                    }
                    true
                } else {
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            pressPtt(btnPttRef)
                            true
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            releasePtt(btnPttRef)
                            true
                        }
                        else -> false
                    }
                }
            }
        }
    }

    private fun bindActionTiles(view: View, meetingMode: Boolean) {
        val broadcast = view.findViewById<View>(R.id.btnBroadcast)
        val monitor = view.findViewById<View>(R.id.btnMonitor)
        val emergency = view.findViewById<View>(R.id.btnEmergency)
        val record = view.findViewById<View>(R.id.btnRecord)
        val txtMonitor = view.findViewById<TextView>(R.id.txtActionMonitor)
        val txtEmergency = view.findViewById<TextView>(R.id.txtActionEmergency)
        val txtRecord = view.findViewById<TextView>(R.id.txtActionRecord)

        if (meetingMode) {
            txtMonitor.text = getString(R.string.meeting_control_speaker)
            txtEmergency.text = getString(R.string.meeting_control_members)
            txtRecord.text = getString(R.string.meeting_control_more)
            broadcast.setOnClickListener {
                lifecycleScope.launch {
                    when (viewModel.toggleMeetingMute()) {
                        is PttDownResult.NoPeers, is PttDownResult.NoTeammates -> {
                            Toast.makeText(
                                requireContext(),
                                R.string.meeting_tap_to_join,
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        is PttDownResult.ServiceStopped -> {
                            Toast.makeText(
                                requireContext(),
                                R.string.service_not_running,
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        else -> Unit
                    }
                }
            }
            monitor.setOnClickListener {
                CallAudioRouteHelper.apply(requireContext(), CallAudioRoute.SPEAKER)
            }
            emergency.setOnClickListener { openMeetingScreen(MeetingNavigation.MEMBERS) }
            record.setOnClickListener { openMeetingScreen(MeetingNavigation.OPTIONS) }
        } else {
            view.findViewById<TextView>(R.id.txtActionBroadcast).text =
                getString(R.string.action_broadcast)
            txtMonitor.text = getString(R.string.action_monitor)
            txtEmergency.text = getString(R.string.action_emergency)
            txtRecord.text = getString(R.string.action_record)
            broadcast.setOnClickListener { viewModel.selectMeetingMode() }
            monitor.setOnClickListener {
                Toast.makeText(requireContext(), R.string.feature_coming_soon, Toast.LENGTH_SHORT).show()
            }
            emergency.setOnClickListener {
                Toast.makeText(requireContext(), R.string.feature_coming_soon, Toast.LENGTH_SHORT).show()
            }
            record.setOnClickListener {
                Toast.makeText(requireContext(), R.string.feature_coming_soon, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun openMeetingScreen(target: MeetingNavigation = MeetingNavigation.MAIN) {
        lifecycleScope.launch {
            val state = viewModel.uiState.value
            val result = if (state.conferenceActive || state.channelConnecting) {
                if (state.channelConnecting) PttDownResult.Connecting else PttDownResult.Ok
            } else {
                viewModel.joinMeeting(reason = "ui.openMeetingScreen")
            }
            when (result) {
                is PttDownResult.Ok, is PttDownResult.Connecting -> {
                    (activity as? MainActivity)?.showMeetingScreen(target)
                }
                is PttDownResult.NoPeers, is PttDownResult.NoTeammates -> {
                    Toast.makeText(
                        requireContext(),
                        R.string.ptt_no_teammates,
                        Toast.LENGTH_SHORT
                    ).show()
                }
                is PttDownResult.ServiceStopped -> {
                    Toast.makeText(
                        requireContext(),
                        R.string.service_not_running,
                        Toast.LENGTH_SHORT
                    ).show()
                }
                is PttDownResult.FloorBusy -> {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.ptt_floor_busy, result.speaker),
                        Toast.LENGTH_SHORT
                    ).show()
                }
                is PttDownResult.MeetingActive -> {
                    (activity as? MainActivity)?.showMeetingScreen(target)
                }
            }
        }
    }

    private fun pressPtt(btnPtt: FrameLayout? = null) {
        pttHeld = true
        btnPtt?.setBackgroundResource(R.drawable.bg_ptt_button_active)
        lifecycleScope.launch {
            when (val result = viewModel.onPttDown()) {
                is PttDownResult.Ok -> Unit
                is PttDownResult.Connecting -> {
                    if (pttHeld) {
                        Toast.makeText(
                            requireContext(),
                            R.string.ptt_setting_up_channel,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                is PttDownResult.NoPeers, is PttDownResult.NoTeammates -> {
                    Toast.makeText(
                        requireContext(),
                        R.string.ptt_no_teammates,
                        Toast.LENGTH_SHORT
                    ).show()
                }
                is PttDownResult.ServiceStopped -> {
                    Toast.makeText(
                        requireContext(),
                        R.string.service_not_running,
                        Toast.LENGTH_SHORT
                    ).show()
                }
                is PttDownResult.FloorBusy -> {
                    btnPtt?.setBackgroundResource(R.drawable.bg_ptt_button)
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.ptt_floor_busy, result.speaker),
                        Toast.LENGTH_SHORT
                    ).show()
                }
                is PttDownResult.MeetingActive -> {
                    btnPtt?.setBackgroundResource(R.drawable.bg_ptt_button)
                    Toast.makeText(
                        requireContext(),
                        R.string.ptt_meeting_active,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            if (!pttHeld) {
                viewModel.onPttUp()
                btnPtt?.setBackgroundResource(R.drawable.bg_ptt_button)
            }
        }
    }

    private fun releasePtt(btnPtt: FrameLayout? = null) {
        if (!pttHeld) return
        pttHeld = false
        lifecycleScope.launch {
            viewModel.onPttUp()
            btnPtt?.setBackgroundResource(R.drawable.bg_ptt_button)
        }
    }

    private fun toggleLockedPtt(btnPtt: FrameLayout, txtPttHint: TextView) {
        if (pttHeld) {
            releasePtt(btnPtt)
            txtPttHint.text = getString(R.string.ptt_locked_hint_lines)
        } else {
            pressPtt(btnPtt)
            txtPttHint.text = getString(R.string.ptt_locked_hint_lines)
        }
    }

    private fun applyResponsiveLayout(root: View, lockBar: View, lockThumb: View) {
        val density = resources.displayMetrics.density
        val widthDp = root.width.takeIf { it > 0 }?.div(density)
            ?: resources.configuration.screenWidthDp.toFloat()
        val scale = TalkResponsiveScale.scaleFactor(widthDp)
        if (kotlin.math.abs(scale - appliedLayoutScale) < 0.01f) return
        appliedLayoutScale = scale
        TalkResponsiveScale.apply(root, scale, resources)
        refreshThumbSlideRange(lockBar, lockThumb)
    }

    private fun setupPttLockSlide(
        bar: View,
        thumb: View,
        trackFill: View,
        txtPttHint: TextView,
        txtLockSlide: TextView,
        imgChevrons: ImageView,
        btnPtt: FrameLayout
    ) {
        bar.post { refreshThumbSlideRange(bar, thumb) }

        bar.setOnTouchListener { v, event ->
            if (thumbSlideMax <= 0f) {
                refreshThumbSlideRange(bar, thumb)
            }
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lockSlideTracking = true
                    thumbDownRawX = event.rawX
                    thumbStartTranslation = thumb.translationX
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!lockSlideTracking) return@setOnTouchListener false
                    val delta = event.rawX - thumbDownRawX
                    val target = (thumbStartTranslation + delta).coerceIn(0f, thumbSlideMax)
                    thumb.translationX = target
                    updateLockTrackFill(trackFill, thumb, target)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!lockSlideTracking) return@setOnTouchListener false
                    lockSlideTracking = false
                    val ratio = if (thumbSlideMax > 0f) thumb.translationX / thumbSlideMax else 0f
                    if (!pttLocked) {
                        if (ratio >= 0.72f) {
                            snapThumb(thumb, thumbSlideMax)
                            updateLockTrackFill(trackFill, thumb, thumbSlideMax)
                            setPttLocked(true, txtPttHint, txtLockSlide, imgChevrons, btnPtt)
                        } else {
                            snapThumb(thumb, 0f)
                            resetLockTrackFill(trackFill)
                        }
                    } else {
                        if (ratio <= 0.28f) {
                            snapThumb(thumb, 0f)
                            resetLockTrackFill(trackFill)
                            setPttLocked(false, txtPttHint, txtLockSlide, imgChevrons, btnPtt)
                        } else {
                            snapThumb(thumb, thumbSlideMax)
                            updateLockTrackFill(trackFill, thumb, thumbSlideMax)
                        }
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun refreshThumbSlideRange(bar: View, thumb: View) {
        val trackWidth = bar.width - bar.paddingLeft - bar.paddingRight
        thumbSlideMax = (trackWidth - thumb.width).toFloat().coerceAtLeast(0f)
        if (pttLocked) {
            thumb.translationX = thumbSlideMax
        } else {
            thumb.translationX = 0f
        }
    }

    private fun updateLockTrackFill(trackFill: View, thumb: View, thumbX: Float) {
        val width = (thumbX + thumb.width).toInt().coerceAtLeast(thumb.width)
        trackFill.visibility = View.VISIBLE
        val params = trackFill.layoutParams as FrameLayout.LayoutParams
        params.width = width
        trackFill.layoutParams = params
    }

    private fun resetLockTrackFill(trackFill: View) {
        trackFill.visibility = View.INVISIBLE
        val params = trackFill.layoutParams
        params.width = 0
        trackFill.layoutParams = params
    }

    private fun snapThumb(thumb: View, targetX: Float) {
        thumb.animate().translationX(targetX).setDuration(120L).start()
    }

    private fun setPttLocked(
        locked: Boolean,
        txtPttHint: TextView,
        txtLockSlide: TextView,
        imgChevrons: ImageView,
        btnPtt: FrameLayout
    ) {
        pttLocked = locked
        txtPttHint.text = if (locked) {
            getString(R.string.ptt_locked_hint_lines)
        } else {
            getString(R.string.ptt_hold_hint_lines)
        }
        txtLockSlide.text = if (locked) {
            getString(R.string.lock_ptt_unlock_slide)
        } else {
            getString(R.string.lock_ptt_slide)
        }
        imgChevrons.visibility = if (locked) View.INVISIBLE else View.VISIBLE
        if (!locked && pttHeld) {
            releasePtt(btnPtt)
        }
    }

    private fun buildChannelSubtitle(state: TalkUiState): String {
        val parts = mutableListOf<String>()
        if (state.taskProfileName.isNotBlank()) {
            parts.add(state.taskProfileName)
        } else if (state.channelSubtitle.isNotBlank()) {
            parts.add(state.channelSubtitle)
        }
        if (state.rfKeyLabel.isNotBlank()) {
            parts.add(getString(R.string.task_profile_rf_meta, state.rfKeyLabel).removePrefix("· ").trim())
        }
        return parts.joinToString(" · ")
    }

    private fun showTaskProfilePicker() {
        val profiles = viewModel.listTaskProfiles()
        if (profiles.isEmpty()) {
            Toast.makeText(requireContext(), R.string.task_profile_not_found, Toast.LENGTH_SHORT).show()
            return
        }
        val names = profiles.map { it.name }.toTypedArray()
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.task_profile_pick_title)
            .setItems(names) { _, which ->
                val profile = profiles[which]
                if (profile.id == viewModel.activeTaskProfile()?.id) {
                    Toast.makeText(requireContext(), R.string.task_profile_already_active, Toast.LENGTH_SHORT).show()
                    return@setItems
                }
                TaskProfileSwitchDialog.show(requireContext(), profile) {
                    viewModel.switchTaskProfile(profile.id)
                        .onSuccess {
                            Toast.makeText(requireContext(), R.string.task_profile_switched, Toast.LENGTH_SHORT).show()
                        }
                        .onFailure {
                            Toast.makeText(
                                requireContext(),
                                it.message ?: getString(R.string.task_profile_not_found),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                }
            }
            .show()
    }
}
