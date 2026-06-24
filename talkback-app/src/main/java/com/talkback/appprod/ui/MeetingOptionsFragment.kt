package com.talkback.appprod.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.switchmaterial.SwitchMaterial
import com.talkback.appprod.R
import kotlinx.coroutines.launch

class MeetingOptionsFragment : Fragment() {
    private val viewModel: TalkViewModel by activityViewModels { TalkViewModelFactory(requireContext()) }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_meeting_options, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        view.findViewById<android.widget.TextView>(R.id.txtSettingsTitle)
            .setText(R.string.meeting_options_title)
        view.findViewById<View>(R.id.btnSettingsBack).setOnClickListener {
            (parentFragment as? MeetingFragment)?.hideSubPage()
        }

        val switchLock = view.findViewById<SwitchMaterial>(R.id.switchLockMeeting)
        val switchAgc = view.findViewById<SwitchMaterial>(R.id.switchAutoGain)
        val switchNs = view.findViewById<SwitchMaterial>(R.id.switchNoiseSuppression)
        val config = viewModel.loadConfig()
        switchLock.isChecked = config.meetingLocked
        switchAgc.isChecked = config.meetingAutoGain
        switchNs.isChecked = config.meetingNoiseSuppression

        view.findViewById<View>(R.id.rowMuteAll).setOnClickListener {
            lifecycleScope.launch {
                viewModel.toggleMeetingMute()
                Toast.makeText(requireContext(), R.string.meeting_mute_all_applied, Toast.LENGTH_SHORT).show()
            }
        }
        switchLock.setOnCheckedChangeListener { _, checked ->
            viewModel.setMeetingLocked(checked)
        }
        view.findViewById<View>(R.id.rowEndForAll).setOnClickListener {
            viewModel.endMeetingForAll()
            Toast.makeText(requireContext(), R.string.meeting_end_for_all_done, Toast.LENGTH_SHORT).show()
            (activity as? MainActivity)?.dismissMeetingOverlay()
        }
        switchAgc.setOnCheckedChangeListener { _, checked ->
            viewModel.setMeetingAutoGain(checked)
        }
        switchNs.setOnCheckedChangeListener { _, checked ->
            viewModel.setMeetingNoiseSuppression(checked)
        }

        view.findViewById<View>(R.id.rowMeetingInfo).setOnClickListener {
            (parentFragment as? MeetingFragment)?.showSubPage(MeetingInfoFragment())
        }
        view.findViewById<View>(R.id.rowMeetingMembers).setOnClickListener {
            (parentFragment as? MeetingFragment)?.showSubPage(MeetingMembersFragment())
        }
        view.findViewById<View>(R.id.rowInviteMembers).setOnClickListener {
            (parentFragment as? MeetingFragment)?.showSubPage(InviteMembersFragment())
        }
    }
}
