package com.talkback.appprod.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.talkback.appprod.R
import kotlinx.coroutines.launch

class MeetingOptionsFragment : Fragment() {
    private val viewModel: TalkViewModel by activityViewModels { TalkViewModelFactory(requireContext()) }

    private var rowMuteAll: View? = null
    private var rowEndForAll: View? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_meeting_options, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        view.findViewById<TextView>(R.id.txtSettingsTitle).setText(R.string.meeting_options_title)
        view.findViewById<View>(R.id.btnSettingsBack).setOnClickListener {
            (parentFragment as? MeetingFragment)?.hideSubPage()
        }

        val config = viewModel.loadConfig()
        val groupMeetingControl = view.findViewById<LinearLayout>(R.id.groupMeetingControl)
        val groupAudio = view.findViewById<LinearLayout>(R.id.groupAudio)
        val groupInfo = view.findViewById<LinearLayout>(R.id.groupInfo)
        val groupParticipants = view.findViewById<LinearLayout>(R.id.groupParticipants)

        rowMuteAll = groupMeetingControl.inflateSettingsRow(R.layout.item_settings_subpage_nav_row)
            .also { row ->
                row.setupSubpageNavRow(
                    title = getString(R.string.meeting_mute_all),
                    showChevron = false,
                    showDivider = true,
                    onClick = {
                        lifecycleScope.launch {
                            viewModel.toggleMeetingMute()
                            Toast.makeText(
                                requireContext(),
                                R.string.meeting_mute_all_applied,
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                )
            }

        groupMeetingControl.inflateSettingsRow(R.layout.item_settings_toggle_row).setupSubpageToggleRow(
            title = getString(R.string.meeting_lock),
            checked = config.meetingLocked,
            showDivider = true,
            onCheckedChange = { checked -> viewModel.setMeetingLocked(checked) }
        )

        rowEndForAll = groupMeetingControl.inflateSettingsRow(R.layout.item_settings_subpage_nav_row)
            .also { row ->
                row.setupSubpageNavRow(
                    title = getString(R.string.meeting_end_for_all),
                    showChevron = false,
                    onClick = {
                        viewModel.endMeetingForAll()
                        Toast.makeText(
                            requireContext(),
                            R.string.meeting_end_for_all_done,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                )
                row.findViewById<TextView>(R.id.txtSubpageTitle).setTextColor(
                    ContextCompat.getColor(requireContext(), R.color.tb_danger)
                )
            }

        groupAudio.inflateSettingsRow(R.layout.item_settings_toggle_row).setupSubpageToggleRow(
            title = getString(R.string.meeting_auto_gain),
            checked = config.meetingAutoGain,
            showDivider = true,
            onCheckedChange = { checked -> viewModel.setMeetingAutoGain(checked) }
        )

        groupAudio.inflateSettingsRow(R.layout.item_settings_toggle_row).setupSubpageToggleRow(
            title = getString(R.string.meeting_noise_suppression),
            checked = config.meetingNoiseSuppression,
            onCheckedChange = { checked -> viewModel.setMeetingNoiseSuppression(checked) }
        )

        groupInfo.inflateSettingsRow(R.layout.item_settings_subpage_nav_row).setupSubpageNavRow(
            title = getString(R.string.meeting_info_title),
            showChevron = true,
            onClick = {
                (parentFragment as? MeetingFragment)?.showSubPage(MeetingInfoFragment())
            }
        )

        groupParticipants.inflateSettingsRow(R.layout.item_settings_subpage_nav_row).setupSubpageNavRow(
            title = getString(R.string.meeting_members_title),
            showChevron = true,
            showDivider = true,
            onClick = {
                (parentFragment as? MeetingFragment)?.showSubPage(MeetingMembersFragment())
            }
        )

        groupParticipants.inflateSettingsRow(R.layout.item_settings_subpage_nav_row).setupSubpageNavRow(
            title = getString(R.string.meeting_invite_members),
            showChevron = true,
            onClick = {
                (parentFragment as? MeetingFragment)?.showSubPage(InviteMembersFragment())
            }
        )

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    bindSessionDependentControls(state.conferenceActive)
                }
            }
        }
    }

    private fun bindSessionDependentControls(conferenceActive: Boolean) {
        val enabled = conferenceActive
        listOfNotNull(rowMuteAll, rowEndForAll).forEach { row ->
            row.findViewById<View>(R.id.subpageRowContent).apply {
                isEnabled = enabled
                alpha = if (enabled) 1f else 0.4f
            }
        }
    }
}
