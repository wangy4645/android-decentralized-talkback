package com.talkback.appprod.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.talkback.appprod.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class MeetingFragment : Fragment() {
    private val viewModel: TalkViewModel by activityViewModels { TalkViewModelFactory(requireContext()) }

    private var timerJob: Job? = null
    private var volumeMeterPollJob: Job? = null
    private var connectingStartedAtMs: Long? = null
    private var pendingNavigation: MeetingNavigation = MeetingNavigation.MAIN
    private var navigationHandled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingNavigation = arguments?.getString(ARG_NAV)?.let {
            runCatching { MeetingNavigation.valueOf(it) }.getOrDefault(MeetingNavigation.MAIN)
        } ?: MeetingNavigation.MAIN
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_meeting, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        setupControl(
            view.findViewById(R.id.btnMeetingMute),
            R.drawable.ic_mic_ptt,
            getString(R.string.call_control_mute)
        ) {
            lifecycleScope.launch { viewModel.toggleMeetingMute() }
        }
        val speakerControl = view.findViewById<View>(R.id.btnMeetingSpeaker)
        val headsetControl = view.findViewById<View>(R.id.btnMeetingHeadset)
        setupControl(
            speakerControl,
            R.drawable.ic_toolbar_volume,
            getString(R.string.call_control_speaker)
        ) {
            CallAudioRouteHelper.apply(requireContext(), CallAudioRoute.SPEAKER)
            CallAudioRouteUi.highlight(speakerControl, headsetControl, CallAudioRoute.SPEAKER)
        }
        setupControl(
            headsetControl,
            R.drawable.ic_volume,
            getString(R.string.call_control_headset)
        ) {
            CallAudioRouteHelper.apply(requireContext(), CallAudioRoute.EARPIECE)
            CallAudioRouteUi.highlight(speakerControl, headsetControl, CallAudioRoute.EARPIECE)
        }
        setupControl(
            view.findViewById(R.id.btnMeetingMore),
            R.drawable.ic_more_horiz,
            getString(R.string.meeting_control_more)
        ) {
            showSubPage(MeetingOptionsFragment())
        }

        view.findViewById<View>(R.id.btnMeetingBack).setOnClickListener {
            handleMeetingBack()
        }
        view.findViewById<View>(R.id.btnMeetingLeaveTop).setOnClickListener { leaveMeeting() }
        view.findViewById<View>(R.id.btnMeetingLeave).setOnClickListener { leaveMeeting() }
        view.findViewById<View>(R.id.btnMeetingCancel).setOnClickListener { handleMeetingCancel() }

        CallAudioRouteHelper.apply(requireContext(), CallAudioRoute.SPEAKER)
        CallAudioRouteUi.highlight(speakerControl, headsetControl, CallAudioRoute.SPEAKER)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    bindState(view, state)
                    maybeHandlePendingNavigation(state)
                }
            }
        }
    }

    override fun onDestroyView() {
        timerJob?.cancel()
        timerJob = null
        volumeMeterPollJob?.cancel()
        volumeMeterPollJob = null
        connectingStartedAtMs = null
        navigationHandled = false
        super.onDestroyView()
    }

    fun showSubPage(fragment: Fragment) {
        val container = view?.findViewById<FrameLayout>(R.id.meetingSubContainer) ?: return
        container.visibility = View.VISIBLE
        childFragmentManager.beginTransaction()
            .replace(R.id.meetingSubContainer, fragment)
            .addToBackStack("meeting_sub")
            .commit()
    }

    fun hideSubPage() {
        val container = view?.findViewById<FrameLayout>(R.id.meetingSubContainer) ?: return
        if (childFragmentManager.backStackEntryCount > 0) {
            childFragmentManager.popBackStack()
        }
        container.post {
            container.isVisible = childFragmentManager.backStackEntryCount > 0
        }
    }

    private fun maybeHandlePendingNavigation(state: TalkUiState) {
        if (navigationHandled || pendingNavigation == MeetingNavigation.MAIN) return
        if (!state.conferenceActive || !state.channelReady) return
        navigationHandled = true
        when (pendingNavigation) {
            MeetingNavigation.MEMBERS -> showSubPage(MeetingMembersFragment())
            MeetingNavigation.OPTIONS -> showSubPage(MeetingOptionsFragment())
            MeetingNavigation.INVITE -> showSubPage(InviteMembersFragment())
            MeetingNavigation.MAIN -> Unit
        }
    }

    fun handleMeetingBack() {
        val state = viewModel.uiState.value
        val awaitingRejoin = state.conferenceMode && !state.conferenceActive
        val connecting = awaitingRejoin || (state.conferenceActive && !state.channelReady)
        if (connecting && !viewModel.isConferenceHost() && state.conferenceActive) {
            leaveMeeting()
        } else {
            minimizeMeeting()
        }
    }

    private fun handleMeetingCancel() {
        leaveMeeting()
    }

    private fun minimizeMeeting() {
        (activity as? MainActivity)?.dismissMeetingOverlay()
    }

    private fun leaveMeeting() {
        viewModel.leaveMeeting()
        minimizeMeeting()
    }

    private fun setupControl(root: View, iconRes: Int, label: String, onClick: () -> Unit) {
        root.findViewById<ImageView>(R.id.imgControlIcon).setImageResource(iconRes)
        root.findViewById<TextView>(R.id.txtControlLabel).text = label
        root.setOnClickListener { onClick() }
    }

    private fun bindMuteControl(root: View, muted: Boolean) {
        val icon = root.findViewById<ImageView>(R.id.imgControlIcon)
        val label = root.findViewById<TextView>(R.id.txtControlLabel)
        val circle = root.findViewById<View>(R.id.btnControlCircle)
        val ctx = requireContext()
        if (muted) {
            icon.setImageResource(R.drawable.ic_mic_off)
            icon.imageTintList = null
            label.text = getString(R.string.call_control_unmute)
            label.setTextColor(ContextCompat.getColor(ctx, R.color.tb_text_secondary))
            circle.setBackgroundResource(R.drawable.bg_meeting_mute_control_active)
        } else {
            icon.setImageResource(R.drawable.ic_mic_ptt)
            icon.imageTintList = ContextCompat.getColorStateList(ctx, R.color.tb_text_primary)
            label.text = getString(R.string.call_control_mute)
            label.setTextColor(ContextCompat.getColor(ctx, R.color.tb_text_muted))
            circle.setBackgroundResource(R.drawable.bg_call_control_circle)
        }
    }

    private fun bindState(view: View, state: TalkUiState) {
        if (!state.conferenceActive && !state.conferenceMode && state.incomingMeetingInvite == null) {
            (activity as? MainActivity)?.dismissMeetingOverlay()
            return
        }
        view.findViewById<TextView>(R.id.txtMeetingHeader).text =
            getString(R.string.meeting_channel_title, state.channelTitle)
        view.findViewById<TextView>(R.id.txtMeetingSubtitle).text = state.channelSubtitle
        val awaitingRejoin = state.conferenceMode && !state.conferenceActive
        val connecting = awaitingRejoin || (state.conferenceActive && !state.channelReady)
        val live = state.conferenceActive && state.channelReady
        val muted = state.conferenceMuted

        view.findViewById<View>(R.id.panelMeetingConnecting).isVisible = connecting
        view.findViewById<View>(R.id.panelMeetingLive).isVisible = live
        view.findViewById<View>(R.id.rowMeetingControls).isVisible = live
        view.findViewById<View>(R.id.btnMeetingLeave).isVisible = live
        view.findViewById<View>(R.id.btnMeetingCancel).isVisible = connecting
        view.findViewById<View>(R.id.btnMeetingLeaveTop).isVisible = live
        view.findViewById<TextView>(R.id.txtMeetingParticipantCount).isVisible = live
        if (live) {
            val inMeetingCount = state.meeting.visibleParticipantCount
            view.findViewById<TextView>(R.id.txtMeetingParticipantCount).text =
                getString(R.string.meeting_participants, inMeetingCount)
        }

        bindStatusPill(view.findViewById(R.id.txtMeetingStatusPill), connecting, live, muted, state)

        val txtTimer = view.findViewById<TextView>(R.id.txtMeetingTimer)
        if (connecting) {
            if (connectingStartedAtMs == null) {
                connectingStartedAtMs = System.currentTimeMillis()
            }
            startElapsedTimer(txtTimer, connectingStartedAtMs!!)
        } else {
            connectingStartedAtMs = null
            val startedAt = state.meeting.startedAtMs
            if (live && startedAt != null) {
                startElapsedTimer(txtTimer, startedAt)
            } else {
                timerJob?.cancel()
                txtTimer.text = "00:00:00"
            }
        }

        if (!live) return

        val poorNetwork = state.meeting.networkLabel == "Poor" || state.networkLabel == "Poor"
        view.findViewById<TextView>(R.id.txtMeetingPoorNetwork).isVisible = poorNetwork

        val avatar = view.findViewById<ImageView>(R.id.imgMeetingSpeakerAvatar)
        val mutedIcon = view.findViewById<ImageView>(R.id.imgMeetingMutedIcon)
        val txtSpeaker = view.findViewById<TextView>(R.id.txtMeetingSpeaker)
        val txtSpeakerStatus = view.findViewById<TextView>(R.id.txtMeetingSpeakerStatus)
        val avatarOuter = view.findViewById<View>(R.id.frameMeetingAvatarOuter)
        val avatarInner = view.findViewById<View>(R.id.frameMeetingAvatarInner)
        val volumeMeter = view.findViewById<MeetingVolumeMeterView>(R.id.meterMeetingVolume)
        if (muted) {
            avatar.isVisible = false
            mutedIcon.isVisible = true
            avatarOuter.setBackgroundResource(R.drawable.bg_meeting_speaker_outer_ring_muted)
            avatarInner.setBackgroundResource(R.drawable.bg_meeting_speaker_inner_ring_muted)
            txtSpeaker.text = getString(R.string.meeting_you_muted)
            txtSpeakerStatus.text = getString(R.string.meeting_tap_mic_to_speak)
            txtSpeakerStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.tb_text_muted))
            stopVolumeMeterPolling(volumeMeter)
        } else {
            avatar.isVisible = true
            mutedIcon.isVisible = false
            val localSelf = state.endpoints.firstOrNull { it.isLocal }
            val activeSpeaker = state.endpoints.firstOrNull { it.status == EndpointStatus.SPEAKING }
            val speaker = activeSpeaker ?: localSelf ?: state.endpoints.firstOrNull {
                it.status == EndpointStatus.ONLINE
            }
            val isSpeaking = speaker?.status == EndpointStatus.SPEAKING
            if (speaker?.isLocal == true && isSpeaking) {
                avatarOuter.setBackgroundResource(R.drawable.bg_meeting_speaker_outer_ring)
                avatarInner.setBackgroundResource(R.drawable.bg_meeting_speaker_inner_ring)
            } else if (speaker?.isLocal == true) {
                avatarOuter.setBackgroundResource(R.drawable.bg_meeting_speaker_outer_ring_local)
                avatarInner.setBackgroundResource(R.drawable.bg_meeting_speaker_inner_ring)
            } else if (isSpeaking) {
                avatarOuter.setBackgroundResource(R.drawable.bg_meeting_speaker_outer_ring)
                avatarInner.setBackgroundResource(R.drawable.bg_meeting_speaker_inner_ring)
            } else {
                avatarOuter.setBackgroundResource(R.drawable.bg_meeting_speaker_outer_ring)
                avatarInner.setBackgroundResource(R.drawable.bg_meeting_speaker_inner_ring)
            }
            txtSpeaker.text = speaker?.displayLabel ?: "--"
            txtSpeakerStatus.text = when {
                speaker?.isLocal == true && isSpeaking -> getString(R.string.meeting_speaking)
                speaker?.isLocal == true -> getString(R.string.meeting_you_in_meeting)
                isSpeaking -> getString(R.string.meeting_speaking)
                else -> getString(R.string.status_online)
            }
            txtSpeakerStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.tb_primary))
            if (isSpeaking) {
                startVolumeMeterPolling(volumeMeter)
            } else {
                stopVolumeMeterPolling(volumeMeter)
            }
        }

        bindMuteControl(view.findViewById(R.id.btnMeetingMute), muted)
        bindAvatarRow(view.findViewById(R.id.containerMeetingAvatars), state.endpoints)
    }

    private fun bindStatusPill(
        statusPill: TextView,
        connecting: Boolean,
        live: Boolean,
        muted: Boolean,
        state: TalkUiState
    ) {
        val ctx = requireContext()
        val poorNetwork = state.meeting.networkLabel == "Poor" || state.networkLabel == "Poor"
        when {
            state.conferenceReconnectFailed && connecting -> {
                statusPill.setCompoundDrawablesWithIntrinsicBounds(R.drawable.dot_meeting_connecting, 0, 0, 0)
                statusPill.text = getString(R.string.meeting_reconnect_failed)
                statusPill.setBackgroundResource(R.drawable.bg_meeting_connecting_pill)
                statusPill.setTextColor(ContextCompat.getColor(ctx, R.color.tb_meeting_connecting))
            }
            state.conferenceReconnecting && connecting -> {
                statusPill.setCompoundDrawablesWithIntrinsicBounds(R.drawable.dot_meeting_connecting, 0, 0, 0)
                statusPill.text = getString(R.string.meeting_reconnecting)
                statusPill.setBackgroundResource(R.drawable.bg_meeting_connecting_pill)
                statusPill.setTextColor(ContextCompat.getColor(ctx, R.color.tb_meeting_connecting))
            }
            connecting -> {
                statusPill.setCompoundDrawablesWithIntrinsicBounds(R.drawable.dot_meeting_connecting, 0, 0, 0)
                statusPill.text = getString(R.string.meeting_status_connecting)
                statusPill.setBackgroundResource(R.drawable.bg_meeting_connecting_pill)
                statusPill.setTextColor(ContextCompat.getColor(ctx, R.color.tb_meeting_connecting))
            }
            muted -> {
                statusPill.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0)
                statusPill.text = getString(R.string.conference_status_muted)
                statusPill.setBackgroundResource(R.drawable.bg_status_pill)
                statusPill.setTextColor(ContextCompat.getColor(ctx, R.color.tb_danger))
            }
            poorNetwork && live -> {
                statusPill.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0)
                statusPill.text = getString(R.string.meeting_poor_network)
                statusPill.setBackgroundResource(R.drawable.bg_status_pill)
                statusPill.setTextColor(ContextCompat.getColor(ctx, R.color.tb_warning))
            }
            live && isWaitingAlone(state) -> {
                statusPill.setCompoundDrawablesWithIntrinsicBounds(R.drawable.dot_meeting_connecting, 0, 0, 0)
                statusPill.text = getString(R.string.meeting_status_waiting)
                statusPill.setBackgroundResource(R.drawable.bg_meeting_connecting_pill)
                statusPill.setTextColor(ContextCompat.getColor(ctx, R.color.tb_meeting_connecting))
            }
            live -> {
                statusPill.setCompoundDrawablesWithIntrinsicBounds(R.drawable.dot_online, 0, 0, 0)
                statusPill.text = getString(R.string.conference_status_live)
                statusPill.setBackgroundResource(R.drawable.bg_call_online_pill)
                statusPill.setTextColor(ContextCompat.getColor(ctx, R.color.tb_success))
            }
            else -> {
                statusPill.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0)
                statusPill.text = getString(R.string.conference_status_connecting)
                statusPill.setBackgroundResource(R.drawable.bg_status_pill)
                statusPill.setTextColor(ContextCompat.getColor(ctx, R.color.tb_text_secondary))
            }
        }
    }

    private fun isWaitingAlone(state: TalkUiState): Boolean =
        state.conferenceActive &&
            viewModel.isConferenceHost() &&
            state.meeting.visibleParticipantCount <= 1

    private fun startVolumeMeterPolling(meter: MeetingVolumeMeterView) {
        meter.isVisible = true
        if (volumeMeterPollJob?.isActive == true) return
        volumeMeterPollJob = viewLifecycleOwner.lifecycleScope.launch {
            while (isActive) {
                meter.setLevel(viewModel.meetingSpeakerAudioLevel())
                delay(80L)
            }
        }
    }

    private fun stopVolumeMeterPolling(meter: MeetingVolumeMeterView) {
        volumeMeterPollJob?.cancel()
        volumeMeterPollJob = null
        meter.isVisible = false
        meter.setLevel(0f)
    }

    private fun bindAvatarRow(container: LinearLayout, endpoints: List<EndpointUiItem>) {
        container.removeAllViews()
        val inflater = LayoutInflater.from(requireContext())
        val ctx = requireContext()
        val primary = ContextCompat.getColor(ctx, R.color.tb_primary)
        val secondary = ContextCompat.getColor(ctx, R.color.tb_text_secondary)
        val ordered = endpoints.sortedWith(
            compareByDescending<EndpointUiItem> { it.isLocal }
                .thenByDescending { it.status == EndpointStatus.SPEAKING }
        )
        ordered.take(8).forEach { item ->
            val chip = inflater.inflate(R.layout.item_meeting_participant_chip, container, false)
            val frame = chip.findViewById<View>(R.id.frameChipAvatar)
            val label = chip.findViewById<TextView>(R.id.txtChipLabel)
            label.text = item.displayLabel
            val isSpeaking = item.status == EndpointStatus.SPEAKING
            when {
                isSpeaking -> {
                    frame.setBackgroundResource(R.drawable.bg_meeting_chip_speaking)
                    chip.alpha = 1f
                    label.setTextColor(primary)
                }
                item.isLocal -> {
                    frame.setBackgroundResource(R.drawable.bg_meeting_chip_local)
                    chip.alpha = 0.95f
                    label.setTextColor(primary)
                }
                else -> {
                    frame.setBackgroundResource(R.drawable.bg_avatar_circle)
                    chip.alpha = 0.75f
                    label.setTextColor(secondary)
                }
            }
            container.addView(chip)
        }
    }

    private fun startElapsedTimer(txtTimer: TextView, startedAtMs: Long) {
        timerJob?.cancel()
        timerJob = viewLifecycleOwner.lifecycleScope.launch {
            while (isActive) {
                val elapsed = System.currentTimeMillis() - startedAtMs
                txtTimer.text = formatElapsed(elapsed, includeHours = elapsed >= TimeUnit.HOURS.toMillis(1))
                delay(1_000L)
            }
        }
    }

    private fun formatElapsed(elapsedMs: Long, includeHours: Boolean): String {
        val hours = TimeUnit.MILLISECONDS.toHours(elapsedMs)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(elapsedMs) % 60
        val seconds = TimeUnit.MILLISECONDS.toSeconds(elapsedMs) % 60
        return if (includeHours) {
            String.format("%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%02d:%02d:%02d", 0L, minutes, seconds)
        }
    }

    companion object {
        const val TAG_MEETING = "meeting_overlay"
        private const val ARG_NAV = "meeting_nav"

        fun newInstance(target: MeetingNavigation = MeetingNavigation.MAIN): MeetingFragment =
            MeetingFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_NAV, target.name)
                }
            }
    }
}
