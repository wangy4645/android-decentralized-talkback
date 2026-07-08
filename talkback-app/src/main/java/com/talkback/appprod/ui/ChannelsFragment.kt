package com.talkback.appprod.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.talkback.appprod.R
import com.talkback.core.session.ConferenceRuntimePhase
import kotlinx.coroutines.launch

class ChannelsFragment : Fragment() {
    private val viewModel: TalkViewModel by activityViewModels { TalkViewModelFactory(requireContext()) }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_channels, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val txtTitle = view.findViewById<TextView>(R.id.txtActiveChannel)
        val txtDesc = view.findViewById<TextView>(R.id.txtActiveChannelDesc)
        val txtOnlineCount = view.findViewById<TextView>(R.id.txtChannelOnlineCount)
        val txtStatus = view.findViewById<TextView>(R.id.txtChannelStatus)
        val txtFloor = view.findViewById<TextView>(R.id.txtChannelFloor)
        val imgStatusIcon = view.findViewById<ImageView>(R.id.imgChannelStatusIcon)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    bindChannel(
                        view,
                        state,
                        txtTitle,
                        txtDesc,
                        txtOnlineCount,
                        txtStatus,
                        txtFloor,
                        imgStatusIcon
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.startPolling()
        viewModel.refresh()
    }

    override fun onPause() {
        viewModel.stopPolling()
        super.onPause()
    }

    private fun bindChannel(
        root: View,
        state: TalkUiState,
        txtTitle: TextView,
        txtDesc: TextView,
        txtOnlineCount: TextView,
        txtStatus: TextView,
        txtFloor: TextView,
        imgStatusIcon: ImageView
    ) {
        val ctx = root.context
        txtTitle.text = state.channelTitle
        txtDesc.text = state.channelSubtitle
        txtOnlineCount.text = state.onlineCount.toString()

        val speaking = state.serviceRunning && state.sessionActive && (
            (state.conferenceActive &&
                state.meeting.runtimePhase == ConferenceRuntimePhase.ACTIVE &&
                !state.conferenceMuted) ||
                state.talking != "--"
            )
        val floorLabel = when {
            speaking -> state.talking
            state.floorOwner != "--" -> state.floorOwner
            else -> "--"
        }
        txtFloor.text = if (state.conferenceMode) {
            getString(R.string.mode_meeting) + ": " + floorLabel
        } else {
            getString(R.string.channel_floor_location, floorLabel)
        }

        val statusText = when {
            !state.serviceRunning -> getString(R.string.channel_status_service_stopped)
            state.incomingMeetingInvite != null ->
                getString(R.string.meeting_invite_pending)
            state.conferenceMode && !state.sessionActive -> getString(R.string.meeting_tap_to_join)
            !state.sessionActive -> getString(R.string.channel_status_waiting)
            state.conferenceActive && state.conferenceMuted -> getString(R.string.conference_status_muted)
            state.conferenceActive && state.meeting.runtimePhase == ConferenceRuntimePhase.ACTIVE ->
                getString(R.string.conference_status_live)
            state.conferenceActive &&
                state.meeting.runtimePhase == ConferenceRuntimePhase.RECOVERING ->
                getString(R.string.meeting_reconnecting)
            state.conferenceActive -> getString(R.string.conference_status_connecting)
            speaking -> getString(R.string.status_speaking)
            else -> getString(R.string.channel_status_idle)
        }
        txtStatus.text = statusText
        txtStatus.setTextColor(
            ContextCompat.getColor(
                ctx,
                when {
                    !state.serviceRunning -> R.color.tb_text_muted
                    speaking -> R.color.tb_primary
                    else -> R.color.tb_text_secondary
                }
            )
        )

        when {
            speaking -> {
                imgStatusIcon.isVisible = true
                imgStatusIcon.setImageResource(R.drawable.ic_waveform)
            }
            state.serviceRunning && state.sessionActive -> {
                imgStatusIcon.isVisible = true
                imgStatusIcon.setImageResource(R.drawable.ic_chevrons_right)
            }
            else -> imgStatusIcon.isVisible = false
        }
    }
}
